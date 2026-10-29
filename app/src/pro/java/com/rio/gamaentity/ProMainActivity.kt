package com.rio.gamaentity

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button

class ProMainActivity : MainActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
