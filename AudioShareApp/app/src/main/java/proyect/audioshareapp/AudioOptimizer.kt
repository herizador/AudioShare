package com.audioshare.app

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.projection.MediaProjection
import android.media.AudioPlaybackCaptureConfiguration
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi

object AudioOptimizer {
    private const val TAG = "AudioOptimizer"

    const val TARGET_SAMPLE_RATE = 16000
    const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    const val CHANNEL_CONFIG_CAPTURE = AudioFormat.CHANNEL_IN_MONO
    const val CHANNEL_CONFIG_PLAYBACK = AudioFormat.CHANNEL_OUT_MONO

    private const val DESIRED_LATENCY_MS = 20
    val BUFFER_SIZE_BYTES = (TARGET_SAMPLE_RATE * (DESIRED_LATENCY_MS / 1000.0f) * 2).toInt()

    private val PREFERRED_RATES = intArrayOf(44100, 48000, 16000)

    fun findSupportedSampleRate(): Int {
        for (rate in PREFERRED_RATES) {
            val minBuf = AudioRecord.getMinBufferSize(rate, CHANNEL_CONFIG_CAPTURE, ENCODING)
            if (minBuf > 0) {
                Log.d(TAG, "Sample rate $rate Hz es soportado (minBuffer=$minBuf)")
                return rate
            }
            Log.w(TAG, "Sample rate $rate Hz NO es soportado")
        }
        Log.w(TAG, "Ningún sample rate soportado, usando 44100 como fallback")
        return 44100
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    fun createOptimizedAudioRecord(mediaProjection: MediaProjection, sampleRate: Int): AudioRecord {
        val minBufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            CHANNEL_CONFIG_CAPTURE,
            ENCODING
        )
        val actualBufferSize = minBufferSize * 2

        val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val audioRecord = AudioRecord.Builder()
            .setAudioPlaybackCaptureConfig(config)
            .setAudioFormat(AudioFormat.Builder()
                .setEncoding(ENCODING)
                .setSampleRate(sampleRate)
                .setChannelMask(CHANNEL_CONFIG_CAPTURE)
                .build())
            .setBufferSizeInBytes(actualBufferSize)
            .build()

        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            throw IllegalStateException("AudioRecord no se pudo inicializar. Estado: ${audioRecord.state}")
        }

        Log.d(TAG, "AudioRecord creado: ${sampleRate}Hz, buffer=$actualBufferSize")
        return audioRecord
    }

    fun createOptimizedAudioTrack(sampleRate: Int): AudioTrack {
        val minBufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            CHANNEL_CONFIG_PLAYBACK,
            ENCODING
        )
        val actualBufferSize = minBufferSize * 2

        val audioTrack = AudioTrack.Builder()
            .setAudioFormat(AudioFormat.Builder()
                .setEncoding(ENCODING)
                .setSampleRate(sampleRate)
                .setChannelMask(CHANNEL_CONFIG_PLAYBACK)
                .build())
            .setBufferSizeInBytes(actualBufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        if (audioTrack.state != AudioTrack.STATE_INITIALIZED) {
            throw IllegalStateException("AudioTrack no se pudo inicializar. Estado: ${audioTrack.state}")
        }

        Log.d(TAG, "AudioTrack creado: ${sampleRate}Hz, buffer=$actualBufferSize")
        return audioTrack
    }

    fun calculateLatency(bufferSizeBytes: Int, sampleRate: Int): Double {
        val bytesPerSample = 2
        val samplesPerBuffer = bufferSizeBytes / bytesPerSample
        val latencyMs = (samplesPerBuffer.toDouble() / sampleRate) * 1000
        return latencyMs
    }
}
