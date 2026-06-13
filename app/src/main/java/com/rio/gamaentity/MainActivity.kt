package com.rio.gamaentity

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.util.Log
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var messagesContainer: LinearLayout
    private lateinit var scrollView: ScrollView
    private lateinit var inputField: EditText
    private lateinit var sendButton: Button

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val messages = JSONArray()
    private val GAMA_URL = "http://204.168.232.162:11434/api/chat"
    private val MODEL = "gama"
    private val PERMISSIONS_REQUEST = 100

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        messagesContainer = findViewById(R.id.messagesContainer)
        scrollView = findViewById(R.id.scrollView)
        inputField = findViewById(R.id.inputField)
        sendButton = findViewById(R.id.sendButton)

        checkAndRequestPermissions()
        sendButton.setOnClickListener { sendMessage() }
        addMessage("GAMA", "Online. How can I help?", false)
    }

    private fun checkAndRequestPermissions() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.READ_CONTACTS)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE)
            != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.CALL_PHONE)
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), PERMISSIONS_REQUEST)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSIONS_REQUEST) {
            val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (allGranted) {
                addMessage("GAMA", "Permissions granted. Ready.", false)
            } else {
                addMessage("GAMA", "Some permissions denied. Contacts and calls may not work.", false)
            }
        }
    }

    private fun getContacts(): String {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED) return ""
        val contacts = StringBuilder()
        try {
            val cursor: Cursor? = contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                null, null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
            )
            cursor?.use {
                while (it.moveToNext()) {
                    val name = it.getString(0) ?: continue
                    val number = it.getString(1) ?: continue
                    contacts.append("$name: $number\n")
                }
            }
        } catch (e: Exception) {
            Log.e("GAMA", "Contacts error: ${e.message}")
        }
        return contacts.toString().take(2000)
    }

    private fun buildSystemPrompt(): String {
        val contacts = getContacts()
        val contactsSection = if (contacts.isNotEmpty()) "CONTACTS:\n$contacts\n" else ""
        return """You are GAMA, an AI agent inside an Android phone built by Rio.
Be natural, first person, concise. Never fabricate.

$contactsSection
CRITICAL: When performing actions output the command on its own line at the end.
Use EXACT format, no spaces around colons, no quotes, no asterisks:
WhatsApp: WHATSAPP:NUMBER:MESSAGE
Call: CALL:NUMBER  
Email: GMAIL:email@domain.com:Subject:Body
Google: GOOGLE:search terms
YouTube: YOUTUBE:search terms
Always use the actual phone number from contacts, never the name."""
    }

    private fun sendMessage() {
        val text = inputField.text.toString().trim()
        if (text.isEmpty()) return
        inputField.setText("")
        addMessage("You", text, true)

        if (messages.length() == 0) {
            val systemMsg = JSONObject()
            systemMsg.put("role", "system")
            systemMsg.put("content", buildSystemPrompt())
            messages.put(systemMsg)
        }

        val userMsg = JSONObject()
        userMsg.put("role", "user")
        userMsg.put("content", text)
        messages.put(userMsg)
        sendButton.isEnabled = false
        callGama()
    }

    private fun callGama() {
        val body = JSONObject()
        body.put("model", MODEL)
        body.put("messages", messages)
        body.put("stream", false)
        val requestBody = body.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url(GAMA_URL).post(requestBody).build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    addMessage("GAMA", "Error: ${e.message}", false)
                    sendButton.isEnabled = true
                }
            }
            override fun onResponse(call: Call, response: Response) {
                val responseBody = response.body?.string()
                runOnUiThread {
                    try {
                        val json = JSONObject(responseBody ?: "")
                        val reply = json.getJSONObject("message").getString("content")
                        val assistantMsg = JSONObject()
                        assistantMsg.put("role", "assistant")
                        assistantMsg.put("content", reply)
                        messages.put(assistantMsg)
                        addMessage("GAMA", reply, false)
                        handleAction(reply)
                    } catch (e: Exception) {
                        addMessage("GAMA", "Parse error: ${e.message}", false)
                    }
                    sendButton.isEnabled = true
                }
            }
        })
    }

    private fun handleAction(reply: String) {
        for (line in reply.split("\n")) {
            val t = line.trim()

            Regex("(?i)WHATSAPP:([\\d+]+):(.+)").find(t)?.let {
                val number = it.groupValues[1].replace("[^\\d+]".toRegex(), "")
                val message = it.groupValues[2].trim()
                val intent = Intent(Intent.ACTION_VIEW)
                intent.data = Uri.parse("whatsapp://send?phone=$number&text=${Uri.encode(message)}")
                try { startActivity(intent) } catch (e: Exception) {
                    addMessage("GAMA", "WhatsApp not found.", false)
                }
                return
            }

            Regex("(?i)CALL:([\\d+]+)").find(t)?.let {
                val number = it.groupValues[1].replace("[^\\d+]".toRegex(), "")
                val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number"))
                startActivity(intent)
                return
            }

            Regex("(?i)GMAIL:(.+?):(.+?):(.+)").find(t)?.let {
                val to = it.groupValues[1].trim()
                val subject = it.groupValues[2].trim().replace(Regex("(?i)subject:\\s*"), "")
                val body = it.groupValues[3].trim()
                val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$to"))
                intent.putExtra(Intent.EXTRA_SUBJECT, subject)
                intent.putExtra(Intent.EXTRA_TEXT, body)
                startActivity(intent)
                return
            }

            Regex("(?i)GOOGLE:(.+)").find(t)?.let {
                val query = it.groupValues[1].trim()
                val intent = Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://www.google.com/search?q=${Uri.encode(query)}"))
                startActivity(intent)
                return
            }

            Regex("(?i)YOUTUBE:(.+)").find(t)?.let {
                val query = it.groupValues[1].trim()
                val intent = Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(query)}"))
                startActivity(intent)
                return
            }
        }
    }

    private fun addMessage(sender: String, text: String, isUser: Boolean) {
        val messageView = TextView(this)
        messageView.text = text
        messageView.setPadding(24, 16, 24, 16)
        messageView.textSize = 16f
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        params.setMargins(16, 8, 16, 8)
        if (isUser) {
            messageView.setBackgroundResource(R.drawable.user_bubble)
            messageView.setTextColor(resources.getColor(android.R.color.white, null))
            params.gravity = android.view.Gravity.END
        } else {
            messageView.setBackgroundResource(R.drawable.gama_bubble)
            messageView.setTextColor(resources.getColor(android.R.color.black, null))
            params.gravity = android.view.Gravity.START
        }
        messageView.layoutParams = params
        messagesContainer.addView(messageView)
        scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
    }
}
