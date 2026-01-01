package app.aaps.plugins.aps.openAPSFCL.vnext.learning

data class LearningAdvice(
    val parameter: LearningParameter,
    val direction: Int,
    val confidence: Double,
    val evidenceCount: Int,
    val isNight: Boolean
)
