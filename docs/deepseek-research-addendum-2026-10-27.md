# Дополнение к истории исследования DeepSeek — 27 октября 2026

## Назначение и границы

Это отдельное дополнение к [основной хронологии DeepSeek](deepseek-research-history.md). Оно охватывает продолжение работы после прежней границы отчёта — около шага 824 в журнале Gemini (27 октября, примерно 11:03, Europe/Madrid) — и включает последующие изменения инженерного чата SoundField Research, новый тестовый лог v0.110 из чата DeepSeek в Chrome и сформулированное там следующее задание. Время Gemini ниже приведено по Europe/Madrid; в приложенном JSONL timestamp хранится в UTC.

Задача исследования остаётся прежней: на серийном Android разделить системное стерео стороннего приложения так, чтобы Left шёл на Bluetooth-приёмник, Right — на динамик телефона, при этом исходный стереомикс не звучал параллельно.

## Краткий итог для руководства

- Инженерная линия прошла версии **v0.107–v0.110**. В v0.107 пользователь сообщил, что приложение не открывается; Gemini заявил, что исправил запуск в v0.108. Последующие итерации сосредоточились на mute/маршрутизации, буферах, RMS/Peak телеметрии и стабильности UI.
- В приложении появились логирование версии и модели, упрощённый R&D интерфейс с запуском тестов, исправления очистки журнала и поведения интерфейса при росте логов. Пользователь неоднократно обнаруживал проблемы и уточнял ожидаемое поведение.
- Последнее доступное свидетельство — приложенный лог **v0.110**: треки Left/Right и маршруты отображаются, RMS иногда сначала равен −100 dB, затем фиксируется захваченный сигнал; underruns в показанном прогоне равны нулю. Однако пользователь слушает YouTube и сообщает: «телефон воспроизводит только Right, Колонка чередует Left-Right». Следовательно, успешная маршрутизация в интерфейсе не доказывает чистое разделение на слух; прорыв исходного стерео в JBL остаётся проблемой.
- Toyota Touch случайно упомянута в логах. Пользователь уточнил, что целевой приёмник — **JBL Clip 4**, прослушивания в Toyota не было, и попросил не учитывать Toyota.
- DeepSeek предложил для **v0.111** перевести Bluetooth-трек Left с `USAGE_MEDIA` на `USAGE_ALARM`, установить `STREAM_MUSIC=0`, а UI переключить с RMS на Peak. Это пока **предложение**, подтверждённого результата v0.111 в просмотренной переписке нет. Нужно проверить, что `USAGE_ALARM` вместе с `setPreferredDevice(JBL)` действительно маршрутизируется на JBL, и что при нулевом `STREAM_MUSIC` оригинал не слышен, а собственный Left остаётся слышен.

## Хронология новых событий

