package com.rio.gamaentity

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream

class WhisperRecorder {
    private var record: AudioRecord? = null
    private var thread: Thread? = null
    @Volatile private var running = false
    private val pcm = ByteArrayOutputStream()
    private val rate = 16000

    @SuppressLint("MissingPermission")
    fun start() {
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val r = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, min * 2)
        record = r
        pcm.reset()
        running = true
        r.startRecording()
        thread = Thread {
            val buf = ByteArray(min)
            while (running) {
                val n = r.read(buf, 0, buf.size)
                if (n > 0) pcm.write(buf, 0, n)
            }
        }.also { it.start() }
    }

    fun stop(): ByteArray? {
        running = false
        thread?.join()
        record?.stop()
        record?.release()
        record = null
        val data = pcm.toByteArray()
        if (data.size < rate * 2 / 2) return null
        return wav(data)
    }

    private fun wav(d: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        fun i32(v: Int) { out.write(v and 255); out.write(v shr 8 and 255); out.write(v shr 16 and 255); out.write(v shr 24 and 255) }
        fun i16(v: Int) { out.write(v and 255); out.write(v shr 8 and 255) }
        out.write("RIFF".toByteArray()); i32(36 + d.size)
        out.write("WAVE".toByteArray()); out.write("fmt ".toByteArray())
        i32(16); i16(1); i16(1); i32(rate); i32(rate * 2); i16(2); i16(16)
        out.write("data".toByteArray()); i32(d.size)
        out.write(d)
        return out.toByteArray()
    }
}
