package com.rio.gamaentity

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings

class AssistantOverlayActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Settings.canDrawOverlays(this)) {
            startService(Intent(this, OverlayService::class.java).apply { action = OverlayService.ACTION_SHOW })
        } else {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                data = android.net.Uri.parse("package:$packageName")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
        finish()
    }
}
