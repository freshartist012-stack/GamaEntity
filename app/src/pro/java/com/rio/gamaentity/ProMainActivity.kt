package com.rio.gamaentity

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button

class ProMainActivity : MainActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        restoreWake()
        if (!GamaAccessibilityService.isEnabled) {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Enable GAMA Pro")
                .setMessage("GAMA Pro needs Accessibility access to read and control your screen. Go to Settings and enable GAMA Pro Assistant.")
                .setPositiveButton("Open Settings") { _, _ ->
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
                .setNegativeButton("Later") { d, _ -> d.dismiss() }
                .show()
        }
    }

    override fun onResume() {
        super.onResume()
        addAccessibilityButton()
        addWakeButton()
    }

    private fun wakeOn(): Boolean = getSharedPreferences("gama_prefs", MODE_PRIVATE).getBoolean("wake_word_on", false)

    private fun restoreWake() {
        if (wakeOn() && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            try { androidx.core.content.ContextCompat.startForegroundService(this, Intent(this, WakeWordService::class.java)) } catch (e: Exception) {}
        }
    }

    private fun addWakeButton() {
        val label = if (wakeOn()) "Wake word: ON (Hey Assistant)" else "Wake word: OFF"
        val color = if (wakeOn()) 0xFF1A6B3C.toInt() else 0xFF2E5090.toInt()
        val existing = drawerContent.findViewWithTag<Button>("wake_btn")
        if (existing != null) {
            existing.text = label
            existing.setBackgroundColor(color)
            return
        }
        val btn = Button(this).apply {
            tag = "wake_btn"
            text = label
            setBackgroundColor(color)
            setTextColor(0xFFFFFFFF.toInt())
            layoutParams = android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 4, 0, 4) }
            setOnClickListener { toggleWake() }
        }
        drawerContent.addView(btn)
    }

    private fun toggleWake() {
        val p = getSharedPreferences("gama_prefs", MODE_PRIVATE)
        if (wakeOn()) {
            p.edit().putBoolean("wake_word_on", false).apply()
            stopService(Intent(this, WakeWordService::class.java))
            android.widget.Toast.makeText(this, "Wake word off", android.widget.Toast.LENGTH_SHORT).show()
        } else {
            val need = mutableListOf<String>()
            if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) need.add(android.Manifest.permission.RECORD_AUDIO)
            if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) need.add(android.Manifest.permission.POST_NOTIFICATIONS)
            if (need.isNotEmpty()) {
                requestPermissions(need.toTypedArray(), 410)
                android.widget.Toast.makeText(this, "Allow the permissions, then tap again", android.widget.Toast.LENGTH_LONG).show()
                return
            }
            p.edit().putBoolean("wake_word_on", true).apply()
            androidx.core.content.ContextCompat.startForegroundService(this, Intent(this, WakeWordService::class.java))
            android.widget.Toast.makeText(this, "Wake word on. Set GAMA battery to Unrestricted for best results", android.widget.Toast.LENGTH_LONG).show()
        }
        addWakeButton()
    }

    private fun addAccessibilityButton() {
        val existing = drawerContent.findViewWithTag<Button>("accessibility_btn")
        if (existing != null) {
            existing.text = if (GamaAccessibilityService.isEnabled) "✓ Screen Control Active" else "⚠ Enable Screen Control"
            existing.setBackgroundColor(if (GamaAccessibilityService.isEnabled) 0xFF1A6B3C.toInt() else 0xFF880000.toInt())
            return
        }
        val btn = Button(this).apply {
            tag = "accessibility_btn"
            text = if (GamaAccessibilityService.isEnabled) "✓ Screen Control Active" else "⚠ Enable Screen Control"
            setBackgroundColor(if (GamaAccessibilityService.isEnabled) 0xFF1A6B3C.toInt() else 0xFF880000.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            val p = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 4, 0, 4) }
            layoutParams = p
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
        drawerContent.addView(btn)
    }
}