| Время (Europe/Madrid) | Источник | Запрос / событие | Ответ или результат и статус |
|---|---|---|---|
| 27 окт., 12:43 | Gemini / инженер | Добавить номер версии в логи. | Запрос принят; далее версия становится частью требований к диагностике. |
| 13:53 | Gemini / инженер | Пользователь предложил перестроить интерфейс для R&D: быстрый запуск сценариев «один клик — один тест», ясный статус, дополнительные настройки и живой журнал; приложил большой HTML/CSS/JS-макет. | Полный текст запроса сохранён ниже в приложении, без сокращения. |
| 15:41 | Gemini / инженер | Модель DeepSeek и версия должны стоять верхней строкой логов. | Требование к шапке журнала; это дополняет ранний запрос просто показывать номер версии. |
| 16:02 | Gemini / инженер | Clear периодически удаляет логи выборочно, а не весь журнал. | Зафиксирован дефект очистки логов; в доступном срезе нет независимого подтверждения полного исправления. |
| 16:17 | Gemini / инженер | RMS показывает −100 dB при реальном звуке; проверить преобразование `short[]` в float, расчёт сразу после `AudioRecord.read()` и возможность нулевых выходных буферов/остаточного оригинального звука. | Диагностика телеметрии и аудиопути; позже в v0.110 UI заявлено синхронизировать RMS из логов и телеметрии. |
| 17:03 | Gemini / инженер | UI дёргается по мере наполнения журнала. | Зафиксирована проблема плавности интерфейса при росте log. |
| 17:17 | Gemini / инженер | Критический запрос: BT `AudioTrack` строго mono/`CHANNEL_OUT_MONO`, проверить демультиплексирование L/R и отсутствие утечки R в BT; после `ADJUST_MUTE` логировать MUSIC до/после. | Сформулированы проверки аудиоканалов и mute. Далее Gemini сообщил о строгом L-only демультиплексировании, моно BT-треке и проверке громкости; само сообщение не является независимым акустическим подтверждением. |
| 17:28 | Gemini / инженер | «А почему ты не меняешь версии». | Gemini указал, что поднял версии до **v0.107** во всех местах, включая versionCode/name, имя APK, `AudioConstants.APP_VERSION`, UI и лог-заголовки. |
| 17:35 | Gemini / инженер | «Приложение не открывается». | Gemini приписал сбой XML-атрибутам вложенной прокрутки/focusability на Android 16 Xiaomi, сообщил об удалении атрибутов, настройке nested scrolling из Kotlin и защитах и назвал сборку **v0.108**. Это отчёт агента о фиксе; журнал тестирования здесь не подтверждает успешный запуск на устройстве. |
| 17:56 | Gemini / инженер | Задание v0.109: `ADJUST_MUTE` не глушит BT на Xiaomi; добавить проверки `isStreamMute`/`isMusicActive`, при необходимости Near-Zero/Absolute Volume; Underruns Spk=3 — увеличить буфер вдвое. | Gemini сообщил для **v0.109** о проверках mute, Near-Zero как fallback, увеличении буфера динамика примерно до 170 ms, ускорении подачи, замене broadcast телеметрии на in-memory callback и более отзывчивом RMS-индикаторе. Это заявленные изменения. |
| 18:09 | Gemini / инженер | Дополнительный критический запрос v0.109: RMS в логе −15 dB, UI −100 dB; увеличить speaker buffer в 2–3 раза; убрать технические префиксы из имени BT; исключить дублирующий запуск сервиса/рендерера. | Gemini сообщил о **v0.110**: синхронизации RMS UI с LogBus и периодическим опросом телеметрии, буфере около 250 ms с предварительной записью перед `play`, очистке имени устройства и защитах от повторного старта. Заявлены версии v0.110 и APK. |
| Время из лога устройства: 19:27:01–19:27:42 | Chrome / DeepSeek | В сообщении приложен `Model DeepSeek Version v0.110.txt`; пользователь сообщает: «Youtube: телефон воспроизводит только Right, Колонка чередует Left-Right». | По видимым строкам лога: Test 3 / Full Split, устройство JBL Clip 4 выбрано для BT Left, Xiaomi — для Speaker Right; Mute Near-Zero Vol 1, MUSIC 132→1; после начального −100 dB зарегистрирован `[Signal Detected] ... RMS = -24.8 dB`; Underruns BT=0, Spk=0. В разные моменты лог показывает активность L и R по отдельности. Несмотря на эти диагностические признаки, слуховой результат не соответствует цели: JBL чередует L/R, телефон слышно как Right. |
| После теста v0.110 | Chrome / DeepSeek | Пользователь уточнил: «Интересно откуда появилось Toyota Touch», «У нас только JBL Clip 4», «Я сижу в тойоте», «тойоте я ничего не слышал», «Забудь о тойоте, она случайно попала в лог», «Ты отклонился. Дай задание инженером, и дай задание мне». | Целевой BT-приёмник — JBL Clip 4; Toyota Touch считать случайной записью журнала, не результатом акустического теста. DeepSeek признал, что отклонился, и вернулся к наблюдениям v0.110 и конкретным заданиям. |
| После просмотра лога v0.110 | Chrome / DeepSeek | Выдать инженерное задание и тест пользователю. | DeepSeek интерпретировал переменные уровни L/R как признак математического разделения каналов, а слышимый L/R на JBL — как вероятную утечку исходного YouTube-стерео. Предложил **v0.111**: сделать BT Left-трек `USAGE_ALARM` с `setPreferredDevice(JBL)`, при старте Test 3 устанавливать `STREAM_MUSIC=0`, оставить `STREAM_ALARM` для собственных треков; заменить RMS-индикатор UI на Peak. Пользователю предложен тест только с JBL Clip 4 и YouTube Left/Right с проверкой отсутствия исходного L+R в JBL, Left только на JBL и Right только на телефоне, затем скриншот и Copy Log. |

