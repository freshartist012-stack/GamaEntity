package com.rio.gamaentity

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object ConfirmEdit {
    private val client = OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).build()

    fun editWhatsApp(key: String, spoken: String, contact: String, message: String, done: (String, String) -> Unit) {
        if (key.isEmpty()) { done("", ""); return }
        Thread {
            try {
                val ls = System.lineSeparator()
                val sys = "You edit a WhatsApp message draft. You get the current recipient name, the current message, and what the user said. Reply with ONLY a JSON object with two keys: contact (a new recipient name only if the user asked to change the recipient, otherwise an empty string) and message (the full updated message text, or the unchanged message if the user did not ask to change it). No other text."
                val usr = "Recipient: " + contact + ls + "Message: " + message + ls + "User said: " + spoken
                val msgs = JSONArray()
                msgs.put(JSONObject().put("role", "system").put("content", sys))
                msgs.put(JSONObject().put("role", "user").put("content", usr))
                val body = JSONObject().put("model", "openai/gpt-oss-120b").put("messages", msgs).put("max_tokens", 3000).put("tool_choice", "none")
                val req = Request.Builder()
                    .url("https://api.groq.com/openai/v1/chat/completions")
                    .addHeader("Authorization", "Bearer " + key)
                    .addHeader("Content-Type", "application/json")
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                val res = client.newCall(req).execute()
                val text = res.body?.string() ?: ""
                val reply = JSONObject(text).getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content", "")
                val s = reply.indexOf('{')
                val e = reply.lastIndexOf('}')
                if (s < 0 || e <= s) { done("", ""); return@Thread }
                val j = JSONObject(reply.substring(s, e + 1))
                done(j.optString("contact", "").trim(), j.optString("message", "").trim())
            } catch (ex: Exception) {
                done("", "")
            }
        }.start()
    }
}
