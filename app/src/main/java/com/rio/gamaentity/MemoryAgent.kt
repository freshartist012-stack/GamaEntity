package com.rio.gamaentity

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object MemoryAgent {
    private val client = OkHttpClient.Builder()
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private fun instructions(): String {
        return buildString {
            appendLine("You are a memory manager for a personal assistant.")
            appendLine("You get the user's stored facts, each with an id, and the latest messages.")
            appendLine("Decide what lasting facts about the user to ADD, UPDATE or DELETE.")
            appendLine("Keep only lasting things: interests, goals, projects, skills, preferences, people, routines.")
            appendLine("Ignore small talk and one-off requests.")
            appendLine("If a new fact changes an existing one, UPDATE it by id. Never add duplicates.")
            appendLine("Each fact is one short sentence.")
            appendLine("Reply with ONLY a JSON array and nothing else.")
            appendLine("Item formats:")
            appendLine("{\"op\":\"ADD\",\"text\":\"...\",\"category\":\"...\"}")
            appendLine("{\"op\":\"UPDATE\",\"id\":3,\"text\":\"...\"}")
            appendLine("{\"op\":\"DELETE\",\"id\":5}")
            appendLine("If nothing should change, reply with [].")
        }
    }

    fun update(ctx: Context, recent: String, onDone: (Int, String?) -> Unit) {
        val prefs = ctx.getSharedPreferences("gama_prefs", Context.MODE_PRIVATE)
        val memKey = prefs.getString("memory_key", "") ?: ""
        val key = if (memKey.isNotEmpty()) memKey else (prefs.getString("groq_key", "") ?: "")
        if (key.isEmpty()) { onDone(0, "Memory: no API key"); return }
        Thread {
            try {
                val facts = MemoryStore.asPrompt(ctx)
                val userMsg = "STORED FACTS:" + System.lineSeparator() + (if (facts.isEmpty()) "(none)" else facts) +
                    System.lineSeparator() + "LATEST MESSAGES:" + System.lineSeparator() + recent
                val msgs = JSONArray()
                msgs.put(JSONObject().put("role", "system").put("content", instructions()))
                msgs.put(JSONObject().put("role", "user").put("content", userMsg))
                val body = JSONObject()
                    .put("model", "openai/gpt-oss-120b")
                    .put("messages", msgs)
                    .put("max_tokens", 4000)
                    .put("tool_choice", "none")
                val req = Request.Builder()
                    .url("https://api.groq.com/openai/v1/chat/completions")
                    .addHeader("Authorization", "Bearer " + key)
                    .addHeader("Content-Type", "application/json")
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                val res = client.newCall(req).execute()
                val text = res.body?.string() ?: ""
                if (res.code == 429) { onDone(0, "Memory: rate limit reached"); return@Thread }
                val json = JSONObject(text)
                if (json.has("error")) {
                    onDone(0, "Memory: " + (json.optJSONObject("error")?.optString("message", "error")?.take(100) ?: "error"))
                    return@Thread
                }
                val reply = json.getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content", "")
                val s = reply.indexOf('[')
                val e = reply.lastIndexOf(']')
                if (s < 0 || e <= s) { onDone(0, "Memory: reply had no list"); return@Thread }
                val ops = JSONArray(reply.substring(s, e + 1))
                onDone(MemoryStore.apply(ctx, ops), null)
            } catch (ex: Exception) {
                onDone(0, "Memory: " + (ex.message ?: "failed").take(80))
            }
        }.start()
    }
}