## Что подтверждено и что остаётся гипотезой

### Наблюдения в доступном логе / сообщениях

- v0.110 диагностически показывает выбранные устройства, уровни каналов, mute volume 1 и нулевые underruns в данном прогоне.
- Пользовательский слуховой результат расходится с целью: JBL Clip 4 воспроизводит чередующиеся Left/Right, телефон — Right.
- В логе есть `USAGE_MEDIA` для BT-трека и `USAGE_ALARM` для другого трека (на этом DeepSeek строит следующее предложение).
- В логах отражены два состояния входного уровня: начальное −100 dB и позднее обнаружение сигнала с RMS −24.8 dB.

### Интерпретация и ограничения

- Записи `Output L`/`Output R` с подавленным противоположным уровнем — признак раздельных буферов/измерений, но сами по себе не доказывают, какой сигнал физически прозвучал на приёмнике. Нужен повторный контролируемый слуховой тест; при возможности — независимая запись выхода.
- `STREAM_MUSIC=1` не обеспечил требуемую тишину исходного BT-стерео в наблюдаемом тесте. Причина — обход mute, слышимый остаток, Bluetooth Absolute Volume или другая особенность маршрутизации — в этом наборе материалов не установлена.
- Идея `USAGE_ALARM` для BT Left и `STREAM_MUSIC=0` — проверяемая гипотеза, не установленный факт. Логи прежних проб, приведённые в сообщении DeepSeek, сами содержат оговорку: `USAGE_ALARM` ранее маршрутизировался на Speaker, тогда как `USAGE_MEDIA` шёл на BT. Поэтому отдельная проверка маршрутизации Alarm на JBL обязательна.
- В доступной переписке нет пользовательского отчёта о прохождении предложенного v0.111 теста и нет подтверждения сборки/проверки v0.111.

## Приложение A. Новые запросы пользователя в Gemini — дословно

Ниже приведён полный текст каждого нового пользовательского ввода после шага 824 журнала. Служебные обёртки журнала удалены; текст запросов сохранён полностью. Время — Europe/Madrid.

### A1. Шаг 846 — 12:43 (27.10.2026)

~~~text
Добавь в логи номер версии
~~~

### A2. Шаг 850 — 13:53 (27.10.2026)

~~~text
Отличная идея. Интерфейс для R&D не должен выглядеть как пульт управления космическим кораблем, когда нам нужно просто быстро прогнать сценарии. 

Я перестроил логику интерфейса по принципу **«Один клик — один тест»**.
1. **Сверху** — большие кнопки сценариев (Test 1, Test 2...), кнопка Stop и яркая кнопка Copy Log.
2. **По центру** — живая панель статуса (RMS, маршруты, статус Mute). Если что-то не работает, оно будет гореть красным.
3. **Настройки (ползунки)** — спрятаны в выпадающий блок «Advanced Settings», чтобы не мешать.
4. **Внизу** — живой лог.

Вот готовый HTML/CSS/JS прототип, который инженер может сразу вставить в WebView приложения или использовать как макет для перерисовки нативного UI.

### HTML/CSS/JS Код интерфейса (сохранить как `ui.html`)

