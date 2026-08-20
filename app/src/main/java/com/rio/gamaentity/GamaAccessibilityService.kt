package com.rio.gamaentity

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent

class GamaAccessibilityService : AccessibilityService() {

    companion object {
        var instance: GamaAccessibilityService? = null
        var pendingWhatsAppSend = false
        var pendingAlarmDismiss = false
        var recordingMode = false
        var recordTarget = "" // "whatsapp" or "alarm"
        var savedWhatsAppTaps = mutableListOf<Pair<Float, Float>>()
        var savedAlarmTaps = mutableListOf<Pair<Float, Float>>()
        var onTapRecorded: ((Float, Float) -> Unit)? = null
    }

    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return

        if (pendingWhatsAppSend && pkg == "com.whatsapp") {
            val taps = savedWhatsAppTaps
            if (taps.isNotEmpty()) {
                replayTaps(taps) {
                    pendingWhatsAppSend = false
                    Handler(Looper.getMainLooper()).postDelayed({
                        val intent = packageManager?.getLaunchIntentForPackage("com.rio.gamaentity")
                        if (intent != null) {
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            startActivity(intent)
                        }
                    }, 800)
                }
            }
        }

        if (pendingAlarmDismiss && (pkg.contains("clock") || pkg.contains("alarm") || pkg.contains("deskclock"))) {
            val taps = savedAlarmTaps
            if (taps.isNotEmpty()) {
                replayTaps(taps) {
                    pendingAlarmDismiss = false
                    Handler(Looper.getMainLooper()).postDelayed({
                        val intent = packageManager?.getLaunchIntentForPackage("com.rio.gamaentity")
                        if (intent != null) {
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            startActivity(intent)
                        }
                    }, 800)
                }
            }
        }
    }

    private fun replayTaps(taps: List<Pair<Float, Float>>, onDone: () -> Unit) {
        if (taps.isEmpty()) { onDone(); return }
        val tap = taps[0]
        val remaining = taps.drop(1)
        val path = Path().apply { moveTo(tap.first, tap.second) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 50)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Handler(Looper.getMainLooper()).postDelayed({
                    replayTaps(remaining, onDone)
                }, 400)
            }
        }, null)
    }

    override fun onInterrupt() { instance = null }
    override fun onDestroy() { instance = null }
}
