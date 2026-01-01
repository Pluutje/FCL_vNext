package app.aaps.plugins.aps.openAPSFCL.vnext.learning

enum class LearningParameter {
    K_DELTA,
    K_SLOPE,
    K_ACCEL,
    PEAK_MOMENTUM_GAIN,
    STAGE1_MIN,
    STAGE2_MIN;

    fun isProfileParameter(): Boolean =
        name.startsWith("PROFILE_")

    fun displayName(): String =
        name
            .removePrefix("PROFILE_")
            .lowercase()
            .replace('_', ' ')

}