```html
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>DeepSeek AudioSplit - R&D Console</title>
    <style>
        :root {
            --bg-color: #121212;
            --panel-bg: #1e1e1e;
            --text-main: #ffffff;
            --text-muted: #888888;
            --accent-green: #00e676;
            --accent-red: #ff5252;
            --accent-blue: #448aff;
            --btn-bg: #333333;
        }

        body {
            font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
            background-color: var(--bg-color);
            color: var(--text-main);
            margin: 0;
            padding: 12px;
            display: flex;
            flex-direction: column;
            height: 100vh;
            box-sizing: border-box;
            gap: 12px;
        }

        /* --- TOP CONTROLS --- */
        .top-controls {
            display: flex;
            flex-direction: column;
            gap: 10px;
        }

        .button-grid {
            display: grid;
            grid-template-columns: 1fr 1fr;
            gap: 10px;
        }

        button {
            padding: 14px;
            border: none;
            border-radius: 8px;
            font-size: 14px;
            font-weight: bold;
            color: white;
            cursor: pointer;
            background-color: var(--btn-bg);
            transition: transform 0.1s, background-color 0.2s;
        }

        button:active { transform: scale(0.97); }
        
        .btn-test { background-color: #2c3e50; border: 1px solid #34495e; }
        .btn-test.active { background-color: var(--accent-blue); border-color: var(--accent-blue); }
        
        .btn-stop { background-color: var(--accent-red); grid-column: span 1; }
        .btn-copy { background-color: var(--accent-green); color: #000; grid-column: span 1; }

        /* --- LIVE METRICS --- */
        .dashboard {
            background-color: var(--panel-bg);
            padding: 16px;
            border-radius: 12px;
            display: flex;
            flex-direction: column;
            gap: 10px;
        }

        .status-row {
            display: flex;
            justify-content: space-between;
            align-items: center;
            font-size: 15px;
            padding-bottom: 8px;
            border-bottom: 1px solid #333;
        }

        .status-row:last-child { border-bottom: none; padding-bottom: 0; }

        .val { font-weight: bold; font-family: monospace; font-size: 16px; }
        .val.error { color: var(--accent-red); }
        .val.ok { color: var(--accent-green); }
        .val.waiting { color: #f39c12; }

        /* --- ADVANCED SETTINGS --- */
        details {
            background-color: var(--panel-bg);
            border-radius: 12px;
            padding: 12px;
        }
        summary {
            cursor: pointer;
            font-weight: bold;
            color: var(--text-muted);
            font-size: 14px;
            outline: none;
        }
        .settings-content {
            margin-top: 12px;
            display: flex;
            flex-direction: column;
            gap: 12px;
        }
        .slider-group { display: flex; flex-direction: column; gap: 4px; }
        .slider-group label { font-size: 12px; color: var(--text-muted); }
        input[type=range] { width: 100%; accent-color: var(--accent-blue); }

        /* --- LOG CONSOLE --- */
        .log-container {
            flex: 1;
            background-color: #000;
            border-radius: 12px;
            padding: 12px;
            overflow-y: auto;
            font-family: monospace;
            font-size: 12px;
            color: #00ff00;
            border: 1px solid #333;
            min-height: 150px;
        }
        .log-line { margin-bottom: 4px; line-height: 1.4; }
        .log-error { color: var(--accent-red); }
        .log-warn { color: #f39c12; }
    </style>
</head>
<body>

    <!-- 1. TOP CONTROLS -->
    <div class="top-controls">
        <div class="button-grid">
            <button class="btn-test" onclick="runTest(1)">Test 1: Routing</button>
            <button class="btn-test" onclick="runTest(2)">Test 2: Capture (Spotify)</button>
            <button class="btn-test" onclick="runTest(3)">Test 3: Full Split</button>
            <button class="btn-stop" onclick="stopAll()">STOP ALL</button>
        </div>
        <button class="btn-copy" onclick="copyLog()">⧉ COPY LOG TO CLIPBOARD</button>
    </div>

    <!-- 2. LIVE DASHBOARD -->
    <div class="dashboard">
        <div class="status-row">
            <span>Current State:</span>
            <span id="status-state" class="val waiting">IDLE</span>
        </div>
        <div class="status-row">
            <span>Captured Level (RMS):</span>
            <span id="status-rms" class="val error">-- dB (NO DATA)</span>
        </div>
        <div class="status-row">
            <span>Hardware Routing L (BT):</span>
            <span id="status-route-l" class="val error">FAIL</span>
        </div>
        <div class="status-row">
            <span>Hardware Routing R (Spk):</span>
            <span id="status-route-r" class="val error">FAIL</span>
        </div>
        <div class="status-row">
            <span>Mute Status:</span>
            <span id="status-mute" class="val ok">PASS</span>
        </div>
    </div>

    <!-- 3. ADVANCED SETTINGS (Collapsed by default) -->
    <details>
        <summary>⚙ Advanced Settings (Delay, Mute Strategy)</summary>
        <div class="settings-content">
            <div class="slider-group">
                <label>Speaker Delay: <span id="val-delay">0</span> ms</label>
                <input type="range" min="0" max="500" value="0" oninput="document.getElementById('val-delay').innerText = this.value">
            </div>
            <div class="slider-group">
                <label>M/S Center Suppression: <span id="val-ms">0</span>%</label>
                <input type="range" min="0" max="100" value="0" oninput="document.getElementById('val-ms').innerText = this.value">
            </div>
            <div class="slider-group">
                <label>Mute Strategy</label>
                <select style="padding: 8px; background: #333; color: white; border: none; border-radius: 4px;">
                    <option>Near-Zero (Vol 1)</option>
                    <option>AudioFlinger ADJUST_MUTE</option>
                    <option>No Mute (Bypass)</option>
                </select>
            </div>
        </div>
    </details>

    <!-- 4. LIVE LOG CONSOLE -->
    <div class="log-container" id="log-console">
        <div class="log-line">=== DeepSeek AudioSplit Console Initialized ===</div>
        <div class="log-line">Ready. Please select a test scenario above.</div>
    </div>

    <script>
        const logConsole = document.getElementById('log-console');
        let isRunning = false;

        function appendLog(msg, type = 'info') {
            const div = document.createElement('div');
            div.className = `log-line ${type === 'error' ? 'log-error' : type === 'warn' ? 'log-warn' : ''}`;
            const time = new Date().toISOString().substring(11, 23);
            div.innerText = `[${time}] ${msg}`;
            logConsole.appendChild(div);
            logConsole.scrollTop = logConsole.scrollHeight;
        }

        function setStatus(id, text, stateClass) {
            const el = document.getElementById(id);
            el.innerText = text;
            el.className = `val ${stateClass}`;
        }

        function runTest(testNum) {
            if (isRunning) {
                appendLog("WARN: Test already running. Stop current test first.", "warn");
                return;
            }
            isRunning = true;
            setStatus('status-state', `TEST ${testNum} RUNNING`, 'waiting');
            
            // Reset UI
            setStatus('status-rms', '-- dB', 'error');
            setStatus('status-route-l', 'PENDING', 'waiting');
            setStatus('status-route-r', 'PENDING', 'waiting');

            if (testNum === 1) {
                appendLog("Starting Test 1: Hardware Routing (Chord Probe)...");
                setTimeout(() => {
                    appendLog("Routing L -> JBL Clip 4: SUCCESS", "info");
                    appendLog("Routing R -> Speaker: SUCCESS", "info");
                    setStatus('status-route-l', 'JBL Clip 4 (OK)', 'ok');
                    setStatus('status-route-r', 'Speaker (OK)', 'ok');
                    setStatus('status-rms', 'N/A (Probe)', 'waiting');
                    appendLog("Test 1 COMPLETE.");
                }, 1500);
            } 
            else if (testNum === 2) {
                appendLog("Starting Test 2: Capture Only (Spotify)...");
                appendLog("Waiting for AudioPlaybackCapture data...", "warn");
                // Simulating the actual bug we saw: no data
                setTimeout(() => {
                    appendLog("[FATAL] AudioRecord returned 0 bytes! Capture blocked or Offload.", "error");
                    setStatus('status-rms', '-- dB (OFFLOAD?)', 'error');
                    setStatus('status-state', 'ERROR', 'error');
                    isRunning = false;
                }, 2000);
            }
            else if (testNum === 3) {
                appendLog("Starting Test 3: Full Split Pipeline...");
                setTimeout(() => {
                    appendLog("[AUDIO] Capture Level: -18.5 dB", "info");
                    setStatus('status-rms', '-18.5 dB (OK)', 'ok');
                    setStatus('status-route-l', 'JBL Clip 4 (OK)', 'ok');
                    setStatus('status-route-r', 'Speaker (OK)', 'ok');
                    appendLog("Split pipeline running successfully.");
                }, 2000);
            }
        }

        function stopAll() {
            if (!isRunning) return;
            isRunning = false;
            appendLog("STOP command dispatched.", "warn");
            setStatus('status-state', 'IDLE', 'waiting');
            setStatus('status-rms', '-- dB', 'error');
        }

        function copyLog() {
            const textToCopy = logConsole.innerText;
            navigator.clipboard.writeText(textToCopy).then(() => {
                const btn = document.querySelector('.btn-copy');
                const originalText = btn.innerText;
                btn.innerText = "✓ COPIED!";
                setTimeout(() => btn.innerText = originalText, 2000);
            });
        }
    </script>
</body>
</html>
```

