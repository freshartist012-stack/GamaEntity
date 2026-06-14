package com.rio.gamaentity

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class OnboardingActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = getSharedPreferences("gama_prefs", MODE_PRIVATE)

        if (prefs.getString("user_name", null) != null) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_onboarding)

        val nameInput = findViewById<EditText>(R.id.nameInput)
        val nativeButton = findViewById<Button>(R.id.nativeButton)
        val groqButton = findViewById<Button>(R.id.groqButton)

        nativeButton.setOnClickListener {
            val name = nameInput.text.toString().trim()
            if (name.isEmpty()) {
                Toast.makeText(this, "Please enter your name", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            prefs.edit().putString("user_name", name).putString("model_type", "native").apply()
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }

        groqButton.setOnClickListener {
            val name = nameInput.text.toString().trim()
            if (name.isEmpty()) {
                Toast.makeText(this, "Please enter your name", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            prefs.edit().putString("user_name", name).apply()
            startActivity(Intent(this, GroqSetupActivity::class.java))
            finish()
        }
    }
}
