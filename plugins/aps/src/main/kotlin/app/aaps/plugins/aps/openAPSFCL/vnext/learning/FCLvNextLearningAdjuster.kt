package app.aaps.plugins.aps.openAPSFCL.vnext.learning

/**
 * Applies learning-based multipliers to baseline config values.
 *
 * - Never mutates config
 * - Never exceeds spec bounds
 * - Fully deterministic
 */
class FCLvNextLearningAdjuster(
    private val advisor: FCLvNextLearningAdvisor
) {

    /**
     * Returns multiplier for a parameter (default = 1.0)
     */
    fun multiplier(
        parameter: LearningParameter,
        isNight: Boolean
    ): Double {

        val advice = advisor
            .getAdvice(isNight)
            .firstOrNull { it.parameter == parameter }
            ?: return 1.0

        val spec = LearningParameterSpecs.specs[parameter]
            ?: return 1.0

        // richting: +1 = omhoog, -1 = omlaag
        val rawMultiplier =
            1.0 + (advice.direction * advice.confidence * 0.15)

        // clamp rond baseline
        val clamped =
            rawMultiplier
                .coerceIn(
                    spec.minMultiplier,
                    spec.maxMultiplier
                )

        return clamped
    }

}
