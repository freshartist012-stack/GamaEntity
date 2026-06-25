package com.rio.gamaentity

import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class GroqSetupActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_groq_setup)

        prefs = getSharedPreferences("gama_prefs", MODE_PRIVATE)

        val instructionsView = findViewById<TextView>(R.id.groqInstructions)
        val groqKeyInput = findViewById<EditText>(R.id.groqKeyInput)
        val confirmButton = findViewById<Button>(R.id.confirmGroqButton)
        val backButton = findViewById<Button>(R.id.backButton)

        val instructions = "1. Tap the link below to open Groq\n2. Sign in with Google\n3. Click Generate API Key\n4. Copy the key\n5. Paste it in the field below\n\nOpen Groq API Keys"
        val spannable = SpannableString(instructions)
        val linkText = "Open Groq API Keys"
        val start = instructions.indexOf(linkText)
        val end = start + linkText.length
        spannable.setSpan(object : ClickableSpan() {
            override fun onClick(widget: View) {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://console.groq.com/keys")))
            }
        }, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        instructionsView.text = spannable
        instructionsView.movementMethod = LinkMovementMethod.getInstance()

        confirmButton.setOnClickListener {
            val key = groqKeyInput.text.toString().trim()
            if (key.isEmpty()) {
                Toast.makeText(this, "Please paste your Groq API key", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            prefs.edit().putString("groq_key", key).putString("model_type", "groq").apply()
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }

        backButton.setOnClickListener {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
        }
    }
}
