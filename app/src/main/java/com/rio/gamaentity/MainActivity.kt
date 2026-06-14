package com.rio.gamaentity

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.util.Log
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.drawerlayout.widget.DrawerLayout
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var messagesContainer: LinearLayout
    private lateinit var scrollView: ScrollView
    private lateinit var inputField: EditText
    private lateinit var sendButton: Button
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var drawerContent: LinearLayout
    private lateinit var prefs: SharedPreferences

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val messages = JSONArray()
    private val GAMA_URL = "http://204.168.232.162:11434/api/chat"
    private var currentChatFile: String = ""
    private var userName = "User"
    private var modelType = "native"
    private var groqKey = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("gama_prefs", MODE_PRIVATE)
        userName = prefs.getString("user_name", "User") ?: "User"
        modelType = prefs.getString("model_type", "native") ?: "native"
        groqKey = prefs.getString("groq_key", "") ?: ""

        drawerLayout = findViewById(R.id.drawerLayout)
        drawerContent = findViewById(R.id.drawerContent)
        messagesContainer = findViewById(R.id.messagesContainer)
        scrollView = findViewById(R.id.scrollView)
        inputField = findViewById(R.id.inputField)
        sendButton = findViewById(R.id.sendButton)

        val menuButton = findViewById<ImageButton>(R.id.menuButton)
        menuButton.setOnClickListener {
            drawerLayout.openDrawer(Gravity.LEFT)
            loadChatHistory()
        }

        sendButton.setOnClickListener { sendMessage() }
        checkAndRequestPermissions()
        startNewChat()
    }

    private fun checkAndRequestPermissions() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.READ_CONTACTS)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE)
            != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.CALL_PHONE)
        if (needed.isNotEmpty())
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), 100)
    }

    private fun lookupContact(nameOrNumber: String): String {
        val digits = nameOrNumber.replace("[^\\d]".toRegex(), "")
        if (digits.length >= 7) return formatNumber(nameOrNumber)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED) return nameOrNumber
        try {
            val cursor = contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                null, null, null
            )
            cursor?.use {
                while (it.moveToNext()) {
                    val contactName = it.getString(0) ?: continue
                    val number = it.getString(1) ?: continue
                    if (contactName.lowercase().contains(nameOrNumber.lowercase().trim())) {
                        return formatNumber(number)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("GAMA", "Contact lookup error: ${e.message}")
        }
        return nameOrNumber
    }

    private fun formatNumber(raw: String): String {
        val digits = raw.replace("[^\\d]".toRegex(), "")
        return when {
            digits.startsWith("27") && digits.length >= 11 -> digits
            digits.startsWith("0") && digits.length == 10 -> "27${digits.substring(1)}"
            digits.length == 9 -> "27$digits"
            else -> digits
        }
    }

    private fun startNewChat() {
        messages.length().let { len -> for (i in len - 1 downTo 0) messages.remove(i) }
        messagesContainer.removeAllViews()
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        currentChatFile = "chat_$timestamp.json"
        drawerLayout.closeDrawers()
        addMessage("GAMA", "Hi $userName! How can I help?", false)
    }

    private fun loadChatHistory() {
        drawerContent.removeAllViews()
        val title = TextView(this)
        title.text = "Chats"
        title.textSize = 18f
        title.setPadding(24, 24, 24, 16)
        title.setTextColor(resources.getColor(android.R.color.white, null))
        drawerContent.addView(title)

        val newChatBtn = Button(this)
        newChatBtn.text = "New Chat"
        newChatBtn.setBackgroundColor(0xFF4CAF50.toInt())
        newChatBtn.setTextColor(0xFFFFFFFF.toInt())
        val btnParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        btnParams.setMargins(16, 8, 16, 16)
        newChatBtn.layoutParams = btnParams
        newChatBtn.setOnClickListener { startNewChat() }
        drawerContent.addView(newChatBtn)

        val switchBtn = Button(this)
        switchBtn.text = "Switch Model (${if (modelType == "groq") "Groq" else "GAMA"})"
        switchBtn.setBackgroundColor(0xFF333366.toInt())
        switchBtn.setTextColor(0xFFFFFFFF.toInt())
        switchBtn.layoutParams = btnParams
        switchBtn.setOnClickListener {
            prefs.edit().remove("user_name").apply()
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
        }
        drawerContent.addView(switchBtn)

        val chatsDir = File(filesDir, "chats")
        if (chatsDir.exists()) {
            val files = chatsDir.listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()
            for (file in files) {
                try {
                    val arr = JSONArray(file.readText())
                    val preview = if (arr.length() > 0) arr.getJSONObject(0).getString("content").take(40) else file.name
                    val chatBtn = TextView(this)
                    chatBtn.text = preview
                    chatBtn.setPadding(24, 16, 24, 16)
                    chatBtn.setTextColor(0xFFCCCCCC.toInt())
                    chatBtn.textSize = 14f
                    chatBtn.setOnClickListener { loadChat(file) }
                    drawerContent.addView(chatBtn)
                } catch (e: Exception) {}
            }
        }
    }

    private fun loadChat(file: File) {
        try {
            val saved = JSONArray(file.readText())
            messages.length().let { len -> for (i in len - 1 downTo 0) messages.remove(i) }
            messagesContainer.removeAllViews()
            currentChatFile = file.name
            for (i in 0 until saved.length()) {
                val msg = saved.getJSONObject(i)
                val role = msg.getString("role")
                val text = msg.getString("content")
                if (role == "user") addMessage("You", text, true)
                else if (role == "assistant") addMessage("GAMA", text, false)
                messages.put(msg)
            }
            drawerLayout.closeDrawers()
        } catch (e: Exception) {
            Toast.makeText(this, "Could not load chat", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveCurrentChat() {
        try {
            val chatsDir = File(filesDir, "chats")
            if (!chatsDir.exists()) chatsDir.mkdirs()
            val toSave = JSONArray()
            for (i in 0 until messages.length()) {
                val msg = messages.getJSONObject(i)
                if (msg.getString("role") != "system") toSave.put(msg)
            }
            File(chatsDir, currentChatFile).writeText(toSave.toString())
        } catch (e: Exception) {}
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
        } catch (e: Exception) {}
        return contacts.toString().take(2000)
    }

    private fun buildSystemPrompt(): String {
        val contacts = getContacts()
        val contactsSection = if (contacts.isNotEmpty()) "CONTACTS:\n$contacts\n" else ""
        return """You are GAMA, an AI agent inside an Android phone. The user's name is $userName.
Be natural, first person, concise. Never fabricate.

$contactsSection
ACTIONS - output on its own line at end of response, exact format:
WHATSAPP:NUMBER:MESSAGE
CALL:NUMBER
GMAIL:email@domain.com:Subject:Body
GOOGLE:search terms
YOUTUBE:search terms
Always use actual phone number from contacts, never the name."""
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

        if (modelType == "groq" && groqKey.isNotEmpty()) callGroq() else callGama()
    }

    private fun callGama() {
        val body = JSONObject()
        body.put("model", "gama")
        body.put("messages", messages)
        body.put("stream", false)
        val req = Request.Builder().url(GAMA_URL)
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread { addMessage("GAMA", "Error: ${e.message}", false); sendButton.isEnabled = true }
            }
            override fun onResponse(call: Call, response: Response) {
                val b = response.body?.string()
                runOnUiThread {
                    try {
                        finishResponse(JSONObject(b ?: "").getJSONObject("message").getString("content"))
                    } catch (e: Exception) { addMessage("GAMA", "Parse error", false) }
                    sendButton.isEnabled = true
                }
            }
        })
    }

    private fun callGroq() {
        val body = JSONObject()
        body.put("model", "llama-3.3-70b-versatile")
        body.put("messages", messages)
        body.put("max_tokens", 1000)
        val req = Request.Builder()
            .url("https://api.groq.com/openai/v1/chat/completions")
            .addHeader("Authorization", "Bearer $groqKey")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread { addMessage("GAMA", "Error: ${e.message}", false); sendButton.isEnabled = true }
            }
            override fun onResponse(call: Call, response: Response) {
                val b = response.body?.string()
                runOnUiThread {
                    try {
                        finishResponse(JSONObject(b ?: "").getJSONArray("choices")
                            .getJSONObject(0).getJSONObject("message").getString("content"))
                    } catch (e: Exception) { addMessage("GAMA", "Parse error", false) }
                    sendButton.isEnabled = true
                }
            }
        })
    }

    private fun finishResponse(reply: String) {
        val assistantMsg = JSONObject()
        assistantMsg.put("role", "assistant")
        assistantMsg.put("content", reply)
        messages.put(assistantMsg)
        addMessage("GAMA", reply, false)
        handleAction(reply)
        saveCurrentChat()
    }

    private fun handleAction(reply: String) {
        for (line in reply.split("\n")) {
            val t = line.trim()

            Regex("(?i)WHATSAPP:([^:]+):(.+)").find(t)?.let {
                val number = lookupContact(it.groupValues[1].trim())
                val message = it.groupValues[2].trim()
                val uri = Uri.parse("https://api.whatsapp.com/send?phone=$number&text=${Uri.encode(message)}")
                val intent = Intent(Intent.ACTION_VIEW, uri).apply { setPackage("com.whatsapp") }
                try { startActivity(intent) } catch (e: Exception) {
                    try { startActivity(Intent(Intent.ACTION_VIEW, uri)) } catch (e2: Exception) {}
                }
                return
            }

            Regex("(?i)CALL:([^\\n]+)").find(t)?.let {
                val number = lookupContact(it.groupValues[1].trim())
                try {
                    startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:$number")))
                } catch (e: Exception) {
                    startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")))
                }
                return
            }

            Regex("(?i)GMAIL:([^:]+):([^:]+):(.+)").find(t)?.let {
                val to = it.groupValues[1].trim()
                val subject = it.groupValues[2].trim().replace(Regex("(?i)^subject:\\s*"), "")
                val body = it.groupValues[3].trim()
                startActivity(Intent(Intent.ACTION_VIEW,
                    Uri.parse("mailto:$to?subject=${Uri.encode(subject)}&body=${Uri.encode(body)}")))
                return
            }

            Regex("(?i)GOOGLE:(.+)").find(t)?.let {
                startActivity(Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://www.google.com/search?q=${Uri.encode(it.groupValues[1].trim())}")))
                return
            }

            Regex("(?i)YOUTUBE:(.+)").find(t)?.let {
                startActivity(Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(it.groupValues[1].trim())}")))
                return
            }
        }
    }

    private fun addMessage(sender: String, text: String, isUser: Boolean) {
        val messageView = TextView(this)
        messageView.text = text
        messageView.setPadding(24, 16, 24, 16)
        messageView.textSize = 16f
        messageView.setTextIsSelectable(true)
        messageView.setOnLongClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("GAMA", text))
            Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
            true
        }
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        params.setMargins(16, 8, 16, 8)
        if (isUser) {
            messageView.setBackgroundResource(R.drawable.user_bubble)
            messageView.setTextColor(resources.getColor(android.R.color.white, null))
            params.gravity = Gravity.END
        } else {
            messageView.setBackgroundResource(R.drawable.gama_bubble)
            messageView.setTextColor(resources.getColor(android.R.color.black, null))
            params.gravity = Gravity.START
        }
        messageView.layoutParams = params
        messagesContainer.addView(messageView)
        scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
    }
}
