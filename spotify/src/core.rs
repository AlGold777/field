//! Ядро приёмника Spotify Connect без JNI: сессия, плеер, Connect, zeroconf, события.
//! JNI-слой (jni_glue.rs) и host-тест (examples/smoke.rs) используют только этот модуль.

use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{mpsc as std_mpsc, Arc, Mutex};
use std::time::{Duration, Instant};

use futures_util::StreamExt;
use librespot_connect::{ConnectConfig, Spirc};
use librespot_core::authentication::Credentials;
use librespot_core::cache::Cache;
use librespot_core::config::DeviceType;
use librespot_core::{Session, SessionConfig};
use librespot_discovery::Discovery;
use librespot_metadata::audio::UniqueFields;
use librespot_playback::audio_backend::{Sink, SinkResult};
use librespot_playback::config::{Bitrate, PlayerConfig};
use librespot_playback::convert::Converter;
use librespot_playback::decoder::AudioPacket;
use librespot_playback::mixer::softmixer::SoftMixer;
use librespot_playback::mixer::{Mixer, MixerConfig};
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
}

struct Shared {
    host: Arc<dyn Host>,
    /// После stop() приёмник больше не вызывает pcm().
    closed: AtomicBool,
}

// ---------------------------------------------------------------- FLUSH

/// Синхронный разбор событий плеера для FLUSH.
/// Плеер шлёт событие из своего потока до следующего `Sink::write`; sink перед каждой записью
/// вычитывает собственный приёмник событий (`try_recv`) и шлёт FLUSH до первого PCM новой позиции.
/// Если плеер не пишет (пауза, остановка), FLUSH шлёт асинхронная задача событий. Обе стороны
/// работают под одним мьютексом.
struct FlushHub {
    inner: Mutex<FlushInner>,
}

struct FlushInner {
    rx: Option<PlayerEventChannel>,
    /// Был EndOfTrack, и новая загрузка ещё не началась: следующий переход естественный.
    ended: bool,
}

impl FlushHub {
    fn new() -> Self {
        Self {
            inner: Mutex::new(FlushInner { rx: None, ended: false }),
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
            match ev {
                PlayerEvent::EndOfTrack { .. } => g.ended = true,
                // Каждая загрузка трека (и пользовательская, и gapless-переход) начинается с этого события.
                PlayerEvent::PlayRequestIdChanged { .. } => {
                    if g.ended {
                        g.ended = false;
                    } else {
                        need_flush = true;
                    }
                }
                PlayerEvent::Seeked { .. } => need_flush = true,
                PlayerEvent::Stopped { .. } => {
                    if g.ended {
                        g.ended = false;
                    } else {
                        need_flush = true;
                    }
                }
                PlayerEvent::Paused { .. } => g.ended = false,
                _ => {}
            }
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
    cmd_tx: mpsc::UnboundedSender<i32>,
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
        let _ = self.cmd_tx.send(cmd);
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
    match Cache::new(Some(dir), Some(dir), None, None) {
        Ok(c) => Some(c),
        Err(e) => {
            log::warn!("cache unavailable: {e}");
            None
        }
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
    cache_dir: PathBuf,
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
                host.event(EV_VOLUME, volume as i64, 0, None, None);
                if let Some(c) = open_cache(&cache_dir) {
                    c.save_volume(volume);
                }
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
    mut cmd_rx: mpsc::UnboundedReceiver<i32>,
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

    let mixer: Arc<dyn Mixer> = match SoftMixer::open(MixerConfig::default()) {
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
                        normalisation: false,
                        position_update_interval: None,
                        ditherer: None,
                        ..PlayerConfig::default()
                    };
                    Player::new(cfg, session.clone(), mixer.get_soft_volume(), move || {
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
                    cache_dir.clone(),
                ));
                player = Some(p);
            }
            Some(p) => p.set_session(session.clone()),
        }
        let Some(p) = player.clone() else { break 'outer };

        let connect_cfg = ConnectConfig {
            name: opts.device_name.clone(),
            device_type: DeviceType::Speaker,
            initial_volume: open_cache(&cache_dir)
                .and_then(|c| c.volume())
                .unwrap_or(u16::MAX / 2),
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
                    if let Some(cmd) = cmd {
                        let r = match cmd {
                            CMD_PLAY => active.spirc.play(),
                            CMD_PAUSE => active.spirc.pause(),
                            CMD_NEXT => active.spirc.next(),
                            CMD_PREVIOUS => active.spirc.prev(),
                            _ => Ok(()),
                        };
                        if let Err(e) = r {
                            host.event(EV_ERROR, 0, 0, Some(&format!("command {cmd}: {e}")), None);
                        }
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
