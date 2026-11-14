package org.bible.reading.tts

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class SystemTtsEngine {

    private val osName = System.getProperty("os.name").lowercase()
    private val isWindows = osName.contains("win")
    private val isMac = osName.contains("mac") || osName.contains("darwin")
    private val isLinux = osName.contains("linux") || osName.contains("nix")

    suspend fun synthesize(
        text: String,
        outputWav: File,
        speechRate: Float = 1.0f
    ): Result<File> = withContext(Dispatchers.IO) {
        outputWav.parentFile?.mkdirs()
        if (outputWav.exists()) outputWav.delete()

        try {
            when {
                isMac -> {
                    val rate = (175 * speechRate).toInt()
                    val pb = ProcessBuilder(
                        "say",
                        "-r", rate.toString(),
                        "-o", outputWav.absolutePath,
                        "--data-format=LEI16@22050",
                        text
                    )
                    val p = pb.start()
                    p.waitFor()
                }
                isLinux -> {
                    // Try espeak-ng or espeak to write WAV directly
                    val command = when {
                        isCommandAvailable("espeak-ng") -> "espeak-ng"
                        isCommandAvailable("espeak") -> "espeak"
                        else -> null
                    }

                    if (command != null) {
                        val speed = (160 * speechRate).toInt()
                        val pb = ProcessBuilder(
                            command,
                            "-s", speed.toString(),
                            "-w", outputWav.absolutePath
                        )
                        val p = pb.start()
                        p.outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
                            writer.write(text)
                            writer.flush()
                        }
                        p.waitFor()
                    } else {
                        // spd-say fallback (speaks directly if cannot write WAV)
                        val pb = ProcessBuilder("spd-say", "-t", "male1", text)
                        val p = pb.start()
                        p.waitFor()
                        return@withContext Result.failure(
                            IllegalStateException("System speech on Linux played via spd-say. For full seekable playback and natural voices, use Piper TTS.")
                        )
                    }
                }
                isWindows -> {
                    val psScript = """
Add-Type -AssemblyName System.Speech
${'$'}synth = New-Object System.Speech.Synthesis.SpeechSynthesizer
${'$'}synth.Rate = ${((speechRate - 1.0f) * 5).toInt().coerceIn(-10, 10)}
${'$'}synth.SetOutputToWaveFile('${outputWav.absolutePath.replace("'", "''")}')
${'$'}synth.Speak('${text.replace("'", "''")}')
${'$'}synth.Dispose()
                    """.trimIndent()

                    val pb = ProcessBuilder("powershell", "-NoProfile", "-Command", psScript)
                    val p = pb.start()
                    p.waitFor()
                }
            }

            if (outputWav.exists() && outputWav.length() > 0) {
                Result.success(outputWav)
            } else {
                Result.failure(RuntimeException("System TTS could not generate audio file."))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun isCommandAvailable(cmd: String): Boolean {
        return try {
            val p = ProcessBuilder("which", cmd).start()
            p.waitFor() == 0
        } catch (_: Exception) {
            false
        }
    }
}
