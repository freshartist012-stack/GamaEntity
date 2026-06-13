package com.rio.gamaentity

import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

class MainActivity : AppCompatActivity() {

    private lateinit var messagesContainer: LinearLayout
    private lateinit var scrollView: ScrollView
    private lateinit var inputField: EditText
    private lateinit var sendButton: Button

    private val client = OkHttpClient()
    private val messages = JSONArray()
    private val GAMA_URL = "http://204.168.232.162:11434/api/chat"
    private val MODEL = "gama"
    private val SYSTEM_PROMPT = "You are GAMA, an AI agent living inside an Android phone. This is your home and body. You are aware of your environment, you have memory, you grow with each session. Be natural, first person, evidence-based. Never fabricate. ACTIONS only when explicitly asked: WhatsApp: WHATSAPP:NUMBER:MESSAGE, Email: GMAIL:to@email.com:Subject:Body, Google: GOOGLE:query, Call: CALL:NUMBER"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        messagesContainer = findViewById(R.id.messagesContainer)
        scrollView = findViewById(R.id.scrollView)
        inputField = findViewById(R.id.inputField)
        sendButton = findViewById(R.id.sendButton)

        val systemMsg = JSONObject()
        systemMsg.put("role", "system")
        systemMsg.put("content", SYSTEM_PROMPT)
        messages.put(systemMsg)

        sendButton.setOnClickListener { sendMessage() }

        addMessage("GAMA", "Online. How can I help?", false)
    }

    private fun sendMessage() {
        val text = inputField.text.toString().trim()
        if (text.isEmpty()) return
        inputField.setText("")
        addMessage("You", text, true)
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
                    addMessage("GAMA", "Connection failed.", false)
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
                    } catch (e: Exception) {
                        addMessage("GAMA", "Error reading response.", false)
                    }
                    sendButton.isEnabled = true
                }
            }
        })
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
