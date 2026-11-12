package com.rio.gamaentity

import android.os.Handler
import android.os.Looper

class ProOverlayService : OverlayService() {

    override fun getSystemPromptExtra(): String {
        if (!GamaAccessibilityService.isEnabled) return ""
        return " [Navigation commands, output only the command: BACK, HOME, MINIMIZE, SCROLL:UP, SCROLL:DOWN, SCROLL:LEFT, SCROLL:RIGHT, SKIP_AD. For downloading or installing an app output INSTALL_APP:app name. One command per reply. Use only when the user asks to navigate.]"
    }

    override fun handleScreenAction(reply: String): Boolean {
        for (line in reply.split("\n")) {
            val t = line.trim()
            val upper = t.uppercase()
            val query = when {
                upper.startsWith("SPOTIFY:") -> t.substring(8).trim()
                upper.startsWith("YOUTUBE_MUSIC:") -> t.substring(14).trim()
                upper.startsWith("YOUTUBE:") -> t.substring(8).trim()
                else -> ""
            }
            if (query.isNotEmpty()) {
                taskBusy = true
                GamaAccessibilityService.onTaskDone = { Handler(Looper.getMainLooper()).post { resumeAfterTask() } }
                Handler(Looper.getMainLooper()).postDelayed({
                    GamaAccessibilityService.playBestMatch(query, upper.startsWith("SPOTIFY:"))
                }, 1500)
                return false
            }
        }
        for (line in reply.lines()) {
            if (line.trim().uppercase().startsWith("SKIP_AD")) {
                val r = GamaAccessibilityService.skipAd()
                val msg = when (r) {
                    "skipped" -> "Ad skipped"
                    "notyoutube" -> "Open YouTube or YouTube Music first. No ad to skip here"
                    else -> "No ad to skip"
                }
                Handler(Looper.getMainLooper()).post { responseText.text = msg }
                return true
            }
        }
        for (line in reply.lines()) {
            val t = line.trim()
            if (t.uppercase().startsWith("INSTALL_APP:")) {
                val appName = t.substring(12).trim()
                if (appName.isNotEmpty()) {
                    taskBusy = true
                    GamaAccessibilityService.onTaskDone = { Handler(Looper.getMainLooper()).post { resumeAfterTask() } }
                    Handler(Looper.getMainLooper()).postDelayed({ if (taskBusy) resumeAfterTask() }, 25000)
                    startActivity(android.content.Intent(this, ProInstallConfirmActivity::class.java).apply {
                        putExtra("app_name", appName)
                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                    return true
                }
            }
        }
        return handleNavigation(reply)
    }

    private fun handleNavigation(reply: String): Boolean {
        val screenActions = listOf("CLICK:", "SCROLL:", "TYPE:", "BACK", "HOME")
        for (line in reply.split("\n")) {
            val t = line.trim()
            val isAction = screenActions.any { t.startsWith(it) || t == it }
            if (isAction) {
                val executed = GamaAccessibilityService.executeAction(t)
                if (executed) {
                    Handler(Looper.getMainLooper()).postDelayed({
                        startListening()
                    }, 1200)
                    return true
                }
            }
        }
        return false
    }
}
