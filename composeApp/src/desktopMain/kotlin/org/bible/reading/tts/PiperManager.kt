package org.bible.reading.tts

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

class PiperManager(private val appDir: File) {

    val ttsDir: File = File(appDir, "tts").apply { mkdirs() }
    val piperDir: File = File(ttsDir, "piper").apply { mkdirs() }
    val voicesDir: File = File(ttsDir, "voices").apply { mkdirs() }
    val cacheDir: File = File(ttsDir, "cache").apply { mkdirs() }

    private val osName = System.getProperty("os.name").lowercase()
    private val osArch = System.getProperty("os.arch").lowercase()

    val isWindows = osName.contains("win")
    val isMac = osName.contains("mac") || osName.contains("darwin")
    val isLinux = osName.contains("linux") || osName.contains("nix")

    val isArm64 = osArch.contains("aarch64") || osArch.contains("arm64")

    /**
     * Resolves the executable binary for Piper:
     * 1. Check local app dir (~/.bibleReadingPlan/tts/piper/piper)
     * 2. Check system PATH ('piper')
     */
    fun findExecutable(): File? {
        val binaryName = if (isWindows) "piper.exe" else "piper"

        // 1. App-local directory
        val localBinary = File(piperDir, binaryName)
        if (localBinary.exists() && localBinary.canExecute()) {
            return localBinary
        }
        // Sometimes extracted into a subfolder "piper/piper"
        val nestedBinary = File(File(piperDir, "piper"), binaryName)
        if (nestedBinary.exists() && nestedBinary.canExecute()) {
            return nestedBinary
        }

        // 2. Check PATH
        val pathDirs = System.getenv("PATH")?.split(File.pathSeparator) ?: emptyList()
        for (dir in pathDirs) {
            val f = File(dir, binaryName)
            if (f.exists() && f.canExecute()) {
                return f
            }
        }

        return null
    }

    fun isBinaryInstalled(): Boolean = findExecutable() != null

    /**
     * Returns the release download URL for Piper standalone binary on this platform.
     */
    fun getBinaryDownloadUrl(): String? {
        return when {
            isLinux && !isArm64 -> "https://github.com/rhasspy/piper/releases/download/2023.11.14-2/piper_linux_x86_64.tar.gz"
            isLinux && isArm64 -> "https://github.com/rhasspy/piper/releases/download/2023.11.14-2/piper_linux_aarch64.tar.gz"
            isMac && isArm64 -> "https://github.com/rhasspy/piper/releases/download/2023.11.14-2/piper_macos_aarch64.tar.gz"
            isMac && !isArm64 -> "https://github.com/rhasspy/piper/releases/download/2023.11.14-2/piper_macos_x64.tar.gz"
            isWindows -> "https://github.com/rhasspy/piper/releases/download/2023.11.14-2/piper_windows_amd64.zip"
            else -> null
        }
    }

    /**
     * Downloads and extracts Piper standalone binary into ~/.bibleReadingPlan/tts/piper
     */
    suspend fun downloadBinary(onProgress: (Float) -> Unit = {}): Result<File> = withContext(Dispatchers.IO) {
        val downloadUrl = getBinaryDownloadUrl()
            ?: return@withContext Result.failure(IllegalStateException("Unsupported OS/architecture: $osName $osArch"))

        val isZip = downloadUrl.endsWith(".zip")
        val tempArchive = File(ttsDir, "piper_download_temp" + (if (isZip) ".zip" else ".tar.gz"))

        try {
            downloadWithProgress(downloadUrl, tempArchive, onProgress)

            if (isZip) {
                extractZip(tempArchive, piperDir)
            } else {
                extractTarGz(tempArchive, piperDir)
            }

            // Set executable permission on binaries
            val binary = findExecutable()
            if (binary != null && !isWindows) {
                binary.setExecutable(true, false)
                val phonemize = File(binary.parentFile, "piper_phonemize")
                if (phonemize.exists()) phonemize.setExecutable(true, false)
            }

            tempArchive.delete()

            val resolved = findExecutable()
            if (resolved != null) {
                Result.success(resolved)
            } else {
                Result.failure(IllegalStateException("Piper extracted successfully but executable not found."))
            }
        } catch (e: Exception) {
            tempArchive.delete()
            Result.failure(e)
        }
    }

