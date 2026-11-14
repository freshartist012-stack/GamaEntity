package com.rio.gamaentity

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.TextView

class ProInstallConfirmActivity : Activity() {
    private var voice: ConfirmVoice? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val name = intent.getStringExtra("app_name") ?: ""
        if (name.isEmpty()) { finish(); return }
        val status = TextView(this)
        status.text = "Listening. Say proceed or cancel"
        status.textSize = 12f
        status.setPadding(48, 8, 48, 0)
        val dlg = AlertDialog.Builder(this)
            .setTitle("Install " + name + "?")
            .setMessage("GAMA will search Play Store for " + name + " and tap Install. Check that it is the right app before you confirm.")
            .setView(status)
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
            .create()
        voice = ConfirmVoice(this, { kind, _ ->
            runOnUiThread {
                when (kind) {
                    ConfirmVoice.Kind.CONFIRM -> dlg.getButton(AlertDialog.BUTTON_POSITIVE)?.performClick()
                    ConfirmVoice.Kind.CANCEL -> { dlg.dismiss(); finish() }
                    ConfirmVoice.Kind.EDIT -> { status.text = "Say proceed or cancel"; voice?.listenAgain() }
                }
            }
        }, { runOnUiThread { status.text = "Tap here to speak" } })
        status.setOnClickListener { status.text = "Listening..."; voice?.start() }
        dlg.setOnDismissListener { voice?.stop() }
        dlg.show()
        voice?.start()
    }

    override fun onDestroy() {
        voice?.stop()
        super.onDestroy()
    }
}
