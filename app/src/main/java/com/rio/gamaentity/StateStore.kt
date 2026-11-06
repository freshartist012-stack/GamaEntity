package com.rio.gamaentity

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object StateStore {
    private fun dir(ctx: Context): File {
        val d = File(ctx.filesDir, "states")
        if (!d.exists()) d.mkdirs()
        return d
    }

    private fun file(ctx: Context, id: String) = File(dir(ctx), id + ".json")

    fun load(ctx: Context, id: String): JSONArray {
        return try { JSONArray(file(ctx, id).readText()) } catch (e: Exception) { JSONArray() }
    }

    fun append(ctx: Context, id: String, role: String, content: String) {
        if (id.isEmpty()) return
        val arr = load(ctx, id)
        val m = JSONObject()
        m.put("role", role)
        m.put("content", content)
        m.put("time", System.currentTimeMillis())
        arr.put(m)
        file(ctx, id).writeText(arr.toString())
    }

    fun ids(ctx: Context): List<String> {
        val files = dir(ctx).listFiles()?.filter { it.name.endsWith(".json") } ?: return emptyList()
        return files.sortedByDescending { it.lastModified() }.map { it.name.removeSuffix(".json") }
    }

    fun preview(ctx: Context, id: String): String {
        val arr = load(ctx, id)
        for (i in 0 until arr.length()) {
            val m = arr.getJSONObject(i)
            if (m.optString("role") == "user") return m.optString("content").take(50)
        }
        return "(empty)"
    }

    fun count(ctx: Context, id: String): Int = load(ctx, id).length()
}
