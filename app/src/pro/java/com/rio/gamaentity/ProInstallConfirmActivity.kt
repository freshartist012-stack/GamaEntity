package com.rio.gamaentity

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle

class ProInstallConfirmActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val name = intent.getStringExtra("app_name") ?: ""
        if (name.isEmpty()) { finish(); return }
        AlertDialog.Builder(this)
            .setTitle("Install " + name + "?")
            .setMessage("GAMA will search Play Store for " + name + " and tap Install. Check that it is the right app before you confirm.")
            .setPositiveButton("Install") { _, _ ->
                try {
                    val i = Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=" + Uri.encode(name) + "&c=apps"))
                    i.setPackage("com.android.vending")
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(i)
                    GamaAccessibilityService.installFromStore(name)
                } catch (e: Exception) {}
                finish()
            }
            .setNegativeButton("Cancel") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }
}
