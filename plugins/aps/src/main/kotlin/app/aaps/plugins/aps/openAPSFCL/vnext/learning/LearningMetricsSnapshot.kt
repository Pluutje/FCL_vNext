package app.aaps.plugins.aps.openAPSFCL.vnext.learning

data class LearningMetricsSnapshot(
    val isNight: Boolean,
    val tir24: Double,
    val tar24: Double,
    val tbr24: Double,
    val tbt24: Double,
    val tir7d: Double,
    val tar7d: Double,
    val tbr7d: Double,
    val tbt7d: Double,
    val dataQualityOk: Boolean,
    val timestampMillis: Long
)
