package app.aaps.plugins.aps.openAPSFCL.vnext.learning

enum class FCLvNextProfile {
    STRICT,
    BALANCED,
    AGGRESSIVE
}

data class FCLvNextProfileTuning(
    // Early stage-1 sneller/langzamer
    val earlyStage1ThresholdMul: Double,   // <1.0 = sneller, >1.0 = trager

    // Extra confidence bij hoge predictedPeak (maakt early sneller, geen hogere maxSMB)
    val earlyPeakEscalationBonus: Double,  // 0..~0.25

    // Treat as effective meal bij duidelijke fast-carb dynamiek
    val fastCarbOverrideEnabled: Boolean,

    // Trend persistence strenger/losser (alleen timing)
    val trendConfirmCyclesDelta: Int       // -1 = sneller, +1 = strenger
)

object FCLvNextProfiles {
    fun tuning(profile: FCLvNextProfile): FCLvNextProfileTuning = when (profile) {
        FCLvNextProfile.STRICT -> FCLvNextProfileTuning(
            earlyStage1ThresholdMul = 1.15,
            earlyPeakEscalationBonus = 0.00,
            fastCarbOverrideEnabled = false,
            trendConfirmCyclesDelta = +1
        )
        FCLvNextProfile.BALANCED -> FCLvNextProfileTuning(
            earlyStage1ThresholdMul = 1.00,
            earlyPeakEscalationBonus = 0.10,
            fastCarbOverrideEnabled = false,
            trendConfirmCyclesDelta = 0
        )
        FCLvNextProfile.AGGRESSIVE -> FCLvNextProfileTuning(
            earlyStage1ThresholdMul = 0.75,
            earlyPeakEscalationBonus = 0.20,
            fastCarbOverrideEnabled = true,
            trendConfirmCyclesDelta = -1
        )
    }
}

data class FCLvNextProfileAdvice(
    val recommended: FCLvNextProfile,
    val confidence: Double,      // 0..1
    val reason: String,
    val evidenceCount: Int
)
