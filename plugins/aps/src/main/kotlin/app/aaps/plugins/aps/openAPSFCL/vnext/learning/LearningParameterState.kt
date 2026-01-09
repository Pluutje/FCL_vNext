package app.aaps.plugins.aps.openAPSFCL.vnext.learning

/**
 * Persistent state for one learnable parameter.
 *
 * value = learned override (null = use config default)
 */
data class LearningParameterState(
    val value: Double?,
    val evidenceCount: Int,
    val lastUpdatedMillis: Long
)
