package org.bible.reading.dto

import java.io.File
import java.time.format.DateTimeFormatter

enum class ExportFormat(val extension: String, val displayName: String) {
    TEXT("txt", "Plain Text (.txt)"),
    MARKDOWN("md", "Markdown (.md)"),
    CSV("csv", "CSV Spreadsheet (.csv)")
}

data class ReadingPlan(val readings: List<DailyReading>) {

    fun format(format: ExportFormat = ExportFormat.TEXT): String {
        return when (format) {
            ExportFormat.TEXT -> toFormattedText()
            ExportFormat.MARKDOWN -> toMarkdown()
            ExportFormat.CSV -> toCsv()
        }
    }

    fun toFormattedText(): String {
        val sb = StringBuilder()
        val dateFormatter = DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy")
        sb.appendLine("BIBLE READING PLAN")
        sb.appendLine("Total Days: ${readings.size}")
        sb.appendLine("Total Chapters: ${readings.sumOf { it.reading.size }}")
        if (readings.isNotEmpty()) {
            sb.appendLine("Start Date: ${readings.first().date}")
            sb.appendLine("End Date: ${readings.last().date}")
        }
        sb.appendLine("=".repeat(50))
        sb.appendLine()

        readings.forEachIndexed { index, daily ->
            val dateStr = daily.date.format(dateFormatter)
            val groupedChapters = daily.reading.groupBy { it.book }
                .entries.joinToString(", ") { entry ->
                    "${entry.key} ${entry.value.map { it.chapterNumber }.joinToString(", ")}"
                }
            sb.appendLine("Day ${index + 1} - $dateStr:")
            sb.appendLine("  $groupedChapters")
            sb.appendLine()
        }
        return sb.toString()
    }

    fun toMarkdown(): String {
        val sb = StringBuilder()
        val dateFormatter = DateTimeFormatter.ofPattern("EEEE, MMM d, yyyy")
        sb.appendLine("# Bible Reading Plan")
        sb.appendLine()
        sb.appendLine("- **Total Days**: ${readings.size}")
        sb.appendLine("- **Total Chapters**: ${readings.sumOf { it.reading.size }}")
        if (readings.isNotEmpty()) {
            sb.appendLine("- **Start Date**: ${readings.first().date}")
            sb.appendLine("- **End Date**: ${readings.last().date}")
        }
        sb.appendLine()
        sb.appendLine("| Day | Date | Readings | Chapters |")
        sb.appendLine("| --- | --- | --- | --- |")

        readings.forEachIndexed { index, daily ->
            val dateStr = daily.date.format(dateFormatter)
            val grouped = daily.reading.groupBy { it.book }
                .entries.joinToString("; ") { entry ->
                    "${entry.key} ${entry.value.map { it.chapterNumber }.joinToString(", ")}"
                }
            sb.appendLine("| ${index + 1} | $dateStr | $grouped | ${daily.reading.size} |")
        }
        return sb.toString()
    }

    fun toCsv(): String {
        val sb = StringBuilder()
        sb.appendLine("Day,Date,DayOfWeek,Book,ChapterNumber")
        readings.forEachIndexed { index, daily ->
            val dayNum = index + 1
            val date = daily.date
            val dow = daily.date.dayOfWeek
            daily.reading.forEach { reading ->
                sb.appendLine("$dayNum,$date,$dow,\"${reading.book}\",${reading.chapterNumber}")
            }
        }
        return sb.toString()
    }

    fun saveToFile(
        targetDirectory: String,
        fileName: String,
        format: ExportFormat = ExportFormat.TEXT
    ): File {
        val expandedPath = if (targetDirectory.startsWith("~")) {
            val userHome = System.getProperty("user.home") ?: ""
            targetDirectory.replaceFirst("~", userHome)
        } else {
            targetDirectory
        }

        val dir = File(expandedPath)
        if (!dir.exists()) {
            dir.mkdirs()
        }

        val fullFileName = if (fileName.contains(".")) fileName else "$fileName.${format.extension}"
        val targetFile = File(dir, fullFileName)
        targetFile.writeText(format(format))
        return targetFile
    }

    override fun toString(): String {
        return "ReadingPlan(readings=$readings)"
    }
}