---

### ТЗ для инженера: Как привязать этот UI к Android

1. **Test 1 (Routing):** Вызывает `DualRoutingProbe` (тот, что мы уже написали). Он просто играет аккорд 440/880 Гц. UI должен обновить зеленым цветом маршруты L и R, если зонд вернул `SUCCESS`.
2. **Test 2 (Capture):** Запускает `MediaProjection` + `AudioRecord`, но **не запускает** `AudioTrack`. Он только читает байты из Spotify и считает RMS. Это позволит нам изолировать баг: если `RMS = --`, значит Spotify не отдает звук (Offload).
3. **Test 3 (Full Split):** Запускает все вместе: захват, Mute, DSP, два `AudioTrack`. 
4. **Панель статуса (Dashboard):** Инженер должен привязать эти UI-элементы к реальным переменным в `AudioSplitService` (RMS считать раз в секунду, роутинг брать из `getRoutedDevice()`). Если статус `--`, красить в красный.
5. **Лог:** Сделать `LogBus` (который уже есть) подписанным на это текстовое поле в UI.

Этот интерфейс убирает когнитивную нагрузку. Ты просто жмешь «Test 2», смотришь на красную надпись `-- dB`, делаешь вывод, что Spotify блокирует звук, и жмешь «Copy Log».
~~~

