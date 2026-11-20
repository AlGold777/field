//! Ядро приёмника Spotify Connect без JNI: сессия, плеер, Connect, zeroconf, события.
//! JNI-слой (jni_glue.rs) и host-тест (examples/smoke.rs) используют только этот модуль.

use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, AtomicU32, Ordering};
use std::sync::{mpsc as std_mpsc, Arc, Mutex};
use std::time::{Duration, Instant};

use futures_util::StreamExt;
use librespot_connect::{ConnectConfig, Spirc};
use librespot_core::authentication::Credentials;
use librespot_core::cache::Cache;
use librespot_core::config::DeviceType;
use librespot_core::error::ErrorKind;
use librespot_core::{Session, SessionConfig};
use librespot_discovery::Discovery;
use librespot_metadata::audio::UniqueFields;
use librespot_playback::audio_backend::{Sink, SinkResult};
use librespot_playback::config::{
    Bitrate, NormalisationMethod, NormalisationType, PlayerConfig, VolumeCtrl,
};
use librespot_playback::convert::Converter;
use librespot_playback::decoder::AudioPacket;
use librespot_playback::mixer::softmixer::SoftMixer;
use librespot_playback::mixer::{Mixer, MixerConfig, VolumeGetter};
use librespot_playback::player::{Player, PlayerEvent, PlayerEventChannel};
use tokio::sync::{mpsc, oneshot};

pub const EV_SESSION_CONNECTED: i32 = 1;
pub const EV_SESSION_DISCONNECTED: i32 = 2;
pub const EV_PLAYING: i32 = 3;
pub const EV_PAUSED: i32 = 4;
pub const EV_TRACK: i32 = 5;
pub const EV_FLUSH: i32 = 6;
pub const EV_STOPPED: i32 = 7;
pub const EV_ERROR: i32 = 8;
pub const EV_VOLUME: i32 = 9;
pub const EV_DISCOVERY_READY: i32 = 10;

pub const CMD_PLAY: i32 = 0;
pub const CMD_PAUSE: i32 = 1;
pub const CMD_NEXT: i32 = 2;
pub const CMD_PREVIOUS: i32 = 3;

const CPATH: &str = "/";
const SPIRC_START_TIMEOUT: Duration = Duration::from_secs(30);
const BACKOFF_MIN: Duration = Duration::from_secs(2);
const BACKOFF_MAX: Duration = Duration::from_secs(60);
const STOP_TOTAL_TIMEOUT: Duration = Duration::from_secs(8);
/// Громкость Spotify отсчитывается от −14 LUFS, файлы SoundField приводятся к −16 LUFS.
const NORMALISATION_PREGAIN_DB: f64 = -2.0;

/// Приёмник событий и звука. Все методы вызываются из нативных потоков.
pub trait Host: Send + Sync + 'static {
    /// Interleaved stereo f32, 44100 Гц, `samples.len() / 2` кадров. Может блокировать.
    fn pcm(&self, samples: &[f32]);
    fn event(&self, kind: i32, a: i64, b: i64, text1: Option<&str>, text2: Option<&str>);
}

pub struct Options {
    pub device_name: String,
    pub cache_dir: PathBuf,
    /// true: mDNS-ответчик внутри библиотеки (host); false: службу объявляет вызывающий.
    pub native_mdns: bool,
    /// Положение регулятора громкости, которое устройство сообщает приложению Spotify (0..65535).
    pub initial_volume: u16,
}

struct Shared {
    host: Arc<dyn Host>,
    /// После stop() приёмник больше не вызывает pcm().
    closed: AtomicBool,
    /// Положение регулятора громкости (0..65535). Звук им не ослабляется: см. [UnityVolume].
    volume: AtomicU32,
}

/// Звук уходит вызывающему с единичным усилением. Громкость из приложения Spotify приходит событием
/// EV_VOLUME, и применяет её вызывающий своим регулятором: иначе сигнал ослаблялся бы до обработки.
struct UnityVolume;

impl VolumeGetter for UnityVolume {
    fn attenuation_factor(&self) -> f64 {
        1.0
    }
}

enum Cmd {
    Transport(i32),
    Volume(u16),
}

// ---------------------------------------------------------------- FLUSH

/// Событие плеера в той части, которая решает судьбу принятого, но не сыгранного звука.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum FlushSignal {
    EndOfTrack,
    /// Загрузка трека: и пользовательская, и переход к следующему (`PlayRequestIdChanged`).
    Load,
    Seeked,
    Stopped,
    Paused,
}

