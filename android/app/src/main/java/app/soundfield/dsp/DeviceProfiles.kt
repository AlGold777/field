package app.soundfield.dsp

/**
 * Загрузчик профилей устройств по docs/a2-profile-spec.md (рабочая версия 0.1).
 * Источник данных: profiles/devices.json (копируется в assets при сборке).
 *
 * JSON читается собственным парсером, а не org.json: на JVM unit-тестах android.jar
 * выбрасывает "Method not mocked", а в проекте нет testImplementation для org.json.
 */

data class Section(
    val type: String,
    val f0Hz: Double,
    val q: Double,
    val gainDb: Double?,
)

data class AllpassSection(val f0Hz: Double, val q: Double)

data class Profile(
    val key: String,
    val synthetic: Boolean,
    val match: List<String>,
    val preampDb: Double,
    val fLowHz: Double,
    val sections: List<Section>,
    val allpassVsPartner: Map<String, List<AllpassSection>>,
)

data class ProfileTable(
    val profilesVersion: String,
    val schema: Int,
    val generic: Profile,
    val models: List<Profile>,
) {
    /**
     * Поиск по строке `MANUFACTURER/MODEL/DEVICE` (без учёта регистра).
     * Неизвестная модель, синтетический профиль при includeSynthetic=false -> generic_phone_speaker.
     */
    fun lookup(manufacturer: String, model: String, device: String, includeSynthetic: Boolean = false): Profile {
        val id = "$manufacturer/$model/$device"
        return models.firstOrNull { p ->
            (includeSynthetic || !p.synthetic) && p.match.any { it.equals(id, ignoreCase = true) }
        } ?: generic
    }
}

object DeviceProfiles {
    const val GENERIC_KEY = "generic_phone_speaker"

    private val SEMVER_RE = Regex("""\d+\.\d+\.\d+""")
    private val KEY_RE = Regex("""[a-z0-9_]+""")
    private val SECTION_TYPES = setOf("highpass", "lowshelf", "highshelf", "peak")
    private const val MAX_SECTIONS = 16
    private const val MAX_ALLPASS = 4

    /** Возвращает null при любой ошибке схемы (битый JSON, значения вне диапазона и т.п.). */
    fun parse(json: String): ProfileTable? = try {
        parseOrThrow(json)
    } catch (e: Exception) {
        null
    }

    /** Секции allpass для пары с partnerKey; пусто, если пары нет в профиле. */
    fun allpassFor(profile: Profile, partnerKey: String): List<AllpassSection> =
        profile.allpassVsPartner[partnerKey].orEmpty()

    private fun parseOrThrow(json: String): ProfileTable {
        val root = JsonParser(json).parseDocument() as? JObj ?: invalid("корень не объект")
        val version = root.str("profiles_version")
        check(SEMVER_RE.matches(version)) { "profiles_version" }
        val schema = root.num("schema")
        check(schema == 1.0) { "schema" }
        val classes = root.obj("classes")
        val generic = parseProfile(GENERIC_KEY, classes.obj(GENERIC_KEY), emptyList())

        val models = root.arr("models").map { item ->
            val o = item as? JObj ?: invalid("модель не объект")
            val key = o.str("key")
            check(KEY_RE.matches(key)) { "key $key" }
            val match = o.arr("match").map { m ->
                val s = m as? String ?: invalid("match не строка")
                check(s.isNotBlank()) { "пустой match" }
                s
            }
            check(match.isNotEmpty()) { "match пуст" }
            parseProfile(key, o, match)
        }
        check(models.map { it.key }.toSet().size == models.size) { "дубликаты key" }
        return ProfileTable(version, schema.toInt(), generic, models)
    }

    private fun parseProfile(key: String, o: JObj, match: List<String>): Profile {
        val synthetic = o.bool("synthetic")
        val preamp = o.num("preamp_db")
        check(preamp <= 0.0) { "preamp_db $preamp" }
        val fLow = o.num("f_low_hz")
        check(fLow in 60.0..600.0) { "f_low_hz $fLow" }
        val sectionItems = o.arr("sections")
        check(sectionItems.size <= MAX_SECTIONS) { "секций больше $MAX_SECTIONS" }
        val sections = sectionItems.map { parseSection(it as? JObj ?: invalid("секция не объект")) }
        val allpass = o.obj("allpass_vs_partner").map.mapValues { (partner, value) ->
            check(partner.isNotEmpty()) { "пустой partner_key" }
            val list = (value as? JArr ?: invalid("allpass не массив")).items.map {
                parseAllpass(it as? JObj ?: invalid("allpass секция не объект"))
            }
            check(list.size <= MAX_ALLPASS) { "allpass больше $MAX_ALLPASS" }
            list
        }
        return Profile(key, synthetic, match, preamp, fLow, sections, allpass)
    }

