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

        showDataDisclosureIfNeeded()
        startNewChat()
        if (intent?.action == "android.intent.action.ASSIST") {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ startVoiceInput() }, 800)
        }
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
            d.startsWith("270") && d.length == 12 -> "27${d.substring(3)}"
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
ALARM:HH:MM:Label (example: ALARM:07:30:Wake up)
DISMISS_ALARM (when user asks to dismiss or turn off a ringing alarm)
GMAIL:email@domain.com:Subject line here:Body text here (ALWAYS include a meaningful subject, never write the word Subject)
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
                        if (messages.length() > 0) messages.remove(messages.length() - 1)
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
                    if (messages.length() > 0) messages.remove(messages.length() - 1)
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
                GamaAccessibilityService.pendingWhatsAppSend = true
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

            val gmailThree = Regex("(?i)GMAIL:([^:\\n]+):([^:\\n]+):(.+)", setOf(RegexOption.DOT_MATCHES_ALL)).find(reply)
            val gmailTwo = Regex("(?i)GMAIL:([^:\\n]+):(.+)", setOf(RegexOption.DOT_MATCHES_ALL)).find(reply)
            val gm = gmailThree ?: gmailTwo
            if (gm != null) {
                val to = gm.groupValues[1].trim()
                val subject = if (gmailThree != null) gm.groupValues[2].trim().replace(Regex("(?i)^subject[=:\\s]+"), "").trim() else "Message"
                val body = if (gmailThree != null) gm.groupValues[3].trim() else gm.groupValues[2].trim()
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

            Regex("(?i)DISMISS_ALARM").find(t)?.let {
                GamaAccessibilityService.pendingAlarmDismiss = true
                addMessage("GAMA", "Dismissing alarm...", false)
                return
            }

            Regex("(?i)ALARM:(\\d{1,2}):(\\d{2})(?::(.+))?").find(t)?.let {
                val hour = it.groupValues[1].toIntOrNull() ?: return
                val minute = it.groupValues[2].toIntOrNull() ?: return
                val label = it.groupValues[3].ifEmpty { "GAMA Alarm" }
                val intent = Intent(android.provider.AlarmClock.ACTION_SET_ALARM).apply {
                    putExtra(android.provider.AlarmClock.EXTRA_HOUR, hour)
                    putExtra(android.provider.AlarmClock.EXTRA_MINUTES, minute)
                    putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, label)
                    putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, false)
                }
                try {
                    startActivity(intent)
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        startActivity(Intent(this, MainActivity::class.java).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP) })
                    }, 3000)
                } catch (e: Exception) {
                    addMessage("GAMA", "Could not set alarm.", false)
                }
                return
            }
        }
    }

    private fun lookupDefinition(word: String): String? {
        return try {
            val db = android.database.sqlite.SQLiteDatabase.openDatabase(
                filesDir.absolutePath + "/dictionary.db",
                null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY
            )
            val cursor = db.rawQuery("SELECT definition, wordtype FROM entries WHERE word = ? LIMIT 1", arrayOf(word.lowercase().trim()))
            val result = if (cursor.moveToFirst()) { val type = cursor.getString(1); val def = cursor.getString(0); if (type.isNotEmpty()) "($type)\n\n$def" else def } else null
            cursor.close()
            db.close()
            result
        } catch (e: Exception) { null }
    }


    private fun showDataDisclosureIfNeeded() {
        val prefs = getSharedPreferences("gama_prefs", MODE_PRIVATE)
        if (prefs.getBoolean("data_consent_given", false)) {
            copyDictionaryIfNeeded()
            checkAndRequestPermissions()
            checkAccessibilityService()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Data Usage Disclosure")
            .setMessage("GAMA Entity collects and transmits your contact names and phone numbers to Groq (api.groq.com) to enable features like sending WhatsApp messages and making calls by name. This data is sent only when you request an action involving a contact.\n\nBy tapping Accept, you consent to this data usage.")
            .setCancelable(false)
            .setPositiveButton("Accept") { _, _ ->
                prefs.edit().putBoolean("data_consent_given", true).apply()
                copyDictionaryIfNeeded()
                checkAndRequestPermissions()
                checkAccessibilityService()
            }
            .setNegativeButton("Decline") { _, _ ->
                finish()
            }
            .show()
    }
    private fun copyDictionaryIfNeeded() {
        val dest = java.io.File(filesDir, "dictionary.db")
        if (!dest.exists()) {
            try {
                assets.open("dictionary.db").use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
            } catch (e: Exception) {}
        }
    }

    private fun addMessage(sender: String, text: String, isUser: Boolean) {
        val wrapper = LinearLayout(this)
        wrapper.orientation = LinearLayout.VERTICAL
        val wrapperParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        wrapperParams.setMargins(16, 8, 16, 4)
        wrapperParams.gravity = if (isUser) Gravity.END else Gravity.START
        wrapper.layoutParams = wrapperParams

        val messageView = TextView(this)
        messageView.text = text
        messageView.setPadding(24, 16, 24, 16)
        messageView.textSize = 16f
        messageView.setTextIsSelectable(true)
        val msgParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        if (isUser) {
            messageView.setBackgroundResource(R.drawable.user_bubble)
            messageView.setTextColor(0xFFFFFFFF.toInt())
            msgParams.gravity = Gravity.END
        } else {
            messageView.setBackgroundResource(R.drawable.gama_bubble)
            val nightMode = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
            messageView.setTextColor(if (nightMode == android.content.res.Configuration.UI_MODE_NIGHT_YES) 0xFFEEEEEE.toInt() else 0xFF111111.toInt())
            msgParams.gravity = Gravity.START
        }
        messageView.layoutParams = msgParams
        wrapper.addView(messageView)

        val buttonsRow = LinearLayout(this)
        buttonsRow.orientation = LinearLayout.HORIZONTAL
        val btnRowParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        btnRowParams.gravity = if (isUser) Gravity.END else Gravity.START
        buttonsRow.layoutParams = btnRowParams

        val copyBtn = TextView(this)
        copyBtn.text = "Copy"
        copyBtn.textSize = 11f
        copyBtn.setPadding(12, 4, 12, 4)
        copyBtn.setTextColor(0xFF888888.toInt())
        copyBtn.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("GAMA", text))
            Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
        }
        buttonsRow.addView(copyBtn)

        if (!isUser && ttsReady) {
            val readBtn = TextView(this)
            readBtn.text = "Read aloud"
            readBtn.textSize = 11f
            readBtn.setPadding(12, 4, 12, 4)
            readBtn.setTextColor(0xFF888888.toInt())
            readBtn.setOnClickListener { speakText(text) }
            buttonsRow.addView(readBtn)
        }

        val defineBtn = TextView(this)
        defineBtn.text = "Define"
        defineBtn.textSize = 11f
        defineBtn.setPadding(12, 4, 12, 4)
        defineBtn.setTextColor(0xFF888888.toInt())
        defineBtn.setOnClickListener {
            val selected = messageView.text.toString()
            val start = messageView.selectionStart
            val end = messageView.selectionEnd
            val word = if (start >= 0 && end > start) selected.substring(start, end).trim()
                       else {
                           val input = android.widget.EditText(this)
                           input.hint = "Enter word to define"
                           AlertDialog.Builder(this)
                               .setTitle("Define a word")
                               .setView(input)
                               .setPositiveButton("Define") { _, _ ->
                                   val w = input.text.toString().trim()
                                   if (w.isNotEmpty()) showDefinition(w)
                               }
                               .setNegativeButton("Cancel") { d, _ -> d.dismiss() }
                               .show()
                           return@setOnClickListener
                       }
            if (word.isNotEmpty()) showDefinition(word)
        }
        buttonsRow.addView(defineBtn)
        wrapper.addView(buttonsRow)

        messagesContainer.addView(wrapper)
        scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun showDefinition(word: String) {
        val definition = lookupDefinition(word)
        if (definition != null) {
            AlertDialog.Builder(this)
                .setTitle(word.replaceFirstChar { it.uppercase() })
                .setMessage(definition)
                .setPositiveButton("OK") { d, _ -> d.dismiss() }
                .show()
        } else {
            Toast.makeText(this, "$word not found in dictionary", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        tts.stop()
        tts.shutdown()
        speechRecognizer?.destroy()
    }
}
