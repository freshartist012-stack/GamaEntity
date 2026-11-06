package com.rio.gamaentity

import android.content.Context
import java.io.File

object ProjectStore {
    private fun dir(ctx: Context): File {
        val d = File(ctx.filesDir, "projects")
        if (!d.exists()) d.mkdirs()
        return d
    }

    fun extractHtml(reply: String): String? {
        val lower = reply.lowercase()
        var s = lower.indexOf("<!doctype")
        if (s < 0) s = lower.indexOf("<html")
        if (s < 0) return null
        val e = lower.lastIndexOf("</html>")
        if (e < s) return null
        return reply.substring(s, e + 7)
    }

    fun nameFrom(html: String): String {
        val lower = html.lowercase()
        val s = lower.indexOf("<title>")
        val e = lower.indexOf("</title>")
        if (s >= 0 && e > s) {
            val t = html.substring(s + 7, e).trim()
            if (t.isNotEmpty()) return t.take(40)
        }
        return "project " + System.currentTimeMillis().toString().takeLast(5)
    }

    private fun slug(name: String): String {
        val x = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
        return if (x.isEmpty()) "project" else x.take(40)
    }

    fun save(ctx: Context, name: String, html: String): String {
        val id = slug(name)
        File(dir(ctx), id + ".html").writeText(html)
        return id
    }

    fun list(ctx: Context): List<String> {
        val files = dir(ctx).listFiles()?.filter { it.name.endsWith(".html") } ?: return emptyList()
        return files.sortedByDescending { it.lastModified() }.map { it.name.removeSuffix(".html") }
    }

    fun load(ctx: Context, id: String): String? {
        val f = File(dir(ctx), id + ".html")
        return if (f.exists()) f.readText() else null
    }

    fun delete(ctx: Context, id: String) {
        File(dir(ctx), id + ".html").delete()
    }
}
