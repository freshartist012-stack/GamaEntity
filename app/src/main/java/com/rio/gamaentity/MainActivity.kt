package com.rio.gamaentity

import android.Manifest
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
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
    private lateinit var micButton: ImageButton
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var drawerContent: LinearLayout
    private lateinit var prefs: SharedPreferences
    private lateinit var tts: TextToSpeech
    private var speechRecognizer: SpeechRecognizer? = null
    private var ttsReady = false

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val messages = JSONArray()
    private val GAMA_URL = "http://204.168.232.162:11434/api/chat"
    private var currentChatFile = ""
    private var userName = "User"
    private var modelType = "native"
    private var groqKey = ""
    private var systemPromptAdded = false
    private lateinit var typingIndicator: TextView

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
        micButton = findViewById(R.id.micButton)

        tts = TextToSpeech(this) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
        }

        findViewById<ImageButton>(R.id.menuButton).setOnClickListener {
            drawerLayout.openDrawer(Gravity.LEFT)
            loadChatHistory()
        }

        typingIndicator = findViewById(R.id.typingIndicator)
        sendButton.setOnClickListener { sendMessage() }
        micButton.setOnClickListener { startVoiceInput() }

        checkAndRequestPermissions()
        checkAccessibilityService()
        startNewChat()
    }

    private fun checkAndRequestPermissions() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED)
            needed.add(Manifest.permission.READ_CONTACTS)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED)
            needed.add(Manifest.permission.CALL_PHONE)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            needed.add(Manifest.permission.RECORD_AUDIO)
        if (needed.isNotEmpty())
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), 100)
    }

    private fun startVoiceInput() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Microphone permission needed", Toast.LENGTH_SHORT).show()
            return
        }
        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    inputField.setText(matches[0])
                    sendMessage()
                }
            }
            override fun onError(error: Int) {
                runOnUiThread { Toast.makeText(this@MainActivity, "Voice error - try again", Toast.LENGTH_SHORT).show() }
            }
            override fun onReadyForSpeech(params: Bundle?) { runOnUiThread { micButton.setColorFilter(0xFFFF0000.toInt()) } }
            override fun onEndOfSpeech() { runOnUiThread { micButton.clearColorFilter() } }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
        }
        speechRecognizer?.startListening(intent)
    }

    private fun speakText(text: String) {
        if (ttsReady) {
            val clean = text.replace(Regex("[*_#]"), "").take(500)
            tts.speak(clean, TextToSpeech.QUEUE_FLUSH, null, null)
        }
    }


    private fun checkAccessibilityService() {
        val enabled = android.provider.Settings.Secure.getString(
            contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: ""
        if (!enabled.contains(packageName)) {
            AlertDialog.Builder(this)
                .setTitle("Enable Auto-Send")
                .setMessage("To automatically send WhatsApp messages, enable GAMA Entity in Accessibility Settings.")
                .setPositiveButton("Open Settings") { _, _ ->
                    startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
                .setNegativeButton("Skip") { d, _ -> d.dismiss() }
                .show()
        }
    }
    private fun startNewChat() {
        for (i in messages.length() - 1 downTo 0) messages.remove(i)
        messagesContainer.removeAllViews()
        systemPromptAdded = false
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
        title.setTextColor(0xFFFFFFFF.toInt())
        drawerContent.addView(title)

        val btnParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        btnParams.setMargins(16, 8, 16, 8)

        val newChatBtn = Button(this)
        newChatBtn.text = "New Chat"
        newChatBtn.setBackgroundColor(0xFF4CAF50.toInt())
        newChatBtn.setTextColor(0xFFFFFFFF.toInt())
        newChatBtn.layoutParams = btnParams
        newChatBtn.setOnClickListener { startNewChat() }
        drawerContent.addView(newChatBtn)

        val switchBtn = Button(this)
        switchBtn.text = "Switch Model (${if (modelType == "groq") "Groq" else "GAMA"})"
        switchBtn.setBackgroundColor(0xFF333366.toInt())
        switchBtn.setTextColor(0xFFFFFFFF.toInt())
        switchBtn.layoutParams = btnParams
        switchBtn.setOnClickListener {
            prefs.edit().remove("model_type").remove("groq_key").apply()
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
        }
        drawerContent.addView(switchBtn)

        val aboutBtn = Button(this)
        aboutBtn.text = "About"
        aboutBtn.setBackgroundColor(0xFF222244.toInt())
        aboutBtn.setTextColor(0xFFFFFFFF.toInt())
        aboutBtn.layoutParams = btnParams
        aboutBtn.setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
            drawerLayout.closeDrawers()
        }
        drawerContent.addView(aboutBtn)

        val chatsDir = File(filesDir, "chats")
        if (chatsDir.exists()) {
            chatsDir.listFiles()?.sortedByDescending { it.lastModified() }?.forEach { file ->
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
            for (i in messages.length() - 1 downTo 0) messages.remove(i)
            messagesContainer.removeAllViews()
            currentChatFile = file.name
            systemPromptAdded = true
            for (i in 0 until saved.length()) {
                val msg = saved.getJSONObject(i)
                when (msg.getString("role")) {
                    "user" -> addMessage("You", msg.getString("content"), true)
                    "assistant" -> addMessage("GAMA", msg.getString("content"), false)
                }
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
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return ""
        val sb = StringBuilder()
        try {
            contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
                null, null, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
            )?.use {
                while (it.moveToNext()) {
                    val name = it.getString(0) ?: continue
                    val number = it.getString(1) ?: continue
                    sb.append("$name: $number\n")
                }
            }
        } catch (e: Exception) {}
        return sb.toString().take(8000)
    }

    private fun lookupContact(nameOrNumber: String): String {
        val digits = nameOrNumber.replace("[^\\d]".toRegex(), "")
        if (digits.length >= 7) return formatNumber(nameOrNumber)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return nameOrNumber
        try {
            contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
                null, null, null
            )?.use {
                while (it.moveToNext()) {
                    val name = it.getString(0) ?: continue
                    val number = it.getString(1) ?: continue
                    if (name.lowercase().contains(nameOrNumber.lowercase().trim()))
                        return formatNumber(number)
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

    private fun buildSystemPrompt(): String {
        val contacts = getContacts()
        val contactsSection = if (contacts.isNotEmpty()) "CONTACTS:\n$contacts\n" else ""
        return """You are GAMA, an AI agent inside an Android phone. The user's name is $userName.
Be natural, first person, concise. Never fabricate.

$contactsSection
NEVER output action commands unless the user uses words like "send", "call", "search", "email", "open" directed at a specific task.
A greeting like "hi" or "hello" should NEVER trigger any action.
Only output a command when explicitly instructed. Command format when needed:
WHATSAPP:NUMBER:MESSAGE
WHATSAPP_CALL:NUMBER
CALL:NUMBER
GMAIL:email@domain.com:Subject:Body
GOOGLE:search terms
YOUTUBE:search terms
Always use actual phone number from contacts, never the name.
When writing emails write only the email content. Never add notes, disclaimers, or parenthetical comments. If you need more information ask the user before writing the email."""
    }

    private fun sendMessage() {
        val text = inputField.text.toString().trim()
        if (text.isEmpty()) return
        inputField.setText("")
        addMessage("You", text, true)

        if (!systemPromptAdded) {
            val systemMsg = JSONObject()
            systemMsg.put("role", "system")
            systemMsg.put("content", buildSystemPrompt())
            messages.put(systemMsg)
            systemPromptAdded = true
        }

        val userMsg = JSONObject()
        userMsg.put("role", "user")
        userMsg.put("content", text)
        messages.put(userMsg)
        sendButton.isEnabled = false
        micButton.isEnabled = false
        typingIndicator.visibility = android.view.View.VISIBLE

        if (modelType == "groq" && groqKey.isNotEmpty()) callGroq() else callGama()
    }

    private fun callGama() {
        Handler(Looper.getMainLooper()).postDelayed({
            val body = JSONObject()
            body.put("model", "gama")
            body.put("messages", messages)
            body.put("stream", false)
            val req = Request.Builder().url(GAMA_URL)
                .post(body.toString().toRequestBody("application/json".toMediaType())).build()
            client.newCall(req).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    runOnUiThread {
                        addMessage("GAMA", "Unable to connect. Please check your internet connection.", false)
                        sendButton.isEnabled = true
                        micButton.isEnabled = true
                    }
                }
                override fun onResponse(call: Call, response: Response) {
                    val b = response.body?.string()
                    runOnUiThread {
                        try {
                            finishResponse(JSONObject(b ?: "").getJSONObject("message").getString("content"))
                        } catch (e: Exception) { addMessage("GAMA", "Parse error", false) }
                        sendButton.isEnabled = true
                        micButton.isEnabled = true
                    }
                }
            })
        }, 500)
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
                runOnUiThread {
                    addMessage("GAMA", "Unable to connect. Please check your internet connection.", false)
                    sendButton.isEnabled = true
                    micButton.isEnabled = true
                }
            }
            override fun onResponse(call: Call, response: Response) {
                val b = response.body?.string()
                runOnUiThread {
                    try {
                        finishResponse(JSONObject(b ?: "").getJSONArray("choices")
                            .getJSONObject(0).getJSONObject("message").getString("content"))
                    } catch (e: Exception) { addMessage("GAMA", "Parse error", false) }
                    sendButton.isEnabled = true
                    micButton.isEnabled = true
                    typingIndicator.visibility = android.view.View.GONE
                }
            }
        })
    }

    private fun finishResponse(reply: String) {
        val msg = JSONObject()
        msg.put("role", "assistant")
        msg.put("content", reply)
        messages.put(msg)
        addMessage("GAMA", reply, false)
        handleAction(reply)
        saveCurrentChat()
    }

    private fun showContactPicker(action: String, message: String) {
        val contacts = mutableListOf<Pair<String, String>>()
        try {
            contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
                null, null, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
            )?.use {
                while (it.moveToNext()) {
                    val name = it.getString(0) ?: continue
                    val number = it.getString(1) ?: continue
                    contacts.add(Pair(name, number))
                }
            }
        } catch (e: Exception) {}

        val names = contacts.map { it.first }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Select Contact")
            .setItems(names) { _, which ->
                val number = formatNumber(contacts[which].second)
                if (action == "whatsapp") {
                    val uri = Uri.parse("https://api.whatsapp.com/send?phone=$number&text=${Uri.encode(message)}")
                    try { startActivity(Intent(Intent.ACTION_VIEW, uri).apply { setPackage("com.whatsapp") }) }
                    catch (e: Exception) { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                } else {
                    try { startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:$number"))) }
                    catch (e: Exception) { startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number"))) }
                }
            }
            .setNegativeButton("Cancel") { d, _ -> d.dismiss() }
            .show()
    }

    private fun handleAction(reply: String) {
        for (line in reply.split("\n")) {
            val t = line.trim()

            Regex("(?i)WHATSAPP:([^:]+):(.+)").find(t)?.let {
                val number = lookupContact(it.groupValues[1].trim())
                val message = it.groupValues[2].trim()
                val uri = Uri.parse("https://api.whatsapp.com/send?phone=$number&text=${Uri.encode(message)}")
                GamaAccessibilityService.pendingSend = true
                try { startActivity(Intent(Intent.ACTION_VIEW, uri).apply { setPackage("com.whatsapp") }) }
                catch (e: Exception) { try { startActivity(Intent(Intent.ACTION_VIEW, uri)) } catch (e2: Exception) {} }
                return
            }

            Regex("(?i)WHATSAPP_CALL:([^\\n]+)").find(t)?.let {
                val number = lookupContact(it.groupValues[1].trim())
                val digits = number.replace("[^\\d]".toRegex(), "")
                if (digits.length < 7) { showContactPicker("call", ""); return }
                val uri = Uri.parse("https://api.whatsapp.com/send?phone=$number")
                try { startActivity(Intent(Intent.ACTION_VIEW, uri).apply { setPackage("com.whatsapp") }) }
                catch (e: Exception) { addMessage("GAMA", "WhatsApp not found.", false) }
                return
            }

            Regex("(?i)CALL:([^\\n]+)").find(t)?.let {
                val raw = it.groupValues[1].trim()
                val number = lookupContact(raw)
                val digits = number.replace("[^\\d]".toRegex(), "")
                if (digits.length < 7) {
                    showContactPicker("call", "")
                    return
                }
                try { startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:$number"))) }
                catch (e: Exception) { startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number"))) }
                return
            }

            Regex("(?i)GMAIL:([^:]+):([^:]+):(.+)", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)).find(reply)?.let {
                val to = it.groupValues[1].trim()
                val subject = it.groupValues[2].trim().replace(Regex("(?i)^subject:\\s*"), "")
                val body = it.groupValues[3].trim()
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("mailto:$to?subject=${Uri.encode(subject)}&body=${Uri.encode(body)}")))
                return
            }

            Regex("(?i)GOOGLE:(.+)").find(t)?.let {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=${Uri.encode(it.groupValues[1].trim())}")))
                return
            }

            Regex("(?i)YOUTUBE:(.+)").find(t)?.let {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(it.groupValues[1].trim())}")))
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
            val options = if (!isUser && ttsReady)
                arrayOf("Copy", "Read aloud")
            else
                arrayOf("Copy")
            AlertDialog.Builder(this)
                .setItems(options) { _, which ->
                    when (which) {
                        0 -> {
                            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("GAMA", text))
                            Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
                        }
                        1 -> speakText(text)
                    }
                }.show()
            true
        }
        val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        params.setMargins(16, 8, 16, 8)
        if (isUser) {
            messageView.setBackgroundResource(R.drawable.user_bubble)
            messageView.setTextColor(0xFFFFFFFF.toInt())
            params.gravity = Gravity.END
        } else {
            messageView.setBackgroundResource(R.drawable.gama_bubble)
            val nightMode = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
            val textColor = if (nightMode == android.content.res.Configuration.UI_MODE_NIGHT_YES) 0xFFEEEEEE.toInt() else 0xFF111111.toInt()
            messageView.setTextColor(textColor)
            params.gravity = Gravity.START
        }
        messageView.layoutParams = params
        messagesContainer.addView(messageView)
        scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    override fun onDestroy() {
        super.onDestroy()
        tts.stop()
        tts.shutdown()
        speechRecognizer?.destroy()
    }
}
