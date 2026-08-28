package com.rio.gamaentity

import android.content.SharedPreferences
import android.os.Bundle
import android.os.CountDownTimer
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class KeysActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var keysContainer: LinearLayout
    private var countDownTimer: CountDownTimer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = getSharedPreferences("gama_prefs", MODE_PRIVATE)

        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(32, 32, 32, 32)
        layout.setBackgroundColor(resources.getColor(android.R.color.background_light, null))

        val title = TextView(this)
        title.text = "API Keys"
        title.textSize = 22f
        title.setTextColor(resources.getColor(android.R.color.black, null))
        title.setPadding(0, 0, 0, 24)
        layout.addView(title)

        val resetInfo = TextView(this)
        resetInfo.textSize = 13f
        resetInfo.setTextColor(0xFF666666.toInt())
        resetInfo.setPadding(0, 0, 0, 16)
        layout.addView(resetInfo)

        startResetTimer(resetInfo)

        keysContainer = LinearLayout(this)
        keysContainer.orientation = LinearLayout.VERTICAL
        layout.addView(keysContainer)

        val addBtn = Button(this)
        addBtn.text = "Add New Key"
        addBtn.setBackgroundColor(resources.getColor(android.R.color.holo_green_dark, null))
        addBtn.setTextColor(resources.getColor(android.R.color.white, null))
        val btnParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        btnParams.setMargins(0, 24, 0, 0)
        addBtn.layoutParams = btnParams
        addBtn.setOnClickListener { showAddKeyDialog() }
        layout.addView(addBtn)

        val backBtn = Button(this)
        backBtn.text = "Back"
        backBtn.setBackgroundColor(0xFF888888.toInt())
        backBtn.setTextColor(resources.getColor(android.R.color.white, null))
        val backParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        backParams.setMargins(0, 12, 0, 0)
        backBtn.layoutParams = backParams
        backBtn.setOnClickListener { finish() }
        layout.addView(backBtn)

        val scroll = ScrollView(this)
        scroll.addView(layout)
        setContentView(scroll)

        refreshKeys()
    }

    private fun startResetTimer(view: TextView) {
        val now = System.currentTimeMillis()
        val minuteMs = 60_000L
        val nextReset = ((now / minuteMs) + 1) * minuteMs
        val remaining = nextReset - now

        countDownTimer?.cancel()
        countDownTimer = object : CountDownTimer(remaining, 1000) {
            override fun onTick(ms: Long) {
                val secs = ms / 1000
                view.text = "Groq free tier resets every minute. Next reset in: ${secs}s\nEstimated requests remaining depends on your usage."
            }
            override fun onFinish() {
                startResetTimer(view)
            }
        }.start()
    }

    private fun getKeys(): JSONArray {
        val raw = prefs.getString("saved_keys", "[]") ?: "[]"
        return try { JSONArray(raw) } catch (e: Exception) { JSONArray() }
    }

    private fun saveKeys(keys: JSONArray) {
        prefs.edit().putString("saved_keys", keys.toString()).apply()
    }

    private fun refreshKeys() {
        keysContainer.removeAllViews()
        val keys = getKeys()
        val activeKey = prefs.getString("groq_key", "") ?: ""

        if (keys.length() == 0) {
            val empty = TextView(this)
            empty.text = "No saved keys. Add one below."
            empty.setTextColor(0xFF888888.toInt())
            empty.setPadding(0, 16, 0, 16)
            keysContainer.addView(empty)
            return
        }

        for (i in 0 until keys.length()) {
            val key = keys.getJSONObject(i)
            val nickname = key.getString("nickname")
            val keyValue = key.getString("key")
            val isActive = keyValue == activeKey

            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.setPadding(16, 16, 16, 16)
            row.setBackgroundColor(if (isActive) 0xFFE8F5E9.toInt() else 0xFFF5F5F5.toInt())
            val rowParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            rowParams.setMargins(0, 0, 0, 8)
            row.layoutParams = rowParams

            val nameView = TextView(this)
            nameView.text = "${if (isActive) "✓ " else ""}$nickname"
            nameView.textSize = 15f
            nameView.setTextColor(if (isActive) 0xFF2E7D32.toInt() else 0xFF333333.toInt())
            nameView.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            row.addView(nameView)

            if (!isActive) {
                val useBtn = Button(this)
                useBtn.text = "Use"
                useBtn.textSize = 12f
                useBtn.setBackgroundColor(0xFFCEBAA2.toInt())
                useBtn.setTextColor(resources.getColor(android.R.color.white, null))
                useBtn.setPadding(16, 8, 16, 8)
                useBtn.setOnClickListener {
                    prefs.edit().putString("groq_key", keyValue).apply()
                    refreshKeys()
                    Toast.makeText(this, "Switched to $nickname", Toast.LENGTH_SHORT).show()
                }
                row.addView(useBtn)
            }

            val deleteBtn = Button(this)
            deleteBtn.text = "✕"
            deleteBtn.textSize = 12f
            deleteBtn.setBackgroundColor(0xFFCC0000.toInt())
            deleteBtn.setTextColor(resources.getColor(android.R.color.white, null))
            deleteBtn.setPadding(16, 8, 16, 8)
            deleteBtn.setOnClickListener {
                AlertDialog.Builder(this)
                    .setTitle("Delete key?")
                    .setMessage("Remove $nickname?")
                    .setPositiveButton("Delete") { _, _ ->
                        val updated = JSONArray()
                        for (j in 0 until keys.length()) {
                            if (j != i) updated.put(keys.getJSONObject(j))
                        }
                        saveKeys(updated)
                        refreshKeys()
                    }
                    .setNegativeButton("Cancel") { d, _ -> d.dismiss() }
                    .show()
            }
            row.addView(deleteBtn)
            keysContainer.addView(row)
        }
    }

    private fun showAddKeyDialog() {
        val dialogLayout = LinearLayout(this)
        dialogLayout.orientation = LinearLayout.VERTICAL
        dialogLayout.setPadding(48, 24, 48, 0)

        val nicknameInput = EditText(this)
        nicknameInput.hint = "Nickname (e.g. Key 1)"
        dialogLayout.addView(TextView(this).apply { text = "Nickname:"; textSize = 13f })
        dialogLayout.addView(nicknameInput)

        val keyInput = EditText(this)
        keyInput.hint = "Paste Groq API key"
        dialogLayout.addView(TextView(this).apply { text = "API Key:"; textSize = 13f; setPadding(0, 12, 0, 0) })
        dialogLayout.addView(keyInput)

        AlertDialog.Builder(this)
            .setTitle("Add API Key")
            .setView(dialogLayout)
            .setPositiveButton("Save") { _, _ ->
                val nickname = nicknameInput.text.toString().trim().ifEmpty { "Key ${getKeys().length() + 1}" }
                val key = keyInput.text.toString().trim()
                if (key.isNotEmpty()) {
                    val keys = getKeys()
                    val obj = JSONObject()
                    obj.put("nickname", nickname)
                    obj.put("key", key)
                    keys.put(obj)
                    saveKeys(keys)
                    prefs.edit().putString("groq_key", key).apply()
                    refreshKeys()
                    Toast.makeText(this, "Key saved and activated", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Please enter a key", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel") { d, _ -> d.dismiss() }
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        countDownTimer?.cancel()
    }
}
