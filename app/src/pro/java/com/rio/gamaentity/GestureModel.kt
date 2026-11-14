package com.rio.gamaentity

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

object GestureModel {
    const val URL = "https://storage.googleapis.com/mediapipe-models/gesture_recognizer/gesture_recognizer/float16/1/gesture_recognizer.task"
    const val SIZE = 8373440L
    private val client = OkHttpClient.Builder().readTimeout(120, TimeUnit.SECONDS).build()

    fun file(ctx: Context): File = File(ctx.filesDir, "gesture_recognizer.task")

    fun ready(ctx: Context): Boolean {
        val f = file(ctx)
        return f.exists() && f.length() == SIZE
    }

    fun download(ctx: Context, onDone: (Boolean, String?) -> Unit) {
        if (ready(ctx)) { onDone(true, null); return }
        Thread {
            try {
                val tmp = File(ctx.filesDir, "gesture_recognizer.part")
                val res = client.newCall(Request.Builder().url(URL).build()).execute()
                if (!res.isSuccessful) { onDone(false, "Download failed (" + res.code + ")"); return@Thread }
                val body = res.body
                if (body == null) { onDone(false, "Empty download"); return@Thread }
                body.byteStream().use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
                if (tmp.length() != SIZE) { tmp.delete(); onDone(false, "Download incomplete"); return@Thread }
                val dest = file(ctx)
                if (dest.exists()) dest.delete()
                tmp.renameTo(dest)
                onDone(true, null)
            } catch (e: Exception) {
                onDone(false, "No connection")
            }
        }.start()
    }
}
