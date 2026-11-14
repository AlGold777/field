//! JNI-слой: app.soundfield.spotify.SpotifyNative.

use std::panic::{catch_unwind, AssertUnwindSafe};
use std::path::PathBuf;
use std::sync::atomic::{AtomicI64, Ordering};
use std::sync::{Arc, Mutex, Once};

use jni::objects::{GlobalRef, JFloatArray, JMethodID, JObject, JString, JValue};
use jni::signature::{Primitive, ReturnType};
use jni::sys::{jint, jlong, jstring};
use jni::{JNIEnv, JavaVM};

use crate::core::{Host, Options, Receiver};

static NEXT_HANDLE: AtomicI64 = AtomicI64::new(1);
static ACTIVE: Mutex<Option<(i64, Arc<Receiver>)>> = Mutex::new(None);
static LOG_HOST: Mutex<Option<Arc<JavaHost>>> = Mutex::new(None);
static LOGGER_INIT: Once = Once::new();

struct JavaHost {
    vm: JavaVM,
    sink: GlobalRef,
    m_pcm: JMethodID,
    m_event: JMethodID,
    m_log: JMethodID,
    /// Переиспользуемый float[]; используется только потоком плеера.
    arr: Mutex<Option<(GlobalRef, usize)>>,
}

impl JavaHost {
    fn with_env<R>(&self, f: impl FnOnce(&mut JNIEnv) -> jni::errors::Result<R>) -> Option<R> {
        let mut env = match self.vm.get_env() {
            Ok(e) => e,
            // Поток остаётся присоединённым до своего завершения.
            Err(_) => self.vm.attach_current_thread_permanently().ok()?,
        };
        let r = f(&mut env);
        if env.exception_check().unwrap_or(false) {
            let _ = env.exception_describe();
            let _ = env.exception_clear();
        }
        r.ok()
    }

    fn log_line(&self, msg: &str) {
        self.with_env(|env| {
            env.with_local_frame(8, |env| {
                let s = env.new_string(msg)?;
                // SAFETY: сигнатура onLog(String): Unit
                unsafe {
                    env.call_method_unchecked(
                        &self.sink,
                        self.m_log,
                        ReturnType::Primitive(Primitive::Void),
                        &[JValue::Object(&s).as_jni()],
                    )?;
                }
                Ok(())
            })
        });
    }
}

impl Host for JavaHost {
    fn pcm(&self, samples: &[f32]) {
        let frames = (samples.len() / 2) as jint;
        if frames == 0 {
            return;
        }
        let Ok(mut guard) = self.arr.lock() else { return };
        self.with_env(|env| {
            if guard.as_ref().is_none_or(|(_, cap)| *cap < samples.len()) {
                let cap = samples.len().max(8192);
                let local = env.new_float_array(cap as jint)?;
                let global = env.new_global_ref(&local)?;
                env.delete_local_ref(local)?;
                *guard = Some((global, cap));
            }
            let Some((global, _)) = guard.as_ref() else { return Ok(()) };
            // SAFETY: глобальная ссылка создана выше из float[]
            let arr = unsafe { JFloatArray::from_raw(global.as_obj().as_raw()) };
            env.set_float_array_region(&arr, 0, samples)?;
            // SAFETY: сигнатура onPcm(float[], int): Unit
            unsafe {
                env.call_method_unchecked(
                    &self.sink,
                    self.m_pcm,
                    ReturnType::Primitive(Primitive::Void),
                    &[JValue::Object(&arr).as_jni(), JValue::Int(frames).as_jni()],
                )?;
            }
            Ok(())
        });
    }

    fn event(&self, kind: i32, a: i64, b: i64, text1: Option<&str>, text2: Option<&str>) {
        self.with_env(|env| {
            env.with_local_frame(16, |env| {
                let s1 = match text1 {
                    Some(t) => JObject::from(env.new_string(t)?),
                    None => JObject::null(),
                };
                let s2 = match text2 {
                    Some(t) => JObject::from(env.new_string(t)?),
                    None => JObject::null(),
                };
                // SAFETY: сигнатура onEvent(int, long, long, String, String): Unit
                unsafe {
                    env.call_method_unchecked(
                        &self.sink,
                        self.m_event,
                        ReturnType::Primitive(Primitive::Void),
                        &[
                            JValue::Int(kind).as_jni(),
                            JValue::Long(a).as_jni(),
                            JValue::Long(b).as_jni(),
                            JValue::Object(&s1).as_jni(),
                            JValue::Object(&s2).as_jni(),
                        ],
                    )?;
                }
                Ok(())
            })
        });
    }
}

struct JniLogger;

impl log::Log for JniLogger {
    fn enabled(&self, m: &log::Metadata) -> bool {
        if m.target().starts_with("sfspotify") {
            m.level() <= log::Level::Info
        } else {
            m.level() <= log::Level::Warn
        }
    }

    fn log(&self, r: &log::Record) {
        if !self.enabled(r.metadata()) {
            return;
        }
        let host = match LOG_HOST.lock() {
            Ok(g) => g.clone(),
            Err(_) => None,
        };
        if let Some(h) = host {
            h.log_line(&format!("{} {}: {}", r.level(), r.target(), r.args()));
        }
    }