    private fun parseSection(o: JObj): Section {
        val type = o.str("type")
        check(type in SECTION_TYPES) { "type $type" }
        val f0 = o.num("f0_hz")
        check(f0 in 20.0..20000.0) { "f0_hz $f0" }
        val q = o.num("q")
        check(q in 0.1..6.0) { "q $q" }
        // gain_db обязателен для peak и shelf; для highpass допустим, но если задан - проверяется.
        val gain = if (type == "highpass") o.optNum("gain_db") else o.num("gain_db")
        if (gain != null) {
            check(gain in -15.0..6.0) { "gain_db $gain" }
            if (f0 > 8000.0) check(gain <= 3.0) { "gain_db выше 8 кГц $gain" }
        }
        return Section(type, f0, q, gain)
    }

    private fun parseAllpass(o: JObj): AllpassSection {
        val f0 = o.num("f0_hz")
        check(f0 in 20.0..20000.0) { "allpass f0_hz $f0" }
        val q = o.num("q")
        check(q in 0.1..6.0) { "allpass q $q" }
        return AllpassSection(f0, q)
    }

    private fun check(condition: Boolean, message: () -> String) {
        if (!condition) invalid(message())
    }

    private fun invalid(message: String): Nothing = throw IllegalArgumentException(message)
}

// ---- минимальный JSON-парсер (объекты, массивы, строки, числа, true/false/null) ----

private object JNull
private class JObj(val map: Map<String, Any>) {
    fun field(key: String): Any = map[key] ?: throw IllegalArgumentException("нет поля $key")
    fun num(key: String): Double = (field(key) as? Double)?.takeIf { it.isFinite() }
        ?: throw IllegalArgumentException("число $key")
    fun optNum(key: String): Double? = if (map.containsKey(key)) num(key) else null
    fun str(key: String): String = field(key) as? String ?: throw IllegalArgumentException("строка $key")
    fun bool(key: String): Boolean = field(key) as? Boolean ?: throw IllegalArgumentException("bool $key")
    fun obj(key: String): JObj = field(key) as? JObj ?: throw IllegalArgumentException("объект $key")
    fun arr(key: String): List<Any> = (field(key) as? JArr)?.items ?: throw IllegalArgumentException("массив $key")
}
private class JArr(val items: List<Any>)

private class JsonParser(private val s: String) {
    private var pos = 0

    fun parseDocument(): Any {
        val v = value(0)
        ws()
        if (pos != s.length) throw IllegalArgumentException("лишние символы")
        return v
    }

    private fun value(depth: Int): Any {
        if (depth > MAX_DEPTH) throw IllegalArgumentException("слишком глубоко")
        ws()
        return when (peek()) {
            '{' -> obj(depth)
            '[' -> arr(depth)
            '"' -> str()
            't' -> literal("true", true)
            'f' -> literal("false", false)
            'n' -> literal("null", JNull)
            else -> num()
        }
    }

    private fun obj(depth: Int): JObj {
        expect('{')
        val map = LinkedHashMap<String, Any>()
        ws()
        if (peek() == '}') { pos++; return JObj(map) }
        while (true) {
            ws()
            if (peek() != '"') throw IllegalArgumentException("ожидался ключ")
            val key = str()
            ws()
            expect(':')
            if (map.containsKey(key)) throw IllegalArgumentException("дубликат ключа $key")
            map[key] = value(depth + 1)
            ws()
            if (next() == '}') return JObj(map)
            // иначе следующий символ обязан быть ',' - проверяется на следующей итерации через next() выше
            pos--
            expect(',')
        }
    }

    private fun arr(depth: Int): JArr {
        expect('[')
        val items = ArrayList<Any>()
        ws()
        if (peek() == ']') { pos++; return JArr(items) }
        while (true) {
            items.add(value(depth + 1))
            ws()
            if (next() == ']') return JArr(items)
            pos--
            expect(',')
        }
    }

    private fun str(): String {
        expect('"')
        val sb = StringBuilder()
        while (true) {
            val c = next()
            when {
                c == '"' -> return sb.toString()
                c == '\\' -> when (val e = next()) {
                    '"', '\\', '/' -> sb.append(e)
                    'b' -> sb.append('\b')
                    'f' -> sb.append('\u000C')
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'u' -> {
                        if (pos + 4 > s.length) throw IllegalArgumentException("\\u")
                        sb.append(s.substring(pos, pos + 4).toInt(16).toChar())
                        pos += 4
                    }
                    else -> throw IllegalArgumentException("escape $e")
                }
                c < ' ' -> throw IllegalArgumentException("управляющий символ в строке")
                else -> sb.append(c)
            }
        }
    }

    private fun num(): Double {
        val start = pos
        while (pos < s.length && (s[pos].isDigit() || s[pos] in "+-.eE")) pos++
        if (pos == start) throw IllegalArgumentException("ожидалось значение на позиции $pos")
        return s.substring(start, pos).toDouble()
    }

    private fun literal(word: String, value: Any): Any {
        if (!s.startsWith(word, pos)) throw IllegalArgumentException("ожидалось $word")
        pos += word.length
        return value
    }

    private fun ws() {
        while (pos < s.length && s[pos] in " \t\r\n") pos++
    }

    private fun peek(): Char = if (pos < s.length) s[pos] else throw IllegalArgumentException("конец текста")

    private fun next(): Char = peek().also { pos++ }

    private fun expect(c: Char) {
        if (next() != c) throw IllegalArgumentException("ожидался $c")
    }

    private companion object {
        const val MAX_DEPTH = 16
    }
}
