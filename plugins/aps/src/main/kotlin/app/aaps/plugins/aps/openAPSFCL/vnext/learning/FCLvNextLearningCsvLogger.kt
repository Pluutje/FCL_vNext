package app.aaps.plugins.aps.openAPSFCL.vnext.learning

import java.io.File
import java.io.FileWriter
import org.joda.time.format.ISODateTimeFormat

object FCLvNextLearningCsvLogger {

    private const val FILE_NAME = "FCLvNext_LearningEpisodes.csv"

    private val dateFormatter =
        ISODateTimeFormat.dateTime()

    private val header = listOf(
        "startTime",
        "endTime",
        "isNight",
        "startBg",
        "targetBg",
        "maxSlope",
        "maxAccel",
        "predictedPeak",
        "peakBand",
        "totalDelivered",
        "earlyStageMax",
        "rescueTriggered",
        "outcome"
    ).joinToString(",")

    fun log(
        directory: File,
        snapshot: LearningEpisodeSnapshot
    ) {
        val file = File(directory, FILE_NAME)
        val isNew = !file.exists()

        FileWriter(file, true).use { writer ->
            if (isNew) {
                writer.appendLine(header)
            }

            writer.appendLine(
                listOf(
                    dateFormatter.print(snapshot.startTime),
                    dateFormatter.print(snapshot.endTime),
                    snapshot.isNight,
                    format(snapshot.startBg),
                    format(snapshot.targetBg),
                    format(snapshot.maxSlope),
                    format(snapshot.maxAccel),
                    format(snapshot.predictedPeak),
                    snapshot.peakBand,
                    format(snapshot.totalDelivered),
                    snapshot.earlyStageMax,
                    snapshot.rescueTriggered,
                    snapshot.outcome.name
                ).joinToString(",")
            )
        }
    }

    private fun format(v: Double): String =
        String.format("%.3f", v)
}
