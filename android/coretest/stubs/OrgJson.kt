// Заглушка org.json для JVM-симуляции (на Android это системная библиотека; в unit-тестах её нет без Robolectric).
// Реализует только методы, которые использует core/SpatialSettings.kt.
package org.json

class JSONObject {
    private val values = LinkedHashMap<String, Any?>()

    fun put(name: String, value: Any?): JSONObject { values[name] = value; return this }
    fun optJSONObject(name: String): JSONObject? = values[name] as? JSONObject
    // Как настоящий org.json: число в строке тоже читается, иначе симуляция могла бы подтвердить нереальное поведение.
    fun optDouble(name: String, fallback: Double): Double = when (val v = values[name]) {
        is Number -> v.toDouble()
        is String -> v.toDoubleOrNull() ?: fallback
        else -> fallback
    }
    fun optBoolean(name: String, fallback: Boolean = false): Boolean = values[name] as? Boolean ?: fallback
    fun optString(name: String, fallback: String = ""): String = values[name] as? String ?: fallback
}

class JSONArray
