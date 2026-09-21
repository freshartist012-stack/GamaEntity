package com.rio.gamaentity

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AlertDialog

class AssistantOverlayActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Small floating window
        window.setLayout(
            (resources.displayMetrics.widthPixels * 0.92).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT
        )
        window.setGravity(Gravity.BOTTOM)
        window.setBackgroundDrawableResource(android.R.color.transparent)
        setFinishOnTouchOutside(false)

        val command = intent?.getStringExtra("execute_command")

        if (command != null) {
            handleCommand(command)
        } else if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                data = Uri.parse("package:$packageName")
            })
            finish()
        } else {
            startService(Intent(this, OverlayService::class.java).apply {
                action = OverlayService.ACTION_SHOW
            })
            finish()
        }
    }

    private fun handleCommand(reply: String) {
        for (line in reply.split("\n")) {
            val t = line.trim()

            Regex("(?i)WHATSAPP:([^:]+):(.+)").find(t)?.let {
                val raw = it.groupValues[1].trim()
                val message = it.groupValues[2].trim()
                val number = lookupContact(raw)
                showWhatsAppConfirmation(raw, number, message)
                return
            }

            Regex("(?i)PLEASE_CALL:([^:]+)(?::(.+))?").find(t)?.let {
                val contactName = it.groupValues[1].trim()
                val network = it.groupValues[2].trim()
                showPleaseCallConfirmation(contactName, network)
                return
            }

            Regex("(?i)CALL:([^\n]+)").find(t)?.let {
                val raw = it.groupValues[1].trim()
                val number = lookupContact(raw)
                showCallConfirmation(raw, number)
                return
            }

            val gmailMatch = Regex("(?i)GMAIL:([^:\\n]+):([^:\\n]+):(.+)", setOf(RegexOption.DOT_MATCHES_ALL)).find(reply)
                ?: Regex("(?i)GMAIL:([^:\\n]+):(.+)", setOf(RegexOption.DOT_MATCHES_ALL)).find(reply)
            gmailMatch?.let {
                val to = it.groupValues[1].trim()
                val subject = if (it.groupValues.size > 3 && it.groupValues[3].isNotEmpty())
                    it.groupValues[2].trim() else "Message"
                val body = if (it.groupValues.size > 3 && it.groupValues[3].isNotEmpty())
                    it.groupValues[3].trim() else it.groupValues[2].trim()
                showEmailConfirmation(to, subject, body)
                return
            }
        }
        finish()
    }

    private fun showWhatsAppConfirmation(contactName: String, number: String, message: String) {
        val contacts = getContactsList()
        val names = contacts.map { it.first }.toTypedArray()
        var selectedNumber = number
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 16, 48, 0)
        }

        val nameView = TextView(this).apply { text = "To: $contactName"; textSize = 15f }
        layout.addView(nameView)
        layout.addView(TextView(this).apply { text = "Change contact:"; textSize = 12f; setPadding(0,8,0,4) })

        val spinner = Spinner(this)
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, names)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter
        val defaultIdx = contacts.indexOfFirst {
            val cNum = it.second.replace("[^\\d]".toRegex(), "")
            val rNum = number.replace("[^\\d]".toRegex(), "")
            cNum.takeLast(7) == rNum.takeLast(7) || it.first.lowercase().contains(contactName.lowercase())
        }
        if (defaultIdx >= 0) spinner.setSelection(defaultIdx)
        spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                selectedNumber = formatNumber(contacts[pos].second)
                nameView.text = "To: ${contacts[pos].first}"
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }
        layout.addView(spinner)

        val msgInput = EditText(this).apply { setText(message); textSize = 14f }
        layout.addView(TextView(this).apply { text = "Message:"; textSize = 12f; setPadding(0,12,0,4) })
        layout.addView(msgInput)

        AlertDialog.Builder(this)
            .setTitle("Send WhatsApp Message?")
            .setView(layout)
            .setCancelable(false)
            .setPositiveButton("Send") { _, _ ->
                val finalMessage = msgInput.text.toString().trim()
                val uri = Uri.parse("https://api.whatsapp.com/send?phone=$selectedNumber&text=${Uri.encode(finalMessage)}")
                try { startActivity(Intent(Intent.ACTION_VIEW, uri).apply { setPackage("com.whatsapp") }) }
                catch (e: Exception) { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                finish()
            }
            .setNegativeButton("Cancel") { _, _ -> finish() }
            .show()
    }

    private fun showPleaseCallConfirmation(contactName: String, specifiedNetwork: String) {
        val networks = arrayOf("MTN", "Vodacom", "Telkom", "Cell C")
        val ussdCodes = mapOf("MTN" to "*121*", "Vodacom" to "*140*", "Telkom" to "*140*", "Cell C" to "*111*")
        val number = lookupContact(contactName)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 16, 48, 0)
        }
        layout.addView(TextView(this).apply { text = "Please call: $contactName"; textSize = 15f })
        layout.addView(TextView(this).apply { text = "Your network:"; textSize = 12f; setPadding(0, 12, 0, 4) })

        val networkSpinner = Spinner(this)
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, networks)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        networkSpinner.adapter = adapter
        if (specifiedNetwork.isNotEmpty()) {
            val idx = networks.indexOfFirst { it.lowercase().contains(specifiedNetwork.lowercase()) }
            if (idx >= 0) networkSpinner.setSelection(idx)
        }
        layout.addView(networkSpinner)

        AlertDialog.Builder(this)
            .setTitle("Send Please Call?")
            .setView(layout)
            .setCancelable(false)
            .setPositiveButton("Send") { _, _ ->
                val network = networks[networkSpinner.selectedItemPosition]
                val ussd = ussdCodes[network] ?: "*140*"
                val cleanNumber = number.replace("[^\\d]".toRegex(), "").let {
                    if (it.startsWith("27") && it.length == 11) "0${it.substring(2)}" else it
                }
                val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$ussd$cleanNumber%23"))
                try { startActivity(intent) } catch (e: Exception) {}
                finish()
            }
            .setNegativeButton("Cancel") { _, _ -> finish() }
            .show()
    }

    private fun showEmailConfirmation(to: String, subject: String, body: String) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 16, 48, 0)
        }

        val toInput = EditText(this).apply { setText(to) }
        val subjectInput = EditText(this).apply { setText(subject) }
        val bodyInput = EditText(this).apply { setText(body); minLines = 3 }

        layout.addView(TextView(this).apply { text = "To:"; textSize = 12f })
        layout.addView(toInput)
        layout.addView(TextView(this).apply { text = "Subject:"; textSize = 12f })
        layout.addView(subjectInput)
        layout.addView(TextView(this).apply { text = "Message:"; textSize = 12f })
        layout.addView(bodyInput)

        AlertDialog.Builder(this)
            .setTitle("Send Email?")
            .setView(layout)
            .setCancelable(false)
            .setPositiveButton("Send") { _, _ ->
                startActivity(Intent(Intent.ACTION_VIEW,
                    Uri.parse("mailto:${toInput.text}?subject=${Uri.encode(subjectInput.text.toString())}&body=${Uri.encode(bodyInput.text.toString())}")))
                finish()
            }
            .setNegativeButton("Cancel") { _, _ -> finish() }
            .show()
    }

    private fun getContactsList(): List<Pair<String, String>> {
        val contacts = mutableListOf<Pair<String, String>>()
        try {
            contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER),
                null, null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
            )?.use {
                while (it.moveToNext()) {
                    val name = it.getString(0) ?: continue
                    val number = it.getString(1) ?: continue
                    contacts.add(Pair(name, number))
                }
            }
        } catch (e: Exception) {}
        return contacts
    }

    private fun showCallConfirmation(raw: String, number: String) {
        val contacts = getContactsList()
        val numbers = contacts.map { "${it.first}: ${it.second}" }.toTypedArray()
        var selectedNumber = number

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 16, 48, 0)
        }
        layout.addView(TextView(this).apply { text = "Calling: $raw"; textSize = 15f })
        layout.addView(TextView(this).apply { text = "Change number:"; textSize = 12f; setPadding(0,8,0,4) })

        val spinner = Spinner(this)
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, numbers)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter
        val defaultIdx = contacts.indexOfFirst {
            val cNum = it.second.replace("[^\\d]".toRegex(), "")
            val rNum = number.replace("[^\\d]".toRegex(), "")
            cNum.takeLast(7) == rNum.takeLast(7) || it.first.lowercase().contains(raw.lowercase())
        }
        if (defaultIdx >= 0) spinner.setSelection(defaultIdx)
        spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                selectedNumber = formatNumber(contacts[pos].second)
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }
        layout.addView(spinner)

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Make Call?")
            .setView(layout)
            .setCancelable(false)
            .setPositiveButton("Call") { _, _ ->
                try { startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:$selectedNumber"))) }
                catch (e: Exception) { startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$selectedNumber"))) }
                finish()
            }
            .setNegativeButton("Cancel") { _, _ -> finish() }
            .show()
    }

    private fun lookupContact(nameOrNumber: String): String {
        val digits = nameOrNumber.replace("[^\\d]".toRegex(), "")
        if (digits.length >= 7) return formatNumber(nameOrNumber)
        try {
            contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER),
                null, null, null
            )?.use {
                while (it.moveToNext()) {
                    val name = it.getString(0) ?: continue
                    val number = it.getString(1) ?: continue
                    if (name.lowercase().contains(nameOrNumber.lowercase())) return formatNumber(number)
                }
            }
        } catch (e: Exception) {}
        return nameOrNumber
    }

    private fun formatNumber(raw: String): String {
        val d = raw.replace("[^\\d]".toRegex(), "")
        return when {
            d.startsWith("27") && d.length >= 11 -> d
            d.startsWith("0") && d.length == 10 -> "27${d.substring(1)}"
            d.length == 9 -> "27$d"
            else -> d
        }
    }
}
