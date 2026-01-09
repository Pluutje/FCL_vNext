package app.aaps.plugins.aps.openAPSFCL.vnext.learning

/**
 * Parameters that may be adjusted by the learning system.
 *
 * IMPORTANT:
 * - Names are part of the persistence contract
 * - Do NOT rename existing entries once released
 * - Only append new parameters at the end
 */
enum class LearningParameter {

    // ─────────────────────────────────────────────
    // Core dynamics (signal weighting)
    // ─────────────────────────────────────────────
    K_DELTA,          // weight of absolute BG deviation
    K_SLOPE,          // weight of BG slope
    K_ACCEL,          // weight of BG acceleration

    // ─────────────────────────────────────────────
    // Commit shaping & dose modulation
    // ─────────────────────────────────────────────
    COMMIT_IOB_POWER, // curvature of IOB damping during commits
    MIN_COMMIT_DOSE,  // minimal effective commit dose

    UNCERTAIN_MIN_FRACTION,
    UNCERTAIN_MAX_FRACTION,
    CONFIRM_MIN_FRACTION,
    CONFIRM_MAX_FRACTION,

    // ─────────────────────────────────────────────
    // Absorption / peak suppression
    // ─────────────────────────────────────────────
    ABSORPTION_DOSE_FACTOR,   // dose reduction during absorption window
    PRE_PEAK_BUNDLE_FACTOR,   // fraction of maxSMB allowed pre-peak

    // ─────────────────────────────────────────────
    // Peak prediction & momentum shaping
    // ─────────────────────────────────────────────
    PEAK_MOMENTUM_GAIN,
    PEAK_RISE_GAIN;

    /**
     * Human-readable name for UI / debug output
     */
    fun displayName(): String =
        name
            .lowercase()
            .replace('_', ' ')

}
