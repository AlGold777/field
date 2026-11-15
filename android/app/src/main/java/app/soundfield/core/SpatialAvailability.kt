package app.soundfield.core

/**
 * Когда блок «Сцена» работает и что показывать, если не работает.
 * Пара: нужны оба телефона с `spatialV1` и встроенный динамик на этом телефоне. Solo: телефон со стереодинамиками на
 * встроенном динамике. Ориентация экрана не проверяется: приложение закреплено в портретной ориентации, и условие
 * «альбомная» не выполнялось никогда, поэтому в Solo ползунки не появлялись вообще.
 */
object SpatialAvailability {
    data class Result(val available: Boolean, val hint: String)

    const val HINT_PEER_VERSION = "Сцена недоступна: обновите SoundField на втором телефоне"
    const val HINT_SPEAKER = "Сцена работает на встроенном динамике: отключите наушники и Bluetooth на этом телефоне"

    /** Подсказка есть только у причин, которые пользователь может устранить сам. Телефоны без стерео в Solo блок не видят. */
    fun evaluate(
        solo: Boolean, music: Boolean, paired: Boolean, peerSupportsSpatial: Boolean,
        builtInSpeaker: Boolean, stereoSpeakers: Boolean,
    ): Result = when {
        !music -> Result(false, "")
        solo -> Result(builtInSpeaker && stereoSpeakers, "")
        !paired -> Result(false, "")
        !peerSupportsSpatial -> Result(false, HINT_PEER_VERSION)
        !builtInSpeaker -> Result(false, HINT_SPEAKER)
        else -> Result(true, "")
    }
}
