package org.bible.reading.tts

import de.sciss.jump3r.lowlevel.LameEncoder
import java.io.File
import java.io.FileOutputStream
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem

/**
 * Pure-JVM MP3 encoder for converting TTS WAV output into CBR 128kbps MP3 files.
 */
object TtsMp3Encoder {

    const val DEFAULT_BITRATE_KBPS = 128

    /**
     * Encodes [sourceWav] into a CBR [bitrateKbps] kbps MP3 at [targetMp3].
     */
    fun encodeWavToMp3(
        sourceWav: File,
        targetMp3: File,
        bitrateKbps: Int = DEFAULT_BITRATE_KBPS
    ): Result<File> {
        return try {
            targetMp3.parentFile?.mkdirs()
            if (targetMp3.exists()) {
                targetMp3.delete()
            }

            AudioSystem.getAudioInputStream(sourceWav).use { rawStream ->
                val srcFormat = rawStream.format
                val encodeStream = if (
                    srcFormat.encoding == AudioFormat.Encoding.PCM_SIGNED ||
                    srcFormat.encoding == AudioFormat.Encoding.PCM_FLOAT
                ) {
                    rawStream
                } else {
                    val pcm16Format = AudioFormat(
                        AudioFormat.Encoding.PCM_SIGNED,
                        srcFormat.sampleRate,
                        16,
                        srcFormat.channels,
                        srcFormat.channels * 2,
                        srcFormat.sampleRate,
                        false
                    )
                    AudioSystem.getAudioInputStream(pcm16Format, rawStream)
                }

                encodeStream.use { pcmStream ->
                    val format = pcmStream.format
                    val channelMode = if (format.channels == 1) {
                        LameEncoder.CHANNEL_MODE_MONO
                    } else {
                        LameEncoder.CHANNEL_MODE_AUTO
                    }

                    val encoder = LameEncoder(
                        format,
                        bitrateKbps,
                        channelMode,
                        LameEncoder.QUALITY_HIGH,
                        false // Constant Bit Rate (CBR) 128kbps
                    )

                    try {
                        val pcmBuffer = ByteArray(encoder.getPCMBufferSize())
                        val mp3Buffer = ByteArray(encoder.getMP3BufferSize())
                        val frameSize = format.frameSize.coerceAtLeast(1)

                        FileOutputStream(targetMp3).buffered().use { out ->
                            var leftover = 0
                            while (true) {
                                val bytesRead = pcmStream.read(pcmBuffer, leftover, pcmBuffer.size - leftover)
                                if (bytesRead < 0) {
                                    val aligned = leftover - (leftover % frameSize)
                                    if (aligned > 0) {
                                        val encoded = encoder.encodeBuffer(pcmBuffer, 0, aligned, mp3Buffer)
                                        if (encoded > 0) {
                                            out.write(mp3Buffer, 0, encoded)
                                        }
                                    }
                                    break
                                }

                                val totalAvailable = leftover + bytesRead
                                val aligned = totalAvailable - (totalAvailable % frameSize)
                                if (aligned > 0) {
                                    val encoded = encoder.encodeBuffer(pcmBuffer, 0, aligned, mp3Buffer)
                                    if (encoded > 0) {
                                        out.write(mp3Buffer, 0, encoded)
                                    }
                                }
                                leftover = totalAvailable - aligned
                                if (leftover > 0) {
                                    System.arraycopy(pcmBuffer, aligned, pcmBuffer, 0, leftover)
                                }
                            }

                            val finalBytes = encoder.encodeFinish(mp3Buffer)
                            if (finalBytes > 0) {
                                out.write(mp3Buffer, 0, finalBytes)
                            }
                            out.flush()
                        }
                    } finally {
                        encoder.close()
                    }
                }
            }

            if (targetMp3.exists() && targetMp3.length() > 0) {
                Result.success(targetMp3)
            } else {
                targetMp3.delete()
                Result.failure(RuntimeException("Encoded MP3 output is empty"))
            }
        } catch (e: Exception) {
            targetMp3.delete()
            Result.failure(e)
        }
    }
}
