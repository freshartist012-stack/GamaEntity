package com.rio.gamaentity

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

object WhisperClient {
    private val client = OkHttpClient()

    fun transcribe(wav: ByteArray, langCode: String, key: String, onResult: (String?, String?) -> Unit) {
        Thread {
            try {
                val body = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("model", "whisper-large-v3")
                    .addFormDataPart("language", langCode)
                    .addFormDataPart("response_format", "json")
                    .addFormDataPart("file", "audio.wav", wav.toRequestBody("audio/wav".toMediaType()))
                    .build()
                val req = Request.Builder()
                    .url("https://api.groq.com/openai/v1/audio/transcriptions")
                    .addHeader("Authorization", "Bearer " + key)
                    .post(body)
                    .build()
                val res = client.newCall(req).execute()
                val text = res.body?.string() ?: ""
                if (res.code == 429) {
                    onResult(null, "Voice limit reached. Try again in a moment or type.")
                    return@Thread
                }
                val json = JSONObject(text)
                if (json.has("error")) {
                    onResult(null, "Voice error. Try again.")
                    return@Thread
                }
                val out = json.optString("text", "").trim()
                if (out.isEmpty() || out.length < 2) onResult(null, "Didn't catch that.") else onResult(out, null)
            } catch (e: Exception) {
                onResult(null, "Connection error.")
            }
        }.start()
    }
}