    fn flush(&self) {}
}

fn build_host(env: &mut JNIEnv, sink: &JObject) -> jni::errors::Result<JavaHost> {
    let vm = env.get_java_vm()?;
    let class = env.get_object_class(sink)?;
    let m_pcm = env.get_method_id(&class, "onPcm", "([FI)V")?;
    let m_event = env.get_method_id(
        &class,
        "onEvent",
        "(IJJLjava/lang/String;Ljava/lang/String;)V",
    )?;
    let m_log = env.get_method_id(&class, "onLog", "(Ljava/lang/String;)V")?;
    let sink = env.new_global_ref(sink)?;
    Ok(JavaHost {
        vm,
        sink,
        m_pcm,
        m_event,
        m_log,
        arr: Mutex::new(None),
    })
}

fn do_start(
    env: &mut JNIEnv,
    name: &JString,
    cache: &JString,
    initial_volume: jint,
    sink: &JObject,
) -> jlong {
    let Ok(mut active) = ACTIVE.lock() else { return 0 };
    if active.is_some() {
        return 0;
    }
    let (Ok(name), Ok(cache)) = (env.get_string(name), env.get_string(cache)) else {
        return 0;
    };
    let name: String = name.into();
    let cache: String = cache.into();
    let host = match build_host(env, sink) {
        Ok(h) => Arc::new(h),
        Err(_) => {
            let _ = env.exception_clear();
            return 0;
        }
    };
    LOGGER_INIT.call_once(|| {
        if log::set_logger(&JniLogger).is_ok() {
            log::set_max_level(log::LevelFilter::Info);
        }
    });
    if let Ok(mut g) = LOG_HOST.lock() {
        *g = Some(host.clone());
    }
    let opts = Options {
        device_name: name,
        cache_dir: PathBuf::from(cache),
        // На Android службу объявляет Kotlin (NsdManager).
        native_mdns: !cfg!(target_os = "android"),
        initial_volume: initial_volume.clamp(0, i32::from(u16::MAX)) as u16,
    };
    match Receiver::start(opts, host) {
        Ok(r) => {
            let h = NEXT_HANDLE.fetch_add(1, Ordering::Relaxed);
            *active = Some((h, Arc::new(r)));
            h
        }
        Err(e) => {
            if let Ok(mut g) = LOG_HOST.lock() {
                if let Some(h) = g.take() {
                    h.log_line(&format!("start failed: {e}"));
                }
            }
            0
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_app_soundfield_spotify_SpotifyNative_start<'l>(
    mut env: JNIEnv<'l>,
    _this: JObject<'l>,
    device_name: JString<'l>,
    cache_dir: JString<'l>,
    initial_volume: jint,
    sink: JObject<'l>,
) -> jlong {
    catch_unwind(AssertUnwindSafe(|| {
        do_start(&mut env, &device_name, &cache_dir, initial_volume, &sink)
    }))
    .unwrap_or(0)
}

#[no_mangle]
pub extern "system" fn Java_app_soundfield_spotify_SpotifyNative_stop<'l>(
    _env: JNIEnv<'l>,
    _this: JObject<'l>,
    handle: jlong,
) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        let taken = match ACTIVE.lock() {
            Ok(mut g) => match g.as_ref() {
                Some((h, _)) if *h == handle => g.take().map(|(_, r)| r),
                _ => None,
            },
            Err(_) => None,
        };
        if let Some(r) = taken {
            r.stop();
            drop(r);
            // Последняя ссылка на JavaHost (и GlobalRef на sink) уходит вместе с Receiver;
            // поток, застрявший в onPcm, удерживает её до возврата.
            if let Ok(mut g) = LOG_HOST.lock() {
                g.take();
            }
        }
    }));
}

#[no_mangle]
pub extern "system" fn Java_app_soundfield_spotify_SpotifyNative_command<'l>(
    _env: JNIEnv<'l>,
    _this: JObject<'l>,
    handle: jlong,
    command: jint,
) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        if let Ok(g) = ACTIVE.lock() {
            if let Some((h, r)) = g.as_ref() {
                if *h == handle {
                    r.command(command);
                }
            }
        }
    }));
}

#[no_mangle]
pub extern "system" fn Java_app_soundfield_spotify_SpotifyNative_setVolume<'l>(
    _env: JNIEnv<'l>,
    _this: JObject<'l>,
    handle: jlong,
    volume: jint,
) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        if let Ok(g) = ACTIVE.lock() {
            if let Some((h, r)) = g.as_ref() {
                if *h == handle {
                    r.set_volume(volume.clamp(0, i32::from(u16::MAX)) as u16);
                }
            }
        }
    }));
}

#[no_mangle]
pub extern "system" fn Java_app_soundfield_spotify_SpotifyNative_version<'l>(
    env: JNIEnv<'l>,
    _this: JObject<'l>,
) -> jstring {
    catch_unwind(AssertUnwindSafe(|| {
        let s = format!("sfspotify {} / librespot 0.8.0", env!("CARGO_PKG_VERSION"));
        env.new_string(s)
            .map(|j: JString| j.into_raw())
            .unwrap_or(std::ptr::null_mut())
    }))
    .unwrap_or(std::ptr::null_mut())
}

