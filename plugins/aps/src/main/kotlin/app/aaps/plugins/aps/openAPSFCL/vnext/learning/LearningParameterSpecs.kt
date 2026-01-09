package app.aaps.plugins.aps.openAPSFCL.vnext.learning

data class LearningParameterSpec(
    val key: LearningParameter,
    val minMultiplier: Double,
    val maxMultiplier: Double,
    val nightAllowed: Boolean = true
)

object LearningParameterSpecs {

    val specs: Map<LearningParameter, LearningParameterSpec> = mapOf(

        // ── Core dynamics
        LearningParameter.K_DELTA to LearningParameterSpec(
            LearningParameter.K_DELTA, 0.85, 1.15, nightAllowed = true
        ),
        LearningParameter.K_SLOPE to LearningParameterSpec(
            LearningParameter.K_SLOPE, 0.80, 1.20, nightAllowed = true
        ),
        LearningParameter.K_ACCEL to LearningParameterSpec(
            LearningParameter.K_ACCEL, 0.80, 1.20, nightAllowed = true
        ),

        // ── Commit shaping
        LearningParameter.COMMIT_IOB_POWER to LearningParameterSpec(
            LearningParameter.COMMIT_IOB_POWER, 0.7, 1.4, nightAllowed = true
        ),
        LearningParameter.MIN_COMMIT_DOSE to LearningParameterSpec(
            LearningParameter.MIN_COMMIT_DOSE, 0.7, 1.3, nightAllowed = true
        ),

        LearningParameter.UNCERTAIN_MIN_FRACTION to LearningParameterSpec(
            LearningParameter.UNCERTAIN_MIN_FRACTION, 0.85, 1.15
        ),
        LearningParameter.UNCERTAIN_MAX_FRACTION to LearningParameterSpec(
            LearningParameter.UNCERTAIN_MAX_FRACTION, 0.85, 1.15
        ),
        LearningParameter.CONFIRM_MIN_FRACTION to LearningParameterSpec(
            LearningParameter.CONFIRM_MIN_FRACTION, 0.85, 1.15
        ),
        LearningParameter.CONFIRM_MAX_FRACTION to LearningParameterSpec(
            LearningParameter.CONFIRM_MAX_FRACTION, 0.85, 1.15
        ),

        // ── Absorption / peak suppression
        LearningParameter.ABSORPTION_DOSE_FACTOR to LearningParameterSpec(
            LearningParameter.ABSORPTION_DOSE_FACTOR, 0.5, 1.3, nightAllowed = true
        ),
        LearningParameter.PRE_PEAK_BUNDLE_FACTOR to LearningParameterSpec(
            LearningParameter.PRE_PEAK_BUNDLE_FACTOR, 0.7, 1.2, nightAllowed = true
        ),

        // ── Peak shaping
        LearningParameter.PEAK_MOMENTUM_GAIN to LearningParameterSpec(
            LearningParameter.PEAK_MOMENTUM_GAIN, 0.7, 1.4, nightAllowed = true
        ),
        LearningParameter.PEAK_RISE_GAIN to LearningParameterSpec(
            LearningParameter.PEAK_RISE_GAIN, 0.7, 1.3, nightAllowed = true
        )
    )
}
