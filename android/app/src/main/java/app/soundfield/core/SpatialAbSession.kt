package app.soundfield.core

import java.util.UUID
import kotlin.random.Random

enum class SpatialAbComparison(val wire: String, val first: SpatialLevel, val second: SpatialLevel) {
    OFF_SOFT("off_soft", SpatialLevel.OFF, SpatialLevel.SOFT),
    OFF_STRONG("off_strong", SpatialLevel.OFF, SpatialLevel.STRONG),
    SOFT_STRONG("soft_strong", SpatialLevel.SOFT, SpatialLevel.STRONG),
}

data class SpatialAbTrialPlan(
    val index: Int,
    val control: Boolean,
    val variantA: SpatialLevel,
    val variantB: SpatialLevel,
)

data class SpatialAbAnswer(
    val detectsDifference: Boolean? = null,
    val width: String? = null,
    val center: String? = null,
    val naturalness: String? = null,
) {
    val complete: Boolean get() = detectsDifference != null && width in CHOICES && center in CHOICES && naturalness in CHOICES

    companion object { val CHOICES = setOf("a", "b", "same") }
}

data class SpatialAbSession(
    val id: String,
    val comparison: SpatialAbComparison,
    val originalSettings: SpatialSettings,
    val anchorMs: Long,
    val resumeAfter: Boolean,
    val fixedCompensationDb: Double,
    val trials: List<SpatialAbTrialPlan>,
    val answers: List<SpatialAbAnswer>,
    val currentTrial: Int = 0,
) {
    val complete: Boolean get() = answers.size == TRIAL_COUNT && answers.all { it.complete }
    val correctDetections: Int get() = trials.zip(answers).count { (trial, answer) ->
        answer.detectsDifference == !trial.control
    }
    val pValue: Double get() = binomialTail(TRIAL_COUNT, correctDetections)

    fun answerCurrent(answer: SpatialAbAnswer): SpatialAbSession {
        if (!answer.complete || currentTrial !in answers.indices) return this
        return copy(answers = answers.toMutableList().also { it[currentTrial] = answer },
            currentTrial = (currentTrial + 1).coerceAtMost(TRIAL_COUNT - 1))
    }

    companion object {
        const val TRIAL_COUNT = 8
        private const val ACTIVE_COUNT = 6
        private const val CONTROL_COUNT = 2

        fun create(
            comparison: SpatialAbComparison,
            originalSettings: SpatialSettings,
            anchorMs: Long,
            resumeAfter: Boolean,
            fixedCompensationDb: Double,
            seed: Int = Random.nextInt(),
        ): SpatialAbSession {
            val random = Random(seed)
            val variants = mutableListOf<Pair<Boolean, Pair<SpatialLevel, SpatialLevel>>>()
            repeat(ACTIVE_COUNT) { variants += false to (comparison.first to comparison.second) }
            repeat(CONTROL_COUNT) {
                val same = if (random.nextBoolean()) comparison.first else comparison.second
                variants += true to (same to same)
            }
            variants.shuffle(random)
            val plans = variants.mapIndexed { index, (control, pair) ->
                val swap = !control && random.nextBoolean()
                SpatialAbTrialPlan(index + 1, control,
                    if (swap) pair.second else pair.first,
                    if (swap) pair.first else pair.second)
            }
            return SpatialAbSession(UUID.randomUUID().toString(), comparison, originalSettings,
                anchorMs.coerceAtLeast(0L), resumeAfter, fixedCompensationDb.coerceIn(-3.0, 3.0), plans,
                List(TRIAL_COUNT) { SpatialAbAnswer() })
        }

        private fun binomialTail(n: Int, successes: Int): Double {
            var numerator = 0.0
            for (k in successes..n) {
                var choose = 1.0
                for (i in 1..k) choose *= (n - i + 1).toDouble() / i
                numerator += choose
            }
            return numerator / (1 shl n)
        }
    }
}