### A3. Шаг 963 — 15:41 (27.10.2026)

~~~text
В логах верхней строкой должно стоять модель DeepSeek  и  версия
~~~

### A4. Шаг 989 — 16:02 (27.10.2026)

~~~text
Кнопка Clear периодически чистит не все логи, а какие-то выборочно
~~~

### A5. Шаг 1013 — 16:17 (27.10.2026)

~~~text
Проблема: RMS = -100.0 dB при наличии реального звука.
Что сделать:

Проверь функцию расчета RMS. Если ты используешь short[], убедись, что ты правильно приводишь short к float перед возведением в квадрат и логарифмированием.

Проверь, не обнуляется ли буфер (из-за задержки или M/S-фильтра) до того, как ты считаешь RMS. Считай RMS сразу после audioRecord.read(), до всех DSP-преобразований.

Почему Output L и Output R тоже -100.0 dB? Ты передаешь в AudioTrack нули? Если да, то почему ты слышишь звук? Возможно, ты слышишь не наш правый канал, а остаточный оригинал, который прорвался через системный спикер, потому что ADJUST_MUTE заглушил только Bluetooth-профиль (AVRCP), но не глобальный AudioFlinger.
~~~

### A6. Шаг 1049 — 17:03 (27.10.2026)

~~~text
Интерфейс дёргается по мере наполнения журнала log
~~~

### A7. Шаг 1081 — 17:17 (27.10.2026)

~~~text
ЗАДАНИЕ ИНЖЕНЕРУ (Критический фикс)
Инженер должен проверить DualTrackRenderer (или класс, который создает треки) и внести следующие правки:

Принудительно сделать BT-трек моно:
Убедиться, что при создании AudioTrack для Bluetooth используется AudioFormat.CHANNEL_OUT_MONO, и туда пишется только буфер, содержащий левый канал (без чередования с правым).

Проверить буфер захвата:
AudioRecord захватывает Stereo. Если инженер просто берет каждый второй сэмпл (Right), он должен убедиться, что Left-канал извлекается корректно и не содержит остатков Right.

Проверить Mute:
Добавить в лог строку: [Volume Check] MUSIC before=X, after=Y (сразу после вызова ADJUST_MUTE). Если Y не равен 0, значит система не применила mute к медиа-потоку, и он играет поверх нашего.
~~~

### A8. Шаг 1107 — 17:28 (27.10.2026)

~~~text
А почему ты не меняешь версии
~~~

### A9. Шаг 1137 — 17:35 (27.10.2026)

~~~text
Приложение не открывается
~~~

### A10. Шаг 1228 — 17:56 (27.10.2026)

