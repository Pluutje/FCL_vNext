package app.aaps.plugins.aps.openAPSFCL.vnext.logging

import app.aaps.core.keys.*
import app.aaps.core.keys.Preferences

/**
 * Bevat ALLE parameters die via de UI instelbaar zijn.
 * Doel:
 * - Eén CSV voor backup / restore bij herinstallatie
 * - Geen vaste constants
 * - Geen learning-interne parameters
 */
object FCLvNextProfileParameterSnapshot {

    fun collect(preferences: Preferences): Map<String, Any> = mapOf(

        // =================================================
        // ⚙️ Algemeen gedrag (FCLvNext UI)
        // =================================================
        "gain_day" to preferences.get(DoubleKey.fcl_vnext_gain_day),
        "gain_night" to preferences.get(DoubleKey.fcl_vnext_gain_night),
        "max_bolus_day" to preferences.get(DoubleKey.max_bolus_day),
        "max_bolus_night" to preferences.get(DoubleKey.max_bolus_night),


        // =================================================
        // 🌙 Dag / nacht & tijd
        // =================================================
        "ochtend_start" to preferences.get(StringKey.OchtendStart),
        "ochtend_start_weekend" to preferences.get(StringKey.OchtendStartWeekend),
        "nacht_start" to preferences.get(StringKey.NachtStart),
        "weekend_dagen" to preferences.get(StringKey.WeekendDagen),



    )
}
