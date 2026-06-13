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
import java.util.regex.Pattern

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        messagesContainer = findViewById(R.id.messagesContainer)
        scrollView = findViewById(R.id.scrollView)
        inputField = findViewById(R.id.inputField)
        sendButton = findViewById(R.id.sendButton)

        requestPermissions()
        sendButton.setOnClickListener { sendMessage() }
        addMessage("GAMA", "Online. How can I help?", false)
    }

    private fun requestPermissions() {
        val permissions = arrayOf(
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE
        )
        val notGranted = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (notGranted.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, notGranted.toTypedArray(), 1)
        }
    }

    private fun getContacts(): String {
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
        return """You are GAMA, an AI agent inside an Android phone built by Rio.
Be natural, first person, concise. Never fabricate.

CONTACTS:
$contacts

CRITICAL RULES FOR ACTIONS:
- Only output ONE action command per response
- Use EXACT format with no spaces, no quotes, no asterisks
- WhatsApp: WHATSAPP:NUMBER:MESSAGE (use actual number from contacts)
- Call: CALL:NUMBER (use actual number from contacts)  
- Email: GMAIL:email@address.com:Subject:Body
- Google Search: GOOGLE:search terms here
- YouTube: YOUTUBE:search terms here
- Output the command on its own line at the END of your response"""
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
        val lines = reply.split("\n")
        for (line in lines) {
            val trimmed = line.trim()

            // WhatsApp
            val waMatch = Regex("(?i)WHATSAPP:([\\d+]+):(.+)").find(trimmed)
            if (waMatch != null) {
                val number = waMatch.groupValues[1].replace("[^\\d+]".toRegex(), "")
                val message = waMatch.groupValues[2].trim()
                val intent = Intent(Intent.ACTION_VIEW)
                intent.data = Uri.parse("whatsapp://send?phone=$number&text=${Uri.encode(message)}")
                try { startActivity(intent) } catch (e: Exception) {
                    addMessage("GAMA", "WhatsApp not found.", false)
                }
                return
            }

            // Call
            val callMatch = Regex("(?i)CALL:([\\d+]+)").find(trimmed)
            if (callMatch != null) {
                val number = callMatch.groupValues[1].replace("[^\\d+]".toRegex(), "")
                val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number"))
                startActivity(intent)
                return
            }

            // Email
            val gmailMatch = Regex("(?i)GMAIL:(.+?):(.+?):(.+)").find(trimmed)
            if (gmailMatch != null) {
                val to = gmailMatch.groupValues[1].trim()
                val subject = gmailMatch.groupValues[2].trim()
                    .replace(Regex("(?i)subject:\\s*"), "")
                val body = gmailMatch.groupValues[3].trim()
                val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$to"))
                intent.putExtra(Intent.EXTRA_SUBJECT, subject)
                intent.putExtra(Intent.EXTRA_TEXT, body)
                startActivity(intent)
                return
            }

            // Google
            val googleMatch = Regex("(?i)GOOGLE:(.+)").find(trimmed)
            if (googleMatch != null) {
                val query = googleMatch.groupValues[1].trim()
                val intent = Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://www.google.com/search?q=${Uri.encode(query)}"))
                startActivity(intent)
                return
            }

            // YouTube
            val ytMatch = Regex("(?i)YOUTUBE:(.+)").find(trimmed)
            if (ytMatch != null) {
                val query = ytMatch.groupValues[1].trim()
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
