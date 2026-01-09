package app.aaps.plugins.aps.openAPSFCL.vnext

import org.joda.time.DateTime
import app.aaps.core.keys.Preferences
import app.aaps.core.keys.StringKey

class FCLvNextStatusFormatter(private val prefs: Preferences) {


    private fun formatDeliveryHistory(
        history: List<Pair<DateTime, Double>>?
    ): String {
        if (history.isNullOrEmpty()) return "Geen recente afleveringen"

        return history.joinToString("\n") { (ts, dose) ->
            "${ts.toString("HH:mm")}  ${"%.2f".format(dose)}U"
        }
    }


    /**
     * Haal de blokregels onder "LEARNING ADVICE:" eruit.
     * In jouw FCLvNext wordt dat zo opgebouwd:
     *   LEARNING ADVICE:
     *    - param ↑ conf=.. n=..
     *    - ...
     */
    private fun extractLearningAdviceLines(statusText: String?): List<String> {
        if (statusText.isNullOrBlank()) return emptyList()

        val lines = statusText.split("\n")
        var inBlock = false
        val out = ArrayList<String>()

        for (raw in lines) {
            val line = raw.trim()

            if (!inBlock) {
                if (line == "LEARNING ADVICE:") {
                    inBlock = true
                }
                continue
            }

            // we zitten in het block
            // stopcriteria: lege regel of een duidelijke sectiewissel
            if (line.isEmpty()) break

            // jouw advice regels beginnen met "-"
            if (line.length >= 1 && line[0] == '-') {
                out.add(line)
            } else {
                // als het geen advice-regel meer is, stoppen we om rommel te voorkomen
                break
            }
        }

        return out
    }

    private fun extractProfileAdviceLine(statusText: String?): String? {
        if (statusText.isNullOrBlank()) return null
        for (line in statusText.split("\n")) {
            val t = line.trim()
            if (t.length >= 14 && t.substring(0, 14) == "PROFILE ADVICE:") {
                return t
            }
        }
        return null
    }

    private fun extractProfileReasonLine(statusText: String?): String? {
        if (statusText.isNullOrBlank()) return null
        for (line in statusText.split("\n")) {
            val t = line.trim()
            if (t.length >= 15 && t.substring(0, 15) == "PROFILE REASON:") {
                return t
            }
        }
        return null
    }

    private fun extractPersistLines(statusText: String?): List<String> {
        if (statusText.isNullOrBlank()) return emptyList()

        val out = ArrayList<String>()
        for (line in statusText.split("\n")) {
            val t = line.trim()
            if (t.length >= 7 && t.substring(0, 7) == "PERSIST") {
                out.add(t)
            }
        }
        return out
    }



