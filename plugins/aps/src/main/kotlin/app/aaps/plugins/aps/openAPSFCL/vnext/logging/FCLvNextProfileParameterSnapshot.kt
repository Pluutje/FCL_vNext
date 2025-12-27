package app.aaps.plugins.aps.openAPSFCL.vnext.logging

import app.aaps.core.keys.*
import app.aaps.core.keys.Preferences

object FCLvNextProfileParameterSnapshot {

    fun collect(preferences: Preferences): Map<String, Any> = mapOf(

        // ── Dag/nacht & tijd ──
        "ochtend_start" to preferences.get(StringKey.OchtendStart),
        "ochtend_start_weekend" to preferences.get(StringKey.OchtendStartWeekend),
        "nacht_start" to preferences.get(StringKey.NachtStart),
        "weekend_dagen" to preferences.get(StringKey.WeekendDagen),

        // ── Resistentie ──
        "resistentie_enabled" to preferences.get(BooleanKey.Resistentie),
        "min_resistentie_perc" to preferences.get(IntKey.Min_resistentiePerc),
        "max_resistentie_perc" to preferences.get(IntKey.Max_resistentiePerc),
        "dag_resistentie_perc" to preferences.get(IntKey.Dag_resistentiePerc),
        "nacht_resistentie_perc" to preferences.get(IntKey.Nacht_resistentiePerc),
        "dag_resistentie_target" to preferences.get(DoubleKey.Dag_resistentie_target),
        "nacht_resistentie_target" to preferences.get(DoubleKey.Nacht_resistentie_target),
        "uren_resistentie" to preferences.get(DoubleKey.Uren_resistentie),
        "min_delay_resistentie" to preferences.get(IntKey.MinDelay_resistentie),

        // ── Activiteit ──
        "stappen_enabled" to preferences.get(BooleanKey.stappenAanUit),
        "stap_activiteit_perc" to preferences.get(IntKey.stap_activiteteitPerc),
        "stap_tt" to preferences.get(DoubleKey.stap_TT),
        "stap_5min" to preferences.get(IntKey.stap_5minuten),
        "stap_retentie" to preferences.get(IntKey.stap_retentie)
    )
}
