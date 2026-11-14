//! Host-проверка без JNI: запуск приёмника, лог событий, остановка.
//! Запуск: cargo run --example smoke -- [секунд_работы] [имя_устройства]

use std::sync::Arc;
use std::time::{Duration, Instant};

use sfspotify::core::{Host, Options, Receiver};

struct LogHost;

impl Host for LogHost {
    fn pcm(&self, samples: &[f32]) {
        println!("PCM frames={}", samples.len() / 2);
    }
    fn event(&self, kind: i32, a: i64, b: i64, t1: Option<&str>, t2: Option<&str>) {
        println!("EVENT kind={kind} a={a} b={b} t1={t1:?} t2={t2:?}");
    }
}

struct StderrLog;
impl log::Log for StderrLog {
    fn enabled(&self, m: &log::Metadata) -> bool {
        m.level() <= log::Level::Info
    }
    fn log(&self, r: &log::Record) {
        eprintln!("LOG {} {}: {}", r.level(), r.target(), r.args());
    }
    fn flush(&self) {}
}

fn main() {
    let args: Vec<String> = std::env::args().collect();
    let secs: u64 = args.get(1).and_then(|s| s.parse().ok()).unwrap_or(10);
    let name = args.get(2).cloned().unwrap_or_else(|| "SoundField Smoke".into());
    let _ = log::set_logger(&StderrLog);
    log::set_max_level(log::LevelFilter::Info);
    let dir = std::env::temp_dir().join(format!("sfspotify-smoke-{}", std::process::id()));
    let r = Receiver::start(
        Options { device_name: name, cache_dir: dir.clone(), native_mdns: true, initial_volume: u16::MAX },
        Arc::new(LogHost),
    )
    .expect("start");
    std::thread::sleep(Duration::from_secs(secs));
    let t = Instant::now();
    r.stop();
    println!("STOPPED in {} ms", t.elapsed().as_millis());
    let _ = std::fs::remove_dir_all(dir);
}
