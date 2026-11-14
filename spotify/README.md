# sfspotify

Приёмник Spotify Connect для SoundField: librespot 0.8.0 + JNI-мост `app.soundfield.spotify.SpotifyNative`.
Звук отдаётся в Kotlin через `SpotifySink.onPcm` (interleaved stereo float32, 44100 Гц).

## Сборка

- Android arm64: `tools/build_spotify.sh` -> `android/app/src/main/jniLibs/arm64-v8a/libsfspotify.so`.
  Нет `cargo` или NDK: скрипт пишет сообщение и выходит с кодом 0. Сборка идёт с `--locked`.
- Хост: `cargo build`, `cargo clippy`, проверка без JNI: `cargo run --example smoke -- 10`.

`Cargo.lock` держим в репозитории: `vergen` в `librespot-core` ломается на 9.1.0, закреплён 9.0.6.

## Устройство

- `src/core.rs`: приёмник без JNI (сессия, Spirc, плеер, zeroconf, повтор подключения, FLUSH).
- `src/jni_glue.rs`: JNI, логгер `log` -> `onLog`.
- `vendor/librespot-discovery`: копия с патчем (zeroconf без mDNS-ответчика, порт HTTP-сервера).
- TLS: rustls + webpki-roots (ring), без OpenSSL. Вывода звука librespot нет, sink свой.

## mDNS

- Android: `DISCOVERY_READY` с `b = 0`. Библиотека поднимает только HTTP-сервер zeroconf на порту `a`;
  Kotlin объявляет `_spotify-connect._tcp` через NsdManager (TXT: `CPath=/`, `VERSION=1.0`, `Stack=SP`).
- Хост: `b = 1`, отвечает libmdns.

## События и порядок

- FLUSH: после seek, пользовательского переключения трека, остановки и ухода устройства. Не шлётся на
  естественном (gapless) переходе: перед ним плеер присылает `EndOfTrack`, и следующая загрузка не считается сбросом.
  Не шлётся на паузе.
- Порядок: FLUSH уходит до первого `onPcm` новой позиции. Если на момент FLUSH поток плеера внутри `onPcm`,
  этот вызов несёт старый звук: его надо отбросить после возврата.
- `onPcm` вызывает один поток плеера librespot. Пока он заблокирован, команды Connect (пауза, seek, загрузка)
  выполняются с задержкой: блокировать дольше ~0,5 с нельзя.
- События могут прийти раньше, чем вернулся `start`.

## Ограничения

- Реальный вход и воспроизведение требуют аккаунта Spotify Premium и в этой сборке не проверялись.
- `stop` ждёт до 8 с, затем отсоединяет зависшие потоки.
