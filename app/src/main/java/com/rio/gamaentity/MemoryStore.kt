package com.rio.gamaentity

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object MemoryStore {
    private fun file(ctx: Context) = File(ctx.filesDir, "gama_facts.json")

    fun load(ctx: Context): JSONArray {
        return try { JSONArray(file(ctx).readText()) } catch (e: Exception) { JSONArray() }
    }

    fun replaceAll(ctx: Context, facts: JSONArray) {
        file(ctx).writeText(facts.toString())
    }

    private fun nextId(facts: JSONArray): Int {
        var m = 0
        for (i in 0 until facts.length()) m = maxOf(m, facts.getJSONObject(i).optInt("id"))
        return m + 1
    }

    fun apply(ctx: Context, ops: JSONArray): Int {
        val facts = load(ctx)
        var changed = 0
        for (i in 0 until ops.length()) {
            val op = ops.optJSONObject(i) ?: continue
            val kind = op.optString("op").uppercase()
            val id = op.optInt("id", -1)
            val text = op.optString("text").trim()
            val now = System.currentTimeMillis()
            if (kind == "ADD" && text.isNotEmpty()) {
                val f = JSONObject()
                f.put("id", nextId(facts))
                f.put("text", text)
                f.put("category", op.optString("category", "general"))
                f.put("updated", now)
                facts.put(f)
                changed++
            } else if (kind == "UPDATE" && text.isNotEmpty()) {
                for (j in 0 until facts.length()) {
                    val f = facts.getJSONObject(j)
                    if (f.optInt("id") == id) { f.put("text", text); f.put("updated", now); changed++ }
                }
            } else if (kind == "DELETE") {
                for (j in facts.length() - 1 downTo 0) {
                    if (facts.getJSONObject(j).optInt("id") == id) { facts.remove(j); changed++ }
                }
            }
        }
        if (changed > 0) replaceAll(ctx, facts)
        return changed
    }

    fun asPrompt(ctx: Context): String {
        val facts = load(ctx)
        val sb = StringBuilder()
        for (i in 0 until facts.length()) {
            val f = facts.getJSONObject(i)
            sb.append("[").append(f.optInt("id")).append("] ").append(f.optString("text")).append("\n")
        }
        return sb.toString()
    }
}
