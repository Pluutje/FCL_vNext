package app.aaps.plugins.aps.openAPSFCL.vnext.learning

import org.joda.time.DateTime

data class LearningEpisodeSnapshot(
    val startTime: DateTime,
    val endTime: DateTime,
    val isNight: Boolean,

    // context
    val startBg: Double,
    val targetBg: Double,

    // dynamics
    val maxSlope: Double,
    val maxAccel: Double,
    val predictedPeak: Double,
    val peakBand: Int,

    // dosing
    val totalDelivered: Double,
    val earlyStageMax: Int,

    // safety
    val rescueTriggered: Boolean,

    // label
    val outcome: FCLvNextLearningEpisodeManager.EpisodeOutcome
)
