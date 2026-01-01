package app.aaps.plugins.aps.openAPSFCL.vnext.learning

data class LearningParameterSpec(
    val key: LearningParameter,
    val minMultiplier: Double,   // Fase 3: clamp rond baseline
    val maxMultiplier: Double,
    val nightAllowed: Boolean = true
)

object LearningParameterSpecs {
    val specs = mapOf(
        LearningParameter.K_DELTA to LearningParameterSpec(LearningParameter.K_DELTA, 0.85, 1.15, nightAllowed = true),
        LearningParameter.K_SLOPE to LearningParameterSpec(LearningParameter.K_SLOPE, 0.80, 1.20, nightAllowed = true),
        LearningParameter.K_ACCEL to LearningParameterSpec(LearningParameter.K_ACCEL, 0.80, 1.20, nightAllowed = true),
        LearningParameter.PEAK_MOMENTUM_GAIN to LearningParameterSpec(LearningParameter.PEAK_MOMENTUM_GAIN, 0.75, 1.30, nightAllowed = true),
        LearningParameter.STAGE1_MIN to LearningParameterSpec(LearningParameter.STAGE1_MIN, 0.85, 1.15, nightAllowed = true),
        LearningParameter.STAGE2_MIN to LearningParameterSpec(LearningParameter.STAGE2_MIN, 0.85, 1.15, nightAllowed = true),
    )
}