/// Решает, устарел ли уже принятый звук. Естественный переход (EndOfTrack, затем загрузка или остановка)
/// сбросом не считается: хвост трека должен доиграть.
#[derive(Default)]
pub struct FlushTracker {
    /// Был EndOfTrack, и новая загрузка ещё не началась: следующий переход естественный.
    ended: bool,
}

impl FlushTracker {
    /// true: нужен FLUSH.
    pub fn on(&mut self, signal: FlushSignal) -> bool {
        match signal {
            FlushSignal::EndOfTrack => {
                self.ended = true;
                false
            }
            FlushSignal::Load | FlushSignal::Stopped => !std::mem::take(&mut self.ended),
            FlushSignal::Seeked => true,
            FlushSignal::Paused => {
                self.ended = false;
                false
            }
        }
    }
}

/// Синхронный разбор событий плеера для FLUSH.
/// Плеер шлёт событие из своего потока до следующего `Sink::write`; sink перед каждой записью
/// вычитывает собственный приёмник событий (`try_recv`) и шлёт FLUSH до первого PCM новой позиции.
/// Если плеер не пишет (пауза, остановка), FLUSH шлёт асинхронная задача событий. Обе стороны
/// работают под одним мьютексом, и он удерживается на время вызова `Host::event`: пока вызывающий
/// обрабатывает FLUSH, запись нового звука не начинается.
struct FlushHub {
    inner: Mutex<FlushInner>,
}

struct FlushInner {
    rx: Option<PlayerEventChannel>,
    tracker: FlushTracker,
}

impl FlushHub {
    fn new() -> Self {
        Self {
            inner: Mutex::new(FlushInner { rx: None, tracker: FlushTracker::default() }),
        }
    }

    fn attach(&self, rx: PlayerEventChannel) {
        if let Ok(mut g) = self.inner.lock() {
            g.rx = Some(rx);
        }
    }

    fn process_pending(&self, host: &dyn Host) {
        let Ok(mut g) = self.inner.lock() else { return };
        let mut need_flush = false;
        while let Some(ev) = g.rx.as_mut().and_then(|rx| rx.try_recv().ok()) {
            let signal = match ev {
                PlayerEvent::EndOfTrack { .. } => FlushSignal::EndOfTrack,
                // Каждая загрузка трека (и пользовательская, и gapless-переход) начинается с этого события.
                PlayerEvent::PlayRequestIdChanged { .. } => FlushSignal::Load,
                PlayerEvent::Seeked { .. } => FlushSignal::Seeked,
                PlayerEvent::Stopped { .. } => FlushSignal::Stopped,
                PlayerEvent::Paused { .. } => FlushSignal::Paused,
                _ => continue,
            };
            need_flush |= g.tracker.on(signal);
        }
        if need_flush {
            host.event(EV_FLUSH, 0, 0, None, None);
        }
    }
}

// ---------------------------------------------------------------- Sink

struct HostSink {
    shared: Arc<Shared>,
    hub: Arc<FlushHub>,
    buf: Vec<f32>,
}

impl Sink for HostSink {
    fn write(&mut self, packet: AudioPacket, _converter: &mut Converter) -> SinkResult<()> {
        if self.shared.closed.load(Ordering::Acquire) {
            return Ok(());
        }
        self.hub.process_pending(self.shared.host.as_ref());
        let AudioPacket::Samples(samples) = packet else {
            return Ok(());
        };
        if samples.is_empty() {
            return Ok(());
        }
        self.buf.clear();
        self.buf.extend(samples.iter().map(|&s| s as f32));
        if self.buf.len() % 2 == 1 {
            self.buf.pop();
        }
        self.shared.host.pcm(&self.buf);
        Ok(())
    }
}

// ---------------------------------------------------------------- Receiver

pub struct Receiver {
    shared: Arc<Shared>,
    rt: Mutex<Option<tokio::runtime::Runtime>>,
    task: Mutex<Option<tokio::task::JoinHandle<()>>>,
    shutdown_tx: Mutex<Option<oneshot::Sender<()>>>,
    cmd_tx: mpsc::UnboundedSender<Cmd>,
}