    /**
     * Checks if a voice model and its configuration file are fully downloaded.
     */
    fun isVoiceDownloaded(voice: TtsVoice): Boolean {
        if (voice.modelFileName.isBlank() || voice.configFileName.isBlank()) return false
        val model = File(voicesDir, voice.modelFileName)
        val config = File(voicesDir, voice.configFileName)
        return model.exists() && model.length() > 1024 && config.exists() && config.length() > 10
    }

    /**
     * Downloads voice model .onnx and .onnx.json into ~/.bibleReadingPlan/tts/voices
     */
    suspend fun downloadVoice(voice: TtsVoice, onProgress: (Float) -> Unit = {}): Result<Unit> = withContext(Dispatchers.IO) {
        if (voice.downloadUrl.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Voice has no download URL"))
        }

        val modelFile = File(voicesDir, voice.modelFileName)
        val configFile = File(voicesDir, voice.configFileName)
        val tempModel = File(voicesDir, "${voice.modelFileName}.tmp")
        val tempConfig = File(voicesDir, "${voice.configFileName}.tmp")

        try {
            // Download model (accounts for 95% of progress)
            downloadWithProgress(voice.downloadUrl, tempModel) { frac ->
                onProgress(frac * 0.95f)
            }

            // Download config (remaining 5%)
            if (voice.configDownloadUrl.isNotBlank()) {
                downloadWithProgress(voice.configDownloadUrl, tempConfig) { frac ->
                    onProgress(0.95f + (frac * 0.05f))
                }
            }

            tempModel.renameTo(modelFile)
            if (tempConfig.exists()) {
                tempConfig.renameTo(configFile)
            }

            onProgress(1.0f)
            Result.success(Unit)
        } catch (e: Exception) {
            tempModel.delete()
            tempConfig.delete()
            Result.failure(e)
        }
    }

    /**
     * Deletes a downloaded voice model.
     */
    fun deleteVoice(voice: TtsVoice): Boolean {
        val model = File(voicesDir, voice.modelFileName)
        val config = File(voicesDir, voice.configFileName)
        val mDel = if (model.exists()) model.delete() else true
        val cDel = if (config.exists()) config.delete() else true
        return mDel && cDel
    }

