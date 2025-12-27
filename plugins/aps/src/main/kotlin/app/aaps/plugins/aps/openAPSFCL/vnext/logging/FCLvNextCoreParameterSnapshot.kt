package app.aaps.plugins.aps.openAPSFCL.vnext.logging

import app.aaps.core.keys.*
import app.aaps.core.keys.Preferences

object FCLvNextCoreParameterSnapshot {

    fun collect(preferences: Preferences): Map<String, Any> = mapOf(

        // ── Gain & caps ──
        "gain_day" to preferences.get(DoubleKey.fcl_vnext_gain_day),
        "gain_night" to preferences.get(DoubleKey.fcl_vnext_gain_night),
        "max_bolus_day" to preferences.get(DoubleKey.max_bolus_day),
        "max_bolus_night" to preferences.get(DoubleKey.max_bolus_night),
        "hybrid_basal_perc" to preferences.get(IntKey.hybrid_basal_perc),

        // ── Energy model ──
        "k_delta" to preferences.get(DoubleKey.fcl_vnext_k_delta),
        "k_slope" to preferences.get(DoubleKey.fcl_vnext_k_slope),
        "k_accel" to preferences.get(DoubleKey.fcl_vnext_k_accel),

        // ── IOB / safety shaping ──
        "min_consistency" to preferences.get(DoubleKey.fcl_vnext_min_consistency),
        "consistency_exp" to preferences.get(DoubleKey.fcl_vnext_consistency_exp),
        "iob_start" to preferences.get(DoubleKey.fcl_vnext_iob_start),
        "iob_max" to preferences.get(DoubleKey.fcl_vnext_iob_max),
        "iob_min_factor" to preferences.get(DoubleKey.fcl_vnext_iob_min_factor),
        "commit_iob_power" to preferences.get(DoubleKey.fcl_vnext_commit_iob_power),

        // ── Absorption / peak ──
        "absorption_window_min" to preferences.get(IntKey.fcl_vnext_absorption_window_minutes),
        "absorption_dose_factor" to preferences.get(DoubleKey.fcl_vnext_absorption_dose_factor),

        // ── Stagnation ──
        "stagnation_delta_min" to preferences.get(DoubleKey.fcl_vnext_stagnation_delta_min),
        "stagnation_slope_max_neg" to preferences.get(DoubleKey.fcl_vnext_stagnation_slope_max_neg),
        "stagnation_slope_max_pos" to preferences.get(DoubleKey.fcl_vnext_stagnation_slope_max_pos),
        "stagnation_accel_max_abs" to preferences.get(DoubleKey.fcl_vnext_stagnation_accel_max_abs),
        "stagnation_energy_boost" to preferences.get(DoubleKey.fcl_vnext_stagnation_energy_boost),

        // ── Filtering ──
        "bg_smoothing_alpha" to preferences.get(DoubleKey.fcl_vnext_bg_smoothing_alpha)
    )
}