impl Receiver {
    pub fn start(opts: Options, host: Arc<dyn Host>) -> Result<Receiver, String> {
        let rt = tokio::runtime::Builder::new_multi_thread()
            .worker_threads(2)
            .enable_all()
            .thread_name("sfspotify-rt")
            .build()
            .map_err(|e| format!("tokio runtime: {e}"))?;
        let shared = Arc::new(Shared {
            host,
            closed: AtomicBool::new(false),
            volume: AtomicU32::new(u32::from(opts.initial_volume)),
        });
        let (shutdown_tx, shutdown_rx) = oneshot::channel();
        let (cmd_tx, cmd_rx) = mpsc::unbounded_channel();
        let task = rt.spawn(supervisor(opts, shared.clone(), cmd_rx, shutdown_rx));
        Ok(Receiver {
            shared,
            rt: Mutex::new(Some(rt)),
            task: Mutex::new(Some(task)),
            shutdown_tx: Mutex::new(Some(shutdown_tx)),
            cmd_tx,
        })
    }

    pub fn command(&self, cmd: i32) {
        let _ = self.cmd_tx.send(Cmd::Transport(cmd));
    }

    /// Положение регулятора громкости изменил вызывающий: сообщаем его приложению Spotify.
    /// Без сеанса значение запоминается и уходит как начальное при подключении.
    pub fn set_volume(&self, volume: u16) {
        self.shared.volume.store(u32::from(volume), Ordering::Release);
        let _ = self.cmd_tx.send(Cmd::Volume(volume));
    }

    /// Блокируется до полного завершения, но не дольше ~8 с: зависшие потоки отсоединяются.
    pub fn stop(&self) {
        self.shared.closed.store(true, Ordering::Release);
        if let Ok(mut g) = self.shutdown_tx.lock() {
            if let Some(tx) = g.take() {
                let _ = tx.send(());
            }
        }
        let rt = self.rt.lock().ok().and_then(|mut g| g.take());
        let task = self.task.lock().ok().and_then(|mut g| g.take());
        let (done_tx, done_rx) = std_mpsc::channel::<()>();
        let _ = std::thread::Builder::new()
            .name("sfspotify-stop".into())
            .spawn(move || {
                if let Some(rt) = rt {
                    if let Some(task) = task {
                        let _ = rt.block_on(async {
                            tokio::time::timeout(Duration::from_secs(6), task).await
                        });
                    }
                    rt.shutdown_timeout(Duration::from_secs(1));
                }
                let _ = done_tx.send(());
            });
        if done_rx.recv_timeout(STOP_TOTAL_TIMEOUT).is_err() {
            log::warn!("stop: teardown timed out, detaching");
        }
    }
}

impl Drop for Receiver {
    fn drop(&mut self) {
        // Если stop() не вызывали, runtime не должен блокировать поток вызывающего.
        if let Ok(mut g) = self.rt.lock() {
            if let Some(rt) = g.take() {
                self.shared.closed.store(true, Ordering::Release);
                rt.shutdown_background();
            }
        }
    }
}

// ---------------------------------------------------------------- supervisor

fn open_cache(dir: &Path) -> Option<Cache> {
    // Cache::new(credentials, volume, audio, size_limit): учётные данные и громкость — в каталоге приёмника;
    // аудио-кэш выключен намеренно — PCM сразу уходит в SoundField, хранить его на диске незачем.
    match Cache::new(Some(dir), Some(dir), None, None) {
        Ok(c) => Some(c),
        Err(e) => {
            log::warn!("cache unavailable: {e}");
            None
        }
    }
}

/// Сохранённые учётные данные отвергнуты сервером: повторять их бессмысленно, ждём новый выбор устройства.
fn forget_credentials(dir: &Path) {
    if let Err(e) = std::fs::remove_file(dir.join("credentials.json")) {
        log::warn!("cannot remove rejected credentials: {e}");
    }
}

fn load_device_id(dir: &Path) -> String {
    let path = dir.join("device_id");
    if let Ok(s) = std::fs::read_to_string(&path) {
        let s = s.trim().to_string();
        if s.len() >= 16 {
            return s;
        }
    }
    let id = SessionConfig::default().device_id;
    let _ = std::fs::create_dir_all(dir);
    if let Err(e) = std::fs::write(&path, &id) {
        log::warn!("cannot persist device id: {e}");
    }
    id
}

