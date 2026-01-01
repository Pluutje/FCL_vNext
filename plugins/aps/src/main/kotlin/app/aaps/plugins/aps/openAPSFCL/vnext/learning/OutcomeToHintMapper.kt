package app.aaps.plugins.aps.openAPSFCL.vnext.learning

import app.aaps.plugins.aps.openAPSFCL.vnext.learning.FCLvNextLearningEpisodeManager.EpisodeOutcome

object OutcomeToHintMapper {

    fun map(
        outcome: EpisodeOutcome,
        isNight: Boolean,
        peakBand: Int,
        rescueConfirmed: Boolean,
        mealActive: Boolean
    ): List<ParameterHint> {

        // safety always dominates
        if (rescueConfirmed) {
            return listOf(
                ParameterHint(LearningParameter.K_DELTA, -1),
                ParameterHint(LearningParameter.K_SLOPE, -1)
            )
        }

        return when (outcome) {

            EpisodeOutcome.TOO_LATE -> {
                // Alleen zinvol bij echte meal/high dynamiek
                if (!mealActive && peakBand < 12) emptyList()
                else listOf(
                    ParameterHint(LearningParameter.K_SLOPE, +1),
                    ParameterHint(LearningParameter.K_ACCEL, +1)
                )
            }

            EpisodeOutcome.TOO_STRONG -> {
                // Nacht: extra conservatief adviseren (optioneel)
                if (isNight) listOf(ParameterHint(LearningParameter.K_ACCEL, -1), ParameterHint(LearningParameter.K_SLOPE, -1))
                else listOf(ParameterHint(LearningParameter.K_ACCEL, -1))
            }

            EpisodeOutcome.OVERSHOOT -> {
                // Peak model vermoedelijk te laag → peakMomentum omhoog
                listOf(ParameterHint(LearningParameter.PEAK_MOMENTUM_GAIN, +1))
            }

            else -> emptyList()
        }
    }
}
