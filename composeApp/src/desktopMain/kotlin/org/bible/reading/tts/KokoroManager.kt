package org.bible.reading.tts

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class KokoroManager(private val appDir: File) {

    val ttsDir = File(appDir, "tts").apply { mkdirs() }
    val kokoroDir = File(ttsDir, "kokoro").apply { mkdirs() }

    /**
     * Checks if kokoro CLI or python kokoro-onnx module is available on the system.
     */
    fun isAvailable(): Boolean {
        // 1. Check kokoro command on PATH
        if (isCommandAvailable("kokoro")) return true

        // 2. Check python3 with kokoro or kokoro_onnx
        if (isPythonModuleAvailable("kokoro_onnx") || isPythonModuleAvailable("kokoro")) return true

        return false
    }

    private fun isCommandAvailable(cmd: String): Boolean {
        return try {
            val p = ProcessBuilder(if (System.getProperty("os.name").lowercase().contains("win")) "where" else "which", cmd).start()
            p.waitFor() == 0
        } catch (_: Exception) {
            false
        }
    }

    private fun isPythonModuleAvailable(moduleName: String): Boolean {
        return try {
            val p = ProcessBuilder("python3", "-c", "import $moduleName").start()
            p.waitFor() == 0
        } catch (_: Exception) {
            false
        }
    }

    suspend fun synthesize(
        text: String,
        outputWav: File,
        voice: TtsVoice,
        speechRate: Float = 1.0f
    ): Result<File> = withContext(Dispatchers.IO) {
        if (!isAvailable()) {
            return@withContext Result.failure(
                IllegalStateException(
                    "Kokoro TTS is not currently installed. To use Kokoro-82M, run:\n" +
                    "pip install kokoro-onnx soundfile\n" +
                    "Alternatively, select Piper TTS for built-in standalone neural voice."
                )
            )
        }

        outputWav.parentFile?.mkdirs()
        if (outputWav.exists()) outputWav.delete()

        // Python synthesis script using kokoro_onnx or kokoro
        val scriptFile = File(kokoroDir, "synthesize.py")
        scriptFile.writeText(
            """
import sys
import os

text = sys.stdin.read().strip()
out_path = sys.argv[1]
voice_id = sys.argv[2]
speed = float(sys.argv[3])

try:
    from kokoro_onnx import Kokoro
    import soundfile as sf
    kokoro = Kokoro()
    samples, sample_rate = kokoro.create(text, voice=voice_id, speed=speed)
    sf.write(out_path, samples, sample_rate)
    print("SUCCESS")
except Exception as e:
    print(f"ERROR: {e}", file=sys.stderr)
    sys.exit(1)
            """.trimIndent()
        )

        try {
            val pb = ProcessBuilder(
                "python3",
                scriptFile.absolutePath,
                outputWav.absolutePath,
                voice.id,
                speechRate.toString()
            )
            pb.directory(kokoroDir)
            val process = pb.start()

            process.outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.write(text)
                writer.flush()
            }

            val exitCode = process.waitFor()
            if (exitCode != 0) {
                val err = process.errorStream.bufferedReader().readText()
                return@withContext Result.failure(RuntimeException("Kokoro synthesis error: $err"))
            }

            if (outputWav.exists() && outputWav.length() > 0) {
                Result.success(outputWav)
            } else {
                Result.failure(RuntimeException("Kokoro synthesis failed to generate audio file."))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
