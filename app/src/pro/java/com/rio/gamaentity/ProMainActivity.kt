package com.rio.gamaentity

import android.content.Intent
import android.os.Bundle
import android.provider.Settings

class ProMainActivity : MainActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        checkAccessibilityEnabled()
    }

    private fun checkAccessibilityEnabled() {
        if (!GamaAccessibilityService.isEnabled) {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Enable GAMA Pro")
                .setMessage("GAMA Pro needs Accessibility access to read and control your screen.\n\nGo to Settings and enable GAMA Pro Assistant.")
                .setPositiveButton("Open Settings") { _, _ ->
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
                .setNegativeButton("Later") { d, _ -> d.dismiss() }
                .setCancelable(false)
                .show()
        }
    }

    override fun onResume() {
        super.onResume()
        if (GamaAccessibilityService.isEnabled) {
            refreshDrawer()
        }
    }
}
