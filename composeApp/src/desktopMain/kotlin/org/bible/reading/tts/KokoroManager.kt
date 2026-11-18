package org.bible.reading.tts

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class KokoroManager(private val appDir: File) {

    val ttsDir = File(appDir, "tts").apply { mkdirs() }
    val kokoroDir = File(ttsDir, "kokoro").apply { mkdirs() }

    /**
     * Resolves the python executable to use.
     * Prefers explicitly configured path, then known pipx venv, local venv, or system PATH.
     */
    fun resolvePythonPath(configuredPath: String? = null): String {
        if (!configuredPath.isNullOrBlank()) {
            val f = File(configuredPath.trim())
            if (f.exists()) return f.absolutePath
        }

        val userHome = System.getProperty("user.home") ?: ""
        val pipxVenv = File(userHome, ".local/share/pipx/venvs/kokoro-onnx/bin/python3")
        if (pipxVenv.exists()) return pipxVenv.absolutePath

        val localVenv = File(kokoroDir, "venv/bin/python3")
        if (localVenv.exists()) return localVenv.absolutePath

        val winVenv = File(kokoroDir, "venv/Scripts/python.exe")
        if (winVenv.exists()) return winVenv.absolutePath

        if (isCommandAvailable("python3")) return "python3"
        if (isCommandAvailable("python")) return "python"

        return "python3"
    }

    /**
     * Resolves the Kokoro ONNX model file or binary path.
     * Prefers configured path, then default model files inside kokoroDir.
     */
    fun resolveModelFile(configuredPath: String? = null): File {
        if (!configuredPath.isNullOrBlank()) {
            val f = File(configuredPath.trim())
            if (f.exists()) return f
        }

        val candidate1 = File(kokoroDir, "kokoro.onnx")
        if (candidate1.exists()) return candidate1

        val candidate2 = File(kokoroDir, "kokoro-v0_19.onnx")
        if (candidate2.exists()) return candidate2

        return candidate1
    }

    /**
     * Resolves the Kokoro voices file (.bin).
     * Prefers configured path, then default voice files inside kokoroDir.
     */
    fun resolveVoicesFile(configuredPath: String? = null): File {
        if (!configuredPath.isNullOrBlank()) {
            val f = File(configuredPath.trim())
            if (f.exists()) return f
        }

        val candidate1 = File(kokoroDir, "voices-v1.0.bin")
        if (candidate1.exists()) return candidate1

        val candidate2 = File(kokoroDir, "voices.bin")
        if (candidate2.exists()) return candidate2

        return candidate1
    }

    /**
     * Checks if kokoro CLI or python kokoro-onnx / kokoro module is available on the system.
     */
    fun isAvailable(
        configuredPython: String? = null,
        configuredModel: String? = null,
        configuredVoices: String? = null
    ): Boolean {
        // 1. Check kokoro command on PATH
        if (isCommandAvailable("kokoro")) return true

        // 2. Check python with kokoro_onnx or kokoro
        val pythonCmd = resolvePythonPath(configuredPython)
        if (isPythonModuleAvailable(pythonCmd, "kokoro_onnx") || isPythonModuleAvailable(pythonCmd, "kokoro")) {
            return true
        }

        return false
    }

    /**
     * Checks if CUDA GPU acceleration is available and working in the resolved Python environment.
     * Preloads pip-installed NVIDIA DLLs/SOs before checking ONNX Runtime and PyTorch providers.
     */
    suspend fun isCudaAvailable(configuredPython: String? = null): Boolean = withContext(Dispatchers.IO) {
        val pythonCmd = resolvePythonPath(configuredPython)
        val checkScript = """
import sys

# 1. Check ONNX Runtime with pip-installed NVIDIA DLL/SO preloading
try:
    import onnxruntime as ort
    if hasattr(ort, "preload_dlls"):
        ort.preload_dlls()
    if "CUDAExecutionProvider" in ort.get_available_providers():
        import subprocess
        res = subprocess.run(["nvidia-smi"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        if res.returncode == 0:
            sys.exit(0)
except Exception:
    pass

# 2. Fallback check for PyTorch-based kokoro package
try:
    import torch
    if torch.cuda.is_available():
        sys.exit(0)
except Exception:
    pass

sys.exit(1)
        """.trimIndent()

        return@withContext try {
            val process = ProcessBuilder(pythonCmd, "-c", checkScript).start()
            process.waitFor() == 0
        } catch (_: Exception) {
            false
        }
    }

    private fun isCommandAvailable(cmd: String): Boolean {
        return try {
            val p = ProcessBuilder(
                if (System.getProperty("os.name").lowercase().contains("win")) "where" else "which",
                cmd
            ).start()
            p.waitFor() == 0
        } catch (_: Exception) {
            false
        }
    }

    private fun isPythonModuleAvailable(pythonCmd: String, moduleName: String): Boolean {
        return try {
            val p = ProcessBuilder(pythonCmd, "-c", "import $moduleName").start()
            p.waitFor() == 0
        } catch (_: Exception) {
            false
        }
    }

    suspend fun synthesize(
        text: String,
        outputWav: File,
        voice: TtsVoice,
        speechRate: Float = 1.0f,
        configuredPython: String? = null,
        configuredModel: String? = null,
        configuredVoices: String? = null,
        useCuda: Boolean = false
    ): Result<File> = withContext(Dispatchers.IO) {
        val pythonCmd = resolvePythonPath(configuredPython)
        val modelFile = resolveModelFile(configuredModel)
        val voicesFile = resolveVoicesFile(configuredVoices)

        if (!isAvailable(configuredPython, configuredModel, configuredVoices)) {
            return@withContext Result.failure(
                IllegalStateException(
                    "Kokoro TTS is not currently installed or configured. To use Kokoro-82M, specify valid paths in TTS Settings or run:\n" +
                            "pip install kokoro-onnx soundfile\n" +
                            "Alternatively, select Piper TTS for built-in standalone neural voice."
                )
            )
        }

        outputWav.parentFile?.mkdirs()
        if (outputWav.exists()) outputWav.delete()

        val scriptFile = File(kokoroDir, "synthesize.py")
        scriptFile.writeText(
            """
import sys
import os

text = sys.stdin.read().strip()
out_path = sys.argv[1]
voice_id = sys.argv[2]
speed = float(sys.argv[3])
model_path = sys.argv[4] if len(sys.argv) > 4 and sys.argv[4] else "kokoro.onnx"
voices_path = sys.argv[5] if len(sys.argv) > 5 and sys.argv[5] else "voices-v1.0.bin"
use_cuda = len(sys.argv) > 6 and sys.argv[6].lower() == "true"

try:
    from kokoro_onnx import Kokoro
    import soundfile as sf

    if use_cuda:
        import onnxruntime as ort
        if hasattr(ort, "preload_dlls"):
            ort.preload_dlls()
        providers = ['CUDAExecutionProvider', 'CPUExecutionProvider']
        sess = ort.InferenceSession(model_path, providers=providers)
        kokoro = Kokoro.from_session(sess, voices_path)
    else:
        kokoro = Kokoro(model_path, voices_path)

    print("Active providers:", kokoro.sess.get_providers())
    samples, sample_rate = kokoro.create(text, voice=voice_id, speed=speed)
    sf.write(out_path, samples, sample_rate)
    print("SUCCESS")
except ImportError:
    try:
        from kokoro import KPipeline
        import soundfile as sf
        import numpy as np
        import torch

        device = 'cuda' if (use_cuda and torch.cuda.is_available()) else 'cpu'
        print(f"DEVICE: {device}")
        pipeline = KPipeline(lang_code='a', device=device)
        generator = pipeline(text, voice=voice_id, speed=speed, split_pattern=r'\n+')
        all_audio = []
        for _, _, audio in generator:
            all_audio.append(audio)
        if all_audio:
            combined = np.concatenate(all_audio)
            sf.write(out_path, combined, 24000)
            print("SUCCESS")
        else:
            print("ERROR: No audio generated", file=sys.stderr)
            sys.exit(1)
    except Exception as e2:
        print(f"ERROR: {e2}", file=sys.stderr)
        sys.exit(1)
except Exception as e:
    print(f"ERROR: {e}", file=sys.stderr)
    sys.exit(1)
            """.trimIndent()
        )

        try {
            val pb = ProcessBuilder(
                pythonCmd,
                scriptFile.absolutePath,
                outputWav.absolutePath,
                voice.id,
                speechRate.toString(),
                modelFile.absolutePath,
                voicesFile.absolutePath,
                useCuda.toString()
            )
            pb.directory(kokoroDir)
            val process = pb.start()

            process.outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.write(text)
                writer.flush()
            }

            val stdout = process.inputStream.bufferedReader(Charsets.UTF_8).readText()
            val stderr = process.errorStream.bufferedReader(Charsets.UTF_8).readText()
            val exitCode = process.waitFor()

            if (exitCode != 0) {
                return@withContext Result.failure(RuntimeException("Kokoro synthesis error: $stderr"))
            }

            if (useCuda && !stdout.contains("CUDAExecutionProvider") && !stdout.contains("DEVICE: cuda")) {
                return@withContext Result.failure(
                    RuntimeException("CUDA was requested, but Kokoro fell back to CPU.\nStdout: $stdout\nStderr: $stderr")
                )
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