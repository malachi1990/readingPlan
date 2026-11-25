package org.bible.reading.tts

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class KokoroManager(
    private val appDir: File,
    private val workerIdleTimeoutMs: Long = DEFAULT_IDLE_TIMEOUT_MS
) {

    companion object {
        const val DEFAULT_IDLE_TIMEOUT_MS = 120_000L
        private const val PROBE_CACHE_TTL_MS = 30_000L

        private val registeredWorkers = Collections.synchronizedSet(HashSet<WeakReference<Process>>())

        init {
            try {
                Runtime.getRuntime().addShutdownHook(Thread({
                    val snapshot = synchronized(registeredWorkers) {
                        registeredWorkers.mapNotNull { it.get() }.also { registeredWorkers.clear() }
                    }
                    for (proc in snapshot) {
                        try {
                            if (proc.isAlive) {
                                proc.destroy()
                                if (!proc.waitFor(300, TimeUnit.MILLISECONDS)) {
                                    proc.destroyForcibly()
                                }
                            }
                        } catch (_: Exception) {}
                    }
                }, "KokoroWorkerShutdownHook"))
            } catch (_: IllegalStateException) {
                // Shutdown already in progress
            }
        }

        private val WORKER_SCRIPT = """
            import json
            import sys

            proto_out = sys.stdout
            # Keep stdout clean for JSON-line IPC by redirecting library logs to stderr
            sys.stdout = sys.stderr

            cached_backend = None
            cached_key = None
            cached_engine = None

            def load_kokoro_onnx(model_path, voices_path, use_cuda):
                from kokoro_onnx import Kokoro
                import soundfile  # ensure dependency is available before committing to onnx backend
                if use_cuda:
                    import onnxruntime as ort
                    if hasattr(ort, "preload_dlls"):
                        ort.preload_dlls()
                    providers = ["CUDAExecutionProvider", "CPUExecutionProvider"]
                    sess = ort.InferenceSession(model_path, providers=providers)
                    kokoro = Kokoro.from_session(sess, voices_path)
                else:
                    kokoro = Kokoro(model_path, voices_path)
                providers_list = list(kokoro.sess.get_providers())
                if use_cuda and "CUDAExecutionProvider" not in providers_list:
                    raise RuntimeError(
                        f"CUDA was requested, but Kokoro fell back to CPU. Active providers: {providers_list}"
                    )
                return kokoro, f"Active providers: {providers_list}"

            def load_kokoro_torch(use_cuda):
                from kokoro import KPipeline
                import torch
                device = "cuda" if (use_cuda and torch.cuda.is_available()) else "cpu"
                if use_cuda and device != "cuda":
                    raise RuntimeError(
                        f"CUDA was requested, but Kokoro fell back to CPU. DEVICE: {device}"
                    )
                pipeline = KPipeline(lang_code="a", device=device)
                return pipeline, f"DEVICE: {device}"

            def get_engine(model_path, voices_path, use_cuda):
                global cached_backend, cached_key, cached_engine
                key = (model_path, voices_path, bool(use_cuda))
                if cached_engine is not None and cached_key == key:
                    return cached_backend, cached_engine[0], cached_engine[1]

                cached_engine = None
                cached_key = None

                try:
                    engine, info = load_kokoro_onnx(model_path, voices_path, use_cuda)
                    cached_backend = "onnx"
                    cached_key = key
                    cached_engine = (engine, info)
                    return cached_backend, engine, info
                except ImportError:
                    engine, info = load_kokoro_torch(use_cuda)
                    cached_backend = "torch"
                    cached_key = key
                    cached_engine = (engine, info)
                    return cached_backend, engine, info

            def write_response(obj):
                proto_out.write(json.dumps(obj) + "\n")
                proto_out.flush()

            write_response({"ready": True})

            for raw_line in sys.stdin:
                line = raw_line.strip()
                if not line:
                    continue
                try:
                    req = json.loads(line)
                    if req.get("cmd") == "quit":
                        write_response({"ok": True})
                        break

                    text = str(req["text"]).strip()
                    out_path = str(req["out_path"])
                    voice_id = str(req["voice_id"])
                    speed = float(req["speed"])
                    model_path = str(req.get("model_path") or "kokoro.onnx")
                    voices_path = str(req.get("voices_path") or "voices-v1.0.bin")
                    use_cuda = bool(req.get("use_cuda", False))

                    backend, engine, provider_info = get_engine(model_path, voices_path, use_cuda)

                    import soundfile as sf
                    if backend == "onnx":
                        samples, sample_rate = engine.create(text, voice=voice_id, speed=speed)
                        sf.write(out_path, samples, sample_rate)
                    else:
                        import numpy as np
                        generator = engine(text, voice=voice_id, speed=speed, split_pattern=r"\n+")
                        all_audio = [audio for _, _, audio in generator]
                        if not all_audio:
                            raise RuntimeError("No audio generated")
                        combined = np.concatenate(all_audio)
                        sf.write(out_path, combined, 24000)

                    write_response({"ok": True, "provider_info": provider_info})
                except Exception as e:
                    write_response({"ok": False, "error": str(e)})
        """.trimIndent()
    }

    private data class ProbeCacheEntry<T>(val value: T, val timestampMs: Long)
    private data class WorkerKey(
        val pythonCmd: String,
        val modelPath: String,
        val voicesPath: String,
        val useCuda: Boolean
    )

    private class WorkerSession(
        val process: Process,
        val processRef: WeakReference<Process>,
        val writer: BufferedWriter,
        val reader: BufferedReader,
        val key: WorkerKey,
        val recentStderr: ArrayDeque<String>
    ) {
        @Volatile
        var lastUsedAtMs: Long = System.currentTimeMillis()
    }

    val ttsDir = File(appDir, "tts").apply { mkdirs() }
    val kokoroDir = File(ttsDir, "kokoro").apply { mkdirs() }

    private val commandCache = ConcurrentHashMap<String, ProbeCacheEntry<Boolean>>()
    private val pythonModuleCache = ConcurrentHashMap<Pair<String, String>, ProbeCacheEntry<Boolean>>()
    private val availabilityCache = ConcurrentHashMap<Triple<String, String, String>, ProbeCacheEntry<Boolean>>()

    private val workerMutex = Mutex()
    private val workerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activeWorker: WorkerSession? = null
    private var idleReaperJob: Job? = null

    val isWorkerRunning: Boolean
        get() = activeWorker?.process?.isAlive == true

    fun invalidateCache() {
        commandCache.clear()
        pythonModuleCache.clear()
        availabilityCache.clear()
    }

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

    suspend fun resolvePythonPathAsync(configuredPath: String? = null): String = withContext(Dispatchers.IO) {
        resolvePythonPath(configuredPath)
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
     * Results are cached briefly to avoid spawning subprocess probes on every UI frame.
     */
    fun isAvailable(
        configuredPython: String? = null,
        configuredModel: String? = null,
        configuredVoices: String? = null
    ): Boolean {
        val cacheKey = Triple(
            configuredPython?.trim().orEmpty(),
            configuredModel?.trim().orEmpty(),
            configuredVoices?.trim().orEmpty()
        )
        val now = System.currentTimeMillis()
        availabilityCache[cacheKey]?.let { entry ->
            if (now - entry.timestampMs < PROBE_CACHE_TTL_MS) {
                return entry.value
            }
        }

        val available = computeAvailability(configuredPython)
        availabilityCache[cacheKey] = ProbeCacheEntry(available, now)
        return available
    }

    suspend fun isAvailableAsync(
        configuredPython: String? = null,
        configuredModel: String? = null,
        configuredVoices: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        isAvailable(configuredPython, configuredModel, configuredVoices)
    }

    private fun computeAvailability(configuredPython: String?): Boolean {
        if (isCommandAvailable("kokoro")) return true

        val pythonCmd = resolvePythonPath(configuredPython)
        return isPythonModuleAvailable(pythonCmd, "kokoro_onnx") ||
            isPythonModuleAvailable(pythonCmd, "kokoro")
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
        val now = System.currentTimeMillis()
        commandCache[cmd]?.let { entry ->
            if (now - entry.timestampMs < PROBE_CACHE_TTL_MS) {
                return entry.value
            }
        }

        val available = try {
            val p = ProcessBuilder(
                if (System.getProperty("os.name").lowercase().contains("win")) "where" else "which",
                cmd
            ).start()
            p.waitFor() == 0
        } catch (_: Exception) {
            false
        }
        commandCache[cmd] = ProbeCacheEntry(available, now)
        return available
    }

    private fun isPythonModuleAvailable(pythonCmd: String, moduleName: String): Boolean {
        val cacheKey = pythonCmd to moduleName
        val now = System.currentTimeMillis()
        pythonModuleCache[cacheKey]?.let { entry ->
            if (now - entry.timestampMs < PROBE_CACHE_TTL_MS) {
                return entry.value
            }
        }

        val available = try {
            val p = ProcessBuilder(pythonCmd, "-c", "import $moduleName").start()
            p.waitFor() == 0
        } catch (_: Exception) {
            false
        }
        pythonModuleCache[cacheKey] = ProbeCacheEntry(available, now)
        return available
    }

    suspend fun shutdownWorker() {
        workerMutex.withLock {
            idleReaperJob?.cancel()
            idleReaperJob = null
            terminateWorkerLocked(activeWorker)
            activeWorker = null
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

        val workerScriptFile = ensureWorkerScriptFile()
        val targetKey = WorkerKey(
            pythonCmd = pythonCmd,
            modelPath = modelFile.absolutePath,
            voicesPath = voicesFile.absolutePath,
            useCuda = useCuda
        )

        workerMutex.withLock {
            try {
                val requestJson = buildWorkerRequestJson(
                    text = text,
                    outPath = outputWav.absolutePath,
                    voiceId = voice.id,
                    speed = speechRate,
                    modelPath = modelFile.absolutePath,
                    voicesPath = voicesFile.absolutePath,
                    useCuda = useCuda
                )

                var responseLine = executeOnWorkerLocked(targetKey, workerScriptFile, requestJson, allowRetry = true)
                val ok = isJsonTrue(responseLine, "ok")
                if (!ok) {
                    val errorMsg = extractJsonString(responseLine, "error")
                        ?: "Kokoro synthesis failed (response: $responseLine)"
                    return@withLock Result.failure(RuntimeException("Kokoro synthesis error: $errorMsg"))
                }

                if (outputWav.exists() && outputWav.length() > 0) {
                    scheduleIdleReaperLocked()
                    Result.success(outputWav)
                } else {
                    Result.failure(RuntimeException("Kokoro synthesis failed to generate audio file."))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    private fun ensureWorkerScriptFile(): File {
        val scriptFile = File(kokoroDir, "kokoro_worker.py")
        val existing = if (scriptFile.exists()) {
            try {
                scriptFile.readText(Charsets.UTF_8)
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }
        if (existing != WORKER_SCRIPT) {
            scriptFile.writeText(WORKER_SCRIPT, Charsets.UTF_8)
        }
        return scriptFile
    }

    private fun executeOnWorkerLocked(
        targetKey: WorkerKey,
        workerScriptFile: File,
        requestJson: String,
        allowRetry: Boolean
    ): String {
        var session = ensureWorkerSessionLocked(targetKey, workerScriptFile)
        return try {
            session.writer.write(requestJson)
            session.writer.newLine()
            session.writer.flush()
            session.lastUsedAtMs = System.currentTimeMillis()
            val response = session.reader.readLine()
                ?: throw IllegalStateException(formatCrashDiagnostics("Kokoro worker closed stdout unexpectedly", session))
            session.lastUsedAtMs = System.currentTimeMillis()
            response
        } catch (e: Exception) {
            terminateWorkerLocked(session)
            activeWorker = null
            if (allowRetry) {
                executeOnWorkerLocked(targetKey, workerScriptFile, requestJson, allowRetry = false)
            } else {
                throw RuntimeException(formatCrashDiagnostics(e.message ?: "Kokoro worker communication error", session), e)
            }
        }
    }

    private fun ensureWorkerSessionLocked(targetKey: WorkerKey, workerScriptFile: File): WorkerSession {
        val current = activeWorker
        if (current != null && current.process.isAlive && current.key == targetKey) {
            return current
        }

        if (current != null) {
            terminateWorkerLocked(current)
            activeWorker = null
        }

        val pb = ProcessBuilder(targetKey.pythonCmd, "-u", workerScriptFile.absolutePath)
        pb.directory(kokoroDir)
        val process = pb.start()
        val procRef = WeakReference(process)
        registeredWorkers.add(procRef)

        val stderrBuffer = ArrayDeque<String>(50)
        val stderrThread = Thread({
            try {
                process.errorStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                    lines.forEach { line ->
                        synchronized(stderrBuffer) {
                            if (stderrBuffer.size >= 50) {
                                stderrBuffer.removeFirst()
                            }
                            stderrBuffer.addLast(line)
                        }
                    }
                }
            } catch (_: Exception) {}
        }, "KokoroWorkerStderr").apply {
            isDaemon = true
            start()
        }
        // Suppress unused variable warning cleanly while keeping thread alive as daemon
        stderrThread.name

        val writer = process.outputStream.bufferedWriter(Charsets.UTF_8)
        val reader = process.inputStream.bufferedReader(Charsets.UTF_8)
        val session = WorkerSession(
            process = process,
            processRef = procRef,
            writer = writer,
            reader = reader,
            key = targetKey,
            recentStderr = stderrBuffer
        )

        val readyLine = reader.readLine()
        if (readyLine == null || !isJsonTrue(readyLine, "ready")) {
            val diag = formatCrashDiagnostics("Kokoro worker failed to initialize (output: $readyLine)", session)
            terminateWorkerLocked(session)
            throw IllegalStateException(diag)
        }

        activeWorker = session
        return session
    }

    private fun terminateWorkerLocked(session: WorkerSession?) {
        if (session == null) return
        try {
            if (session.process.isAlive) {
                try {
                    session.writer.write("{\"cmd\":\"quit\"}\n")
                    session.writer.flush()
                } catch (_: Exception) {}
                if (!session.process.waitFor(400, TimeUnit.MILLISECONDS)) {
                    session.process.destroy()
                    if (!session.process.waitFor(300, TimeUnit.MILLISECONDS)) {
                        session.process.destroyForcibly()
                    }
                }
            }
        } catch (_: Exception) {
            try {
                session.process.destroyForcibly()
            } catch (_: Exception) {}
        } finally {
            registeredWorkers.remove(session.processRef)
            try { session.writer.close() } catch (_: Exception) {}
            try { session.reader.close() } catch (_: Exception) {}
        }
    }

    private fun scheduleIdleReaperLocked() {
        if (workerIdleTimeoutMs <= 0L) return
        idleReaperJob?.cancel()
        idleReaperJob = workerScope.launch {
            delay(workerIdleTimeoutMs)
            workerMutex.withLock {
                val current = activeWorker ?: return@withLock
                if (System.currentTimeMillis() - current.lastUsedAtMs >= workerIdleTimeoutMs) {
                    terminateWorkerLocked(current)
                    activeWorker = null
                }
            }
        }
    }

    private fun formatCrashDiagnostics(prefix: String, session: WorkerSession): String {
        val stderrLines = synchronized(session.recentStderr) { session.recentStderr.toList() }
        return if (stderrLines.isEmpty()) prefix else "$prefix\nStderr:\n${stderrLines.joinToString("\n")}"
    }

    private fun buildWorkerRequestJson(
        text: String,
        outPath: String,
        voiceId: String,
        speed: Float,
        modelPath: String,
        voicesPath: String,
        useCuda: Boolean
    ): String {
        return buildString(text.length + 256) {
            append('{')
            append("\"text\":").append(jsonString(text)).append(',')
            append("\"out_path\":").append(jsonString(outPath)).append(',')
            append("\"voice_id\":").append(jsonString(voiceId)).append(',')
            append("\"speed\":").append(speed).append(',')
            append("\"model_path\":").append(jsonString(modelPath)).append(',')
            append("\"voices_path\":").append(jsonString(voicesPath)).append(',')
            append("\"use_cuda\":").append(useCuda)
            append('}')
        }
    }

    private fun jsonString(value: String): String = buildString(value.length + 2) {
        append('"')
        for (ch in value) {
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                else -> {
                    if (ch < ' ') {
                        append("\\u")
                        append(ch.code.toString(16).padStart(4, '0'))
                    } else {
                        append(ch)
                    }
                }
            }
        }
        append('"')
    }

    private fun isJsonTrue(jsonLine: String, key: String): Boolean {
        val idx = jsonLine.indexOf("\"$key\"")
        if (idx < 0) return false
        val colon = jsonLine.indexOf(':', idx + key.length + 2)
        if (colon < 0) return false
        var pos = colon + 1
        while (pos < jsonLine.length && jsonLine[pos].isWhitespace()) pos++
        return jsonLine.startsWith("true", startIndex = pos)
    }

    private fun extractJsonString(jsonLine: String, key: String): String? {
        val idx = jsonLine.indexOf("\"$key\"")
        if (idx < 0) return null
        val colon = jsonLine.indexOf(':', idx + key.length + 2)
        if (colon < 0) return null
        var pos = colon + 1
        while (pos < jsonLine.length && jsonLine[pos].isWhitespace()) pos++
        if (pos >= jsonLine.length || jsonLine[pos] != '"') return null
        pos++
        val sb = StringBuilder()
        while (pos < jsonLine.length) {
            val c = jsonLine[pos++]
            if (c == '"') return sb.toString()
            if (c == '\\' && pos < jsonLine.length) {
                when (val esc = jsonLine[pos++]) {
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    '/' -> sb.append('/')
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'b' -> sb.append('\b')
                    'f' -> sb.append('\u000C')
                    'u' -> {
                        if (pos + 4 <= jsonLine.length) {
                            val code = jsonLine.substring(pos, pos + 4).toIntOrNull(16)
                            if (code != null) {
                                sb.append(code.toChar())
                                pos += 4
                            }
                        }
                    }
                    else -> sb.append(esc)
                }
            } else {
                sb.append(c)
            }
        }
        return sb.toString()
    }
}