async fn next_creds(d: &mut Option<Discovery>, host: &dyn Host) -> Credentials {
    loop {
        match d.as_mut() {
            Some(disc) => match disc.next().await {
                Some(c) => return c,
                None => {
                    host.event(EV_ERROR, 0, 0, Some("discovery stopped"), None);
                    *d = None;
                }
            },
            None => std::future::pending::<()>().await,
        }
    }
}

fn artists_text(item: &librespot_metadata::audio::AudioItem) -> String {
    match &item.unique_fields {
        UniqueFields::Track { artists, .. } => artists
            .0
            .iter()
            .map(|a| a.name.as_str())
            .collect::<Vec<_>>()
            .join(", "),
        UniqueFields::Local { artists, .. } => artists.clone().unwrap_or_default(),
        UniqueFields::Episode { show_name, .. } => show_name.clone(),
    }
}

async fn event_task(
    mut rx: PlayerEventChannel,
    shared: Arc<Shared>,
    hub: Arc<FlushHub>,
) {
    let host = shared.host.as_ref();
    while let Some(ev) = rx.recv().await {
        // FLUSH уходит раньше связанных с ним STOPPED/PLAYING.
        hub.process_pending(host);
        match ev {
            PlayerEvent::Playing { position_ms, .. } => {
                host.event(EV_PLAYING, position_ms as i64, 0, None, None)
            }
            PlayerEvent::Paused { position_ms, .. } => {
                host.event(EV_PAUSED, position_ms as i64, 0, None, None)
            }
            PlayerEvent::Stopped { .. } => host.event(EV_STOPPED, 0, 0, None, None),
            PlayerEvent::TrackChanged { audio_item } => {
                let artists = artists_text(&audio_item);
                host.event(
                    EV_TRACK,
                    audio_item.duration_ms as i64,
                    0,
                    Some(&audio_item.name),
                    Some(&artists),
                );
            }
            PlayerEvent::VolumeChanged { volume } => {
                shared.volume.store(u32::from(volume), Ordering::Release);
                host.event(EV_VOLUME, volume as i64, 0, None, None);
            }
            PlayerEvent::Unavailable { .. } => {
                host.event(EV_ERROR, 0, 0, Some("track unavailable"), None)
            }
            _ => {}
        }
    }
}

struct Active {
    spirc: Spirc,
    task: tokio::task::JoinHandle<()>,
    task_done: bool,
    session: Session,
    started: Instant,
}

