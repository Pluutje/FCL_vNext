package app.aaps.plugins.aps.openAPSFCL.vnext.learning

import app.aaps.plugins.aps.openAPSFCL.vnext.learning.FCLvNextLearningEpisodeManager.EpisodeOutcome
import app.aaps.plugins.aps.openAPSFCL.vnext.learning.FCLvNextProfile
import app.aaps.plugins.aps.openAPSFCL.vnext.learning.FCLvNextProfileAdvice

class FCLvNextLearningAdvisor {

    private val stats = ParameterLearningStats()
    // ─────────────────────────────────────────────
   // Profile learning (fase 2: adviserend)
   // ─────────────────────────────────────────────
    private var lastProfileAdvice: FCLvNextProfileAdvice? = null
    private var profileEvidenceCount: Int = 0

    fun getProfileAdvice(): FCLvNextProfileAdvice? = lastProfileAdvice



    fun onEpisodeOutcome(
        outcome: EpisodeOutcome,
        isNight: Boolean,
        peakBand: Int,
        rescueConfirmed: Boolean,
        mealActive: Boolean
    ) {
        val hints = OutcomeToHintMapper.map(outcome, isNight, peakBand, rescueConfirmed, mealActive)

        for (h in hints) {
            // whitelist/spec gate (future-safe)
            val spec = LearningParameterSpecs.specs[h.parameter] ?: continue
            if (isNight && !spec.nightAllowed) continue

            stats.update(h.parameter, h.direction, isNight)
        }

        // ─────────────────────────────────────────────
// Profile advice (v1 – deterministic & explainable)
// ─────────────────────────────────────────────
        var scoreAggressive = 0.0
        var scoreStrict = 0.0
        var evidence = 0

// 1️⃣ Outcome-based signal
        when (outcome) {
            EpisodeOutcome.TOO_LATE -> {
                scoreAggressive += 1.0
                evidence++
            }
            EpisodeOutcome.OVERSHOOT -> {
                // vaak: te laat begonnen → achteraf teveel
                scoreAggressive += 0.6
                evidence++
            }
            EpisodeOutcome.HYPO_RISK -> {
                scoreStrict += 1.0
                evidence++
            }
            EpisodeOutcome.TOO_STRONG -> {
                scoreStrict += 0.7
                evidence++
            }
            else -> {
                // GOOD_CONTROL, NO_ACTION_NEEDED → geen signaal
            }
        }

// 2️⃣ Peak severity (alleen relevant bij maaltijd-context)
        if (mealActive && peakBand >= 15) {
            scoreAggressive += 0.8
            evidence++
        } else if (mealActive && peakBand >= 12) {
            scoreAggressive += 0.4
            evidence++
        }

// 3️⃣ Rescue is extra safety-signaal
        if (rescueConfirmed) {
            scoreStrict += 0.6
            evidence++
        }

// 4️⃣ Nacht-bias → iets conservatiever adviseren
        if (isNight) {
            scoreStrict += 0.2
        }

// 5️⃣ Beslis aanbevolen profiel
        val recommendedProfile = when {
            scoreStrict >= scoreAggressive + 0.6 ->
                FCLvNextProfile.STRICT

            scoreAggressive >= scoreStrict + 0.6 ->
                FCLvNextProfile.AGGRESSIVE

            else ->
                FCLvNextProfile.BALANCED
        }

// Confidence = verschil tussen scores
        val confidence =
            kotlin.math.abs(scoreAggressive - scoreStrict)
                .coerceIn(0.0, 1.0)

// Verklarende reden (voor UI / status)
        val reason = buildString {
            append("scores: agg=${"%.2f".format(scoreAggressive)} ")
            append("strict=${"%.2f".format(scoreStrict)}. ")
            append("outcome=$outcome. ")
            if (mealActive) append("mealActive. ")
            if (peakBand > 0) append("peakBand=$peakBand. ")
            if (rescueConfirmed) append("rescueConfirmed. ")
            if (isNight) append("nightBias. ")
        }.trim()

        profileEvidenceCount =
            (profileEvidenceCount + evidence).coerceAtMost(50)

        lastProfileAdvice = FCLvNextProfileAdvice(
            recommended = recommendedProfile,
            confidence = confidence,
            reason = reason,
            evidenceCount = profileEvidenceCount
        )

    }

    fun getAdvice(isNight: Boolean): List<LearningAdvice> =
        stats.allAdvice(isNight)

    // ─────────────────────────────────────────────
    // Persistence support
    // ─────────────────────────────────────────────

    fun exportSnapshot(): FCLvNextLearningSnapshot =
        FCLvNextLearningSnapshot(
            schemaVersion = 1,
            dayStats = stats.exportDayStats(),
            nightStats = stats.exportNightStats(),
            profileAdvice = lastProfileAdvice,
            profileEvidenceCount = profileEvidenceCount
        )

    fun importSnapshot(snapshot: FCLvNextLearningSnapshot) {
        if (snapshot.schemaVersion != 1) return

        stats.importDayStats(snapshot.dayStats)
        stats.importNightStats(snapshot.nightStats)

        lastProfileAdvice = snapshot.profileAdvice
        profileEvidenceCount = snapshot.profileEvidenceCount
    }

    fun getLearningStatus(isNight: Boolean): String {
        val sb = StringBuilder()

        sb.append("🧠 Learning status\n")
        sb.append("─────────────────────\n")

        // parameters
        val paramAdvice = stats.allAdvice(isNight)
        if (paramAdvice.isEmpty()) {
            sb.append("• Parameters: nog geen stabiele adviezen\n")
        } else {
            sb.append("• Parameters:\n")
            paramAdvice
                .sortedByDescending { it.confidence }
                .forEach {
                    val dir = if (it.direction > 0) "↑" else "↓"
                    sb.append(
                        "  - ${it.parameter} $dir " +
                            "conf=${"%.2f".format(it.confidence)} " +
                            "n=${it.evidenceCount}\n"
                    )
                }
        }

        // profiel
        val p = lastProfileAdvice
        if (p == null) {
            sb.append("• Profiel: nog geen advies\n")
        } else {
            sb.append(
                "• Profiel: ${p.recommended} " +
                    "conf=${"%.2f".format(p.confidence)} " +
                    "n=${p.evidenceCount}\n"
            )
        }

        return sb.toString().trimEnd()
    }


}