    /**
     * Maak statusText compacter:
     * - toont eerst profiel + learning advice (als aanwezig)
     * - daarna eventueel de rest van statusText (optioneel, compact)
     */
    private fun buildFclBlock(advice: FCLvNextAdvice?): String {
        if (advice == null) return "Geen FCL advies"

        val statusText = advice.statusText ?: ""
        val profileAdviceLine = extractProfileAdviceLine(statusText)
        val profileReasonLine = extractProfileReasonLine(statusText)
        val learningLines = extractLearningAdviceLines(statusText)

        val persistLines = extractPersistLines(statusText)

        val sb = StringBuilder()

        sb.append("🧠 FCL vNext\n")
        sb.append("─────────────────────\n")


        if (profileAdviceLine != null) {
            sb.append("• ").append(profileAdviceLine).append("\n")
            if (profileReasonLine != null) {
                sb.append("• ").append(profileReasonLine).append("\n")
            }
        }

        if (learningLines.isNotEmpty()) {
            sb.append("\n")
            sb.append("📌 Learning adviezen\n")
            sb.append("─────────────────────\n")
            learningLines.forEach { line ->
                sb.append("• ").append(line).append("\n")
            }
        }



        if (persistLines.isNotEmpty()) {
            sb.append("\n")
            sb.append("🔁 Persistente correctie\n")
            sb.append("─────────────────────\n")
            persistLines.forEach { line ->
                val human = when {
                    line.contains("building") ->
                        "Opbouw: glucose blijft gedurende meerdere metingen verhoogd"

                    line.contains("fire") ->
                        "Correctie gegeven wegens aanhoudend hoge glucose"

                    line.contains("cooldown") ->
                        "Wachttijd actief na correctie (veiligheidsinterval)"

                    line.contains("HOLD") ->
                        "Correctie bewust uitgesteld (stabiliteitsfase)"

                    else ->
                        line   // fallback: toon originele tekst
                }

                sb.append("• ").append(human).append("\n")
            }

        }

        // Optioneel: als je tóch nog debug wil zien, laat hier een compacte excerpt zien.
        // Nu: alleen de eerste ~25 regels om UI netjes te houden.
        val lines = statusText.split("\n").map { it.trim() }

        fun section(title: String, filter: (String) -> Boolean) {
            val block = lines.filter(filter)
            if (block.isNotEmpty()) {
                sb.append("\n")
                sb.append(title).append("\n")
                sb.append("─────────────────────\n")
                block.forEach { sb.append(it).append("\n") }
            }
        }

// 📈 Trends & dynamiek
        section("📈 Trend & dynamiek") {
            it.startsWith("TREND") ||
                it.startsWith("TrendPersistence") ||
                it.startsWith("PeakEstimate")
        }

// 💉 Dosering
        section("💉 Dosering & beslissingen") {
            it.startsWith("RawDose") ||
                it.startsWith("Decision=") ||
                it.startsWith("Trajectory") ||
                it.startsWith("ACCESS")
        }

// ⏳ Timing / commits
        section("⏳ Timing & commits") {
            it.startsWith("Commit") ||
                it.startsWith("OBSERVE") ||
                it.startsWith("DELIVERY")
        }


        return sb.toString().trimEnd()
    }

    fun buildStatus(
        isNight: Boolean,
        advice: FCLvNextAdvice?,
        bolusAmount: Double,
        basalRate: Double,
        shouldDeliver: Boolean,
        activityLog: String?,
        resistanceLog: String?,
        metricsText: String?,
        learningStatusText: String?
    ): String {

        val coreStatus = """
STATUS: (${if (isNight) "'S NACHTS" else "OVERDAG"})
─────────────────────
• Laatste update: ${DateTime.now().toString("HH:mm:ss")}
• Advies actief: ${if (shouldDeliver) "JA" else "NEE"}
• Bolus: ${"%.2f".format(bolusAmount)} U
• Basaal: ${"%.2f".format(basalRate)} U/h

🧪 LAATSTE DOSISSEN
─────────────────────
${formatDeliveryHistory(advice?.let { deliveryHistory.toList() })}
""".trimIndent()

        val fclCore = buildFclBlock(advice)

        val learningBlock = learningStatusText ?: """
🧠 Learning status
─────────────────────
Learning actief, maar nog geen gegevens
""".trimIndent()


        val activityStatus = """
🏃 ACTIVITEIT
─────────────────────
${activityLog ?: "Geen activiteitdata"}
""".trimIndent()

        val resistanceStatus = """
🧬 AUTO-SENS
─────────────────────
${resistanceLog ?: "Geen resistentie-log"}
""".trimIndent()

        val metricsStatus = """
📊 GLUCOSE STATISTIEKEN
─────────────────────
${metricsText ?: "Nog geen data"}
""".trimIndent()

        return """
════════════════════════
 🧠 FCL vNext v21.7.0 
════════════════════════
• Profiel              : ${prefs.get(StringKey.fcl_vnext_profile)}
• Meal Detect Speed  : ${prefs.get(StringKey.fcl_vnext_meal_detect_speed)}
• Correction style   : ${prefs.get(StringKey.fcl_vnext_correction_style)}
• Insulin distribution : ${prefs.get(StringKey.fcl_vnext_dose_distribution_style)}


$coreStatus

$fclCore

$learningBlock

$activityStatus

$resistanceStatus

$metricsStatus
""".trimIndent()
    }
}
