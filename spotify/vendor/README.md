librespot-discovery 0.8.0 (MIT), копия из crates.io с патчем в src/lib.rs:
`Builder::without_zeroconf()` не запускает mDNS-ответчик (только HTTP-сервер), `Discovery::port()` отдаёт порт.
Причина: в оригинале `DnsSdHandle` нельзя создать снаружи, подключаемый бэкенд невозможен.