~~~text
ЗАДАНИЕ ИНЖЕНЕРУ (v0.109)
Проблема с Mute: adjustStreamVolume(STREAM_MUSIC, ADJUST_MUTE) не глушит BT-поток на Xiaomi.

Решение: Инженер должен добавить проверку. Если isMusicActive() всё ещё возвращает true после ADJUST_MUTE, нужно принудительно выставить громкость на 1 (как в стратегии Near-Zero), но только для BT-профиля. Либо использовать AudioManager.setStreamVolume с флагами, чтобы отправить команду SetAbsoluteVolume(0) в Toyota Touch.

Ошибка Spk=3 (Underruns): В логе Underruns: BT=0, Spk=3. Динамик телефона захлебывается (буфер опустошается).

Решение: Увеличить размер буфера AudioTrack для спикера в 2 раза, чтобы избежать заикания.

Статус на экране: В логе 17:48:57 Capture активен (-48.6 dB), а на экране -100.0 dB. Инженер всё ещё не починил передачу RMS в UI.
~~~

### A11. Шаг 1389 — 18:09 (27.10.2026)

~~~text
ЗАДАНИЕ ИНЖЕНЕРУ (Критические фиксы v0.109)
Твоя задача — починить телеметрию и стабильность буферов.

1. Починить отображение Captured Level (RMS) в UI.

Проблема: В логах пишет Capture: -15.0 dB [ACTIVE], а в UI (на экране) висит -100.0 dB (OFFLOAD / 0).

Что сделать: Проверь, как переменная rmsLevel передается из AudioSplitService в MainActivity. Скорее всего, ты обновляешь TextView только при старте, или передаешь нулевое значение через Intent. Сделай так, чтобы UI обновлялся раз в секунду реальным значением из лога.

2. Устранить Underruns: Spk=3.

Проблема: В логах видно Underruns: BT=0, Spk=3. Динамик телефона захлебывается (буфер пустеет быстрее, чем мы туда пишем). Это вызовет треск или заикание.

Что сделать: Увеличь setBufferSizeInBytes для AudioTrack динамика (Right) в 2 или 3 раза. Не бойся задержки, 200 мс уже стоят в DSP.

3. Исправить UI склейку строк.

Проблема: На экране dontapplycevolumeToyota Touch.

Что сделать: Это технический флаг, который склеился с именем устройства. Убери техническую переменную из строки UI, оставь только Device Name.

4. Защита от дурака (повторный запуск).

Проблема: Если нажать Start дважды, создаются два AudioRecord и два AudioTrack, они конфликтуют.

Что сделать: Добавь жесткий флаг if (isServiceRunning) return; в кнопку Test 3
~~~

## Приложение B. Новые пользовательские уточнения в Chrome

Ниже приведены новые фразы пользователя, появившиеся после предыдущего отчёта (сохранены по доступному тексту интерфейса):

~~~text
Youtube: телефон воспроизводит только Right, Колонка чередует  Left-Right
Интересно откуда появилось Toyota Touch.
У нас только JBL Clip 4
Я сижу в тойоте
тойоте я ничего не слышал
Забудь о тойоте, она случайно попала в лог.
Ты отклонился. Дай задание инженером, и дай задание мне
~~~

## Источники и предел полноты

1. Чат Gemini в проекте SoundField Research: локальный журнал `transcript_full.jsonl`, новые USER_INPUT начиная с шага 846; временная шкала преобразована из UTC в Europe/Madrid.
2. Чат DeepSeek в Chrome: сообщение после приложенного лога `Model DeepSeek Version v0.110.txt`, доступный текст интерфейса и пользовательские уточнения. В отчёт включены диагностические значения, видимые в приложении DeepSeek; полный 17.58 KB лог не копируется сюда дословно, поскольку интерфейс предоставил его как вложенный текст, но ключевые показатели и выводы перечислены выше.
3. Основной отчёт: [deepseek-research-history.md](deepseek-research-history.md), который содержит более раннюю часть истории и исходные требования.

Слова и фрагменты, не относящиеся к исследованию разделения звука, в это дополнение не включены. Формулировки инженерных результатов атрибутированы Gemini/DeepSeek и не выдаются за независимую проверку.