    /**
     * Synthesizes text into a WAV file using Piper.
     */
    suspend fun synthesize(
        text: String,
        outputWav: File,
        voice: TtsVoice,
        speechRate: Float = 1.0f,
        sentenceSilence: Float = 0.2f
    ): Result<File> = withContext(Dispatchers.IO) {
        val executable = findExecutable()
            ?: return@withContext Result.failure(IllegalStateException("Piper binary not found. Please install or download Piper."))

        val modelFile = File(voicesDir, voice.modelFileName)
        val configFile = File(voicesDir, voice.configFileName)
        if (!modelFile.exists()) {
            return@withContext Result.failure(IllegalStateException("Voice model ${voice.name} is not downloaded."))
        }

        outputWav.parentFile?.mkdirs()
        if (outputWav.exists()) outputWav.delete()

        // In Piper, length_scale < 1.0 speeds up speech, > 1.0 slows it down
        val clampedRate = speechRate.coerceIn(0.6f, 2.0f)
        val lengthScale = 1.0f / clampedRate

        val binDir = executable.parentFile
        val espeakDataDir = File(binDir, "espeak-ng-data")

        val cmd = mutableListOf<String>().apply {
            add(executable.absolutePath)
            add("-m")
            add(modelFile.absolutePath)
            if (configFile.exists()) {
                add("-c")
                add(configFile.absolutePath)
            }
            add("-f")
            add(outputWav.absolutePath)
            add("--length_scale")
            add(String.format(java.util.Locale.US, "%.2f", lengthScale))
            add("--sentence_silence")
            add(String.format(java.util.Locale.US, "%.2f", sentenceSilence.coerceIn(0.0f, 1.0f)))
            if (espeakDataDir.exists() && espeakDataDir.isDirectory) {
                add("--espeak_data")
                add(espeakDataDir.absolutePath)
            }
        }

        try {
            val processBuilder = ProcessBuilder(cmd)
            processBuilder.directory(binDir)

            // Setup shared library path for onnxruntime and piper_phonemize
            val env = processBuilder.environment()
            val existingLd = env["LD_LIBRARY_PATH"]
            val binDirPath = binDir.absolutePath
            env["LD_LIBRARY_PATH"] = if (existingLd.isNullOrBlank()) binDirPath else "$binDirPath:$existingLd"
            val existingDyld = env["DYLD_LIBRARY_PATH"]
            env["DYLD_LIBRARY_PATH"] = if (existingDyld.isNullOrBlank()) binDirPath else "$binDirPath:$existingDyld"

            val process = processBuilder.start()

            // Feed text into stdin
            process.outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.write(text)
                writer.flush()
            }

            val exitCode = process.waitFor()
            if (exitCode != 0) {
                val errText = process.errorStream.bufferedReader().readText()
                return@withContext Result.failure(RuntimeException("Piper exited with code $exitCode: $errText"))
            }

            if (!outputWav.exists() || outputWav.length() == 0L) {
                return@withContext Result.failure(RuntimeException("Piper finished but output audio file is empty or missing."))
            }

            Result.success(outputWav)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun downloadWithProgress(
        urlStr: String,
        targetFile: File,
        onProgress: (Float) -> Unit
    ) {
        val url = URL(urlStr)
        var connection = url.openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connectTimeout = 15000
        connection.readTimeout = 30000

        var status = connection.responseCode
        if (status == HttpURLConnection.HTTP_MOVED_TEMP ||
            status == HttpURLConnection.HTTP_MOVED_PERM ||
            status == 307 || status == 308
        ) {
            val newUrl = connection.getHeaderField("Location")
            connection = URL(newUrl).openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = true
            connection.connectTimeout = 15000
            connection.readTimeout = 30000
            status = connection.responseCode
        }

        if (status !in 200..299) {
            throw IllegalStateException("Failed to download: HTTP $status from $urlStr")
        }

        val totalLength = connection.contentLengthLong
        targetFile.parentFile?.mkdirs()

        connection.inputStream.use { input ->
            FileOutputStream(targetFile).use { output ->
                val buffer = ByteArray(32 * 1024)
                var bytesRead: Int
                var totalRead = 0L

                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    totalRead += bytesRead
                    if (totalLength > 0) {
                        onProgress((totalRead.toFloat() / totalLength).coerceIn(0f, 1f))
                    }
                }
                output.flush()
            }
        }
    }

    private fun extractTarGz(tarGzFile: File, outputDir: File) {
        outputDir.mkdirs()
        GzipCompressorInputStream(BufferedInputStream(tarGzFile.inputStream())).use { gzIn ->
            TarArchiveInputStream(gzIn).use { tarIn ->
                var entry = tarIn.nextEntry
                while (entry != null) {
                    val outputFile = File(outputDir, entry.name)
                    if (entry.isDirectory) {
                        outputFile.mkdirs()
                    } else {
                        outputFile.parentFile?.mkdirs()
                        FileOutputStream(outputFile).use { out ->
                            tarIn.copyTo(out)
                        }
                        if (entry.mode and 0b001001001 != 0) {
                            outputFile.setExecutable(true, false)
                        }
                    }
                    entry = tarIn.nextEntry
                }
            }
        }
    }

    private fun extractZip(zipFile: File, outputDir: File) {
        outputDir.mkdirs()
        ZipInputStream(BufferedInputStream(zipFile.inputStream())).use { zipIn ->
            var entry = zipIn.nextEntry
            while (entry != null) {
                val outputFile = File(outputDir, entry.name)
                if (entry.isDirectory) {
                    outputFile.mkdirs()
                } else {
                    outputFile.parentFile?.mkdirs()
                    FileOutputStream(outputFile).use { out ->
                        zipIn.copyTo(out)
                    }
                }
                zipIn.closeEntry()
                entry = zipIn.nextEntry
            }
        }
    }
}