async fn supervisor(
    opts: Options,
    shared: Arc<Shared>,
    mut cmd_rx: mpsc::UnboundedReceiver<Cmd>,
    mut shutdown_rx: oneshot::Receiver<()>,
) {
    let host = shared.host.clone();
    let host = host.as_ref();
    let cache_dir = opts.cache_dir.clone();
    let device_id = load_device_id(&cache_dir);
    let session_config = SessionConfig {
        device_id: device_id.clone(),
        ..SessionConfig::default()
    };

    // zeroconf: живёт всё время работы приёмника.
    let mut discovery: Option<Discovery> = {
        let mut b = Discovery::builder(device_id.clone(), session_config.client_id.clone())
            .name(opts.device_name.clone())
            .device_type(DeviceType::Speaker);
        if !opts.native_mdns {
            b = b.without_zeroconf();
        }
        match b.launch() {
            Ok(d) => {
                host.event(
                    EV_DISCOVERY_READY,
                    d.port() as i64,
                    if opts.native_mdns { 1 } else { 0 },
                    Some(CPATH),
                    None,
                );
                Some(d)
            }
            Err(e) => {
                host.event(EV_ERROR, 0, 0, Some(&format!("discovery: {e}")), None);
                None
            }
        }
    };

    // Микшер хранит только положение регулятора для Connect (линейно, без пересчёта); звук он не трогает.
    let mixer_config = MixerConfig {
        volume_ctrl: VolumeCtrl::Linear,
        ..MixerConfig::default()
    };
    let mixer: Arc<dyn Mixer> = match SoftMixer::open(mixer_config) {
        Ok(m) => Arc::new(m),
        Err(e) => {
            host.event(EV_ERROR, 0, 0, Some(&format!("mixer: {e}")), None);
            finish(None, discovery).await;
            return;
        }
    };
    let hub = Arc::new(FlushHub::new());
    let mut player: Option<Arc<Player>> = None;

    let mut pending: Option<Credentials> = open_cache(&cache_dir).and_then(|c| c.credentials());
    let mut backoff = BACKOFF_MIN;

    'outer: loop {
        // 1. Ждём учётные данные: из кэша, от zeroconf или после паузы повтора.
        let creds = match pending.take() {
            Some(c) => c,
            None => tokio::select! {
                c = next_creds(&mut discovery, host) => c,
                cmd = cmd_rx.recv() => { let _ = cmd; continue 'outer; }
                _ = &mut shutdown_rx => break 'outer,
            },
        };

        // 2. Сессия + Connect.
        let session = Session::new(session_config.clone(), open_cache(&cache_dir));
        match &player {
            None => {
                let p = {
                    let (shared2, hub2) = (shared.clone(), hub.clone());
                    let cfg = PlayerConfig {
                        bitrate: Bitrate::Bitrate320,
                        gapless: true,
                        // Только ослабление громких треков по данным Spotify, без динамической обработки:
                        // пики ограничивает предлимитер вызывающего.
                        normalisation: true,
                        normalisation_type: NormalisationType::Auto,
                        normalisation_method: NormalisationMethod::Basic,
                        normalisation_pregain_db: NORMALISATION_PREGAIN_DB,
                        position_update_interval: None,
                        ditherer: None,
                        ..PlayerConfig::default()
                    };
                    Player::new(cfg, session.clone(), Box::new(UnityVolume), move || {
                        Box::new(HostSink {
                            shared: shared2,
                            hub: hub2,
                            buf: Vec::with_capacity(8192),
                        })
                    })
                };
                hub.attach(p.get_player_event_channel());
                tokio::spawn(event_task(
                    p.get_player_event_channel(),
                    shared.clone(),
                    hub.clone(),
                ));
                player = Some(p);
            }
            Some(p) => p.set_session(session.clone()),
        }
        let Some(p) = player.clone() else { break 'outer };

        let connect_cfg = ConnectConfig {
            name: opts.device_name.clone(),
            device_type: DeviceType::Speaker,
            initial_volume: shared.volume.load(Ordering::Acquire).min(u32::from(u16::MAX)) as u16,
            ..ConnectConfig::default()
        };
        let started = tokio::select! {
            r = tokio::time::timeout(
                SPIRC_START_TIMEOUT,
                Spirc::new(connect_cfg, session.clone(), creds, p.clone(), mixer.clone()),
            ) => Some(r),
            _ = &mut shutdown_rx => None,
        };
        let (spirc, spirc_future) = match started {
            None => {
                session.shutdown();
                break 'outer;
            }
            Some(Ok(Ok(v))) => v,
            Some(Ok(Err(e))) => {
                session.shutdown();
                if matches!(e.kind, ErrorKind::PermissionDenied | ErrorKind::Unauthenticated) {
                    forget_credentials(&cache_dir);
                }
                host.event(EV_ERROR, 0, 0, Some(&format!("connect: {e}")), None);
                if !retry_wait(
                    &mut backoff, &mut pending, &mut discovery, &mut shutdown_rx, host, &cache_dir,
                )
                .await
                {
                    break 'outer;
                }
                continue 'outer;
            }
            Some(Err(_)) => {
                session.shutdown();
                host.event(EV_ERROR, 0, 0, Some("connect: timeout"), None);
                if !retry_wait(
                    &mut backoff, &mut pending, &mut discovery, &mut shutdown_rx, host, &cache_dir,
                )
                .await
                {
                    break 'outer;
                }
                continue 'outer;
            }
        };
        let mut active = Active {
            spirc,
            task: tokio::spawn(spirc_future),
            task_done: false,
            session: session.clone(),
            started: Instant::now(),
        };
        let user = session.username();
        host.event(EV_SESSION_CONNECTED, 0, 0, Some(&user), None);

        // 3. Работа сессии.
        enum Why {
            Ended,
            NewAccount(Credentials),
            Shutdown,
        }
        let why = loop {
            tokio::select! {
                r = &mut active.task => {
                    active.task_done = true;
                    if let Err(e) = r {
                        host.event(EV_ERROR, 0, 0, Some(&format!("spirc task: {e}")), None);
                    }
                    break Why::Ended;
                }
                c = next_creds(&mut discovery, host) => break Why::NewAccount(c),
                cmd = cmd_rx.recv() => {
                    let r = match cmd {
                        Some(Cmd::Transport(CMD_PLAY)) => active.spirc.play(),
                        Some(Cmd::Transport(CMD_PAUSE)) => active.spirc.pause(),
                        Some(Cmd::Transport(CMD_NEXT)) => active.spirc.next(),
                        Some(Cmd::Transport(CMD_PREVIOUS)) => active.spirc.prev(),
                        Some(Cmd::Volume(v)) => active.spirc.set_volume(v),
                        _ => Ok(()),
                    };
                    if let Err(e) = r {
                        host.event(EV_ERROR, 0, 0, Some(&format!("command: {e}")), None);
                    }
                }
                _ = &mut shutdown_rx => break Why::Shutdown,
            }
        };

        // 4. Разбор сессии.
        let lived = active.started.elapsed();
        let reason = match &why {
            Why::Ended => "session ended",
            Why::NewAccount(_) => "new account selected",
            Why::Shutdown => "receiver stopped",
        };
        teardown(active).await;
        p.stop();
        host.event(EV_SESSION_DISCONNECTED, 0, 0, Some(reason), None);

        match why {
            Why::Shutdown => break 'outer,
            Why::NewAccount(c) => {
                pending = Some(c);
                backoff = BACKOFF_MIN;
            }
            Why::Ended => {
                if lived > Duration::from_secs(30) {
                    backoff = BACKOFF_MIN;
                }
                if !retry_wait(
                    &mut backoff, &mut pending, &mut discovery, &mut shutdown_rx, host, &cache_dir,
                )
                .await
                {
                    break 'outer;
                }
            }
        }
    }

    finish(player, discovery).await;
}

/// Пауза перед повтором. Новые данные от zeroconf прерывают её. false: приёмник останавливают.
async fn retry_wait(
    backoff: &mut Duration,
    pending: &mut Option<Credentials>,
    discovery: &mut Option<Discovery>,
    shutdown_rx: &mut oneshot::Receiver<()>,
    host: &dyn Host,
    cache_dir: &Path,
) -> bool {
    let delay = *backoff;
    *backoff = (*backoff * 2).min(BACKOFF_MAX);
    tokio::select! {
        _ = tokio::time::sleep(delay) => {
            *pending = open_cache(cache_dir).and_then(|c| c.credentials());
            true
        }
        c = next_creds(discovery, host) => {
            *pending = Some(c);
            *backoff = BACKOFF_MIN;
            true
        }
        _ = shutdown_rx => false,
    }
}

async fn teardown(mut a: Active) {
    let _ = a.spirc.shutdown();
    if !a.task_done && tokio::time::timeout(Duration::from_secs(2), &mut a.task).await.is_err() {
        a.task.abort();
    }
    a.session.shutdown();
}

async fn finish(player: Option<Arc<Player>>, discovery: Option<Discovery>) {
    if let Some(d) = discovery {
        let _ = tokio::time::timeout(Duration::from_millis(1500), d.shutdown()).await;
    }
    if let Some(p) = player {
        p.stop();
        // Drop плеера ждёт его поток; поток может сидеть в Host::pcm.
        let _ = tokio::time::timeout(
            Duration::from_secs(2),
            tokio::task::spawn_blocking(move || drop(p)),
        )
        .await;
    }
}

#[cfg(test)]
mod tests {
    use super::{FlushSignal::*, FlushTracker};

    fn run(signals: &[super::FlushSignal]) -> Vec<bool> {
        let mut t = FlushTracker::default();
        signals.iter().map(|s| t.on(*s)).collect()
    }

    #[test]
    fn natural_transition_keeps_the_tail() {
        assert_eq!(run(&[EndOfTrack, Load]), [false, false]);
    }

    #[test]
    fn end_of_queue_keeps_the_tail() {
        assert_eq!(run(&[EndOfTrack, Stopped]), [false, false]);
    }

    #[test]
    fn user_skip_seek_and_stop_flush() {
        assert_eq!(run(&[Load]), [true]);
        assert_eq!(run(&[Seeked]), [true]);
        assert_eq!(run(&[Stopped]), [true]);
    }

    #[test]
    fn natural_transition_does_not_hide_the_next_skip() {
        assert_eq!(run(&[EndOfTrack, Load, Load]), [false, false, true]);
    }

    #[test]
    fn seek_flushes_even_right_after_end_of_track() {
        assert_eq!(run(&[EndOfTrack, Seeked]), [false, true]);
    }

    #[test]
    fn pause_cancels_the_pending_natural_transition() {
        assert_eq!(run(&[EndOfTrack, Paused, Load]), [false, false, true]);
    }
}
