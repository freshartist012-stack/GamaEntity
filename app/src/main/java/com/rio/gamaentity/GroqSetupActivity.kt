package com.rio.gamaentity

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class GroqSetupActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_groq_setup)

        prefs = getSharedPreferences("gama_prefs", MODE_PRIVATE)

        val groqKeyInput = findViewById<EditText>(R.id.groqKeyInput)
        val confirmButton = findViewById<Button>(R.id.confirmGroqButton)
        val backButton = findViewById<Button>(R.id.backButton)

        confirmButton.setOnClickListener {
            val key = groqKeyInput.text.toString().trim()
            if (key.isEmpty()) {
                Toast.makeText(this, "Please paste your Groq API key", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            prefs.edit()
                .putString("groq_key", key)
                .putString("model_type", "groq")
                .apply()
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }

        backButton.setOnClickListener {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
        }
    }
}
