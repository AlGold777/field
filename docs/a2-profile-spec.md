# A2: контракт профиля устройства (рабочая версия 0.1)

Единый источник: `profiles/devices.json`. Читают Python-инструмент (`tools/speaker-lab`), Kotlin, Swift и, при необходимости, ядро.
Поля измерений (fr_db, sens_db_spl_1m, max_level_dbfs, …) ТЗ §A2.2 добавляются инструментом после сеанса; рантайм их не читает.

## Рантайм-подмножество
- `profiles_version` — semver; `schema` — целое, пока 1.
- `classes.generic_phone_speaker` — обязателен: запасной профиль (ФВЧ 250 Гц, подъёмов нет).
- `models[]`: `key` (уникален, `[a-z0-9_]+`), `match[]` — строки `MANUFACTURER/MODEL/DEVICE` (Android, без учёта регистра) или `hw.machine` (iOS); `synthetic` — true для тестовых, в релизной сборке игнорируются;
  `preamp_db` ≤ 0; `f_low_hz` 60…600; `sections[]` — до 16 штук;
  `allpass_vs_partner{partner_key: [{f0_hz, q}]}` — до 4 секций allpass 2-го порядка на каждого партнёра.
- Секция: `type` ∈ `highpass | lowshelf | highshelf | peak`; `f0_hz` 20…20000; `q` 0.1…6; `gain_db` обязателен для peak/shelf, ограничен [−15, +6] (выше 8 кГц ≤ +3).
- Формулы биквадов — RBJ Audio EQ Cookbook; фаза min-phase, форма TDF-II.

## Правила
1. Неизвестная модель или наушники/Bluetooth → `generic_phone_speaker` (при наушниках коррекция выключена совсем).
2. Любое нарушение схемы → профиль отбрасывается целиком, загрузчик возвращает generic (не падает).
3. `synthetic: true` допустим только в отладочной сборке/тестах.
