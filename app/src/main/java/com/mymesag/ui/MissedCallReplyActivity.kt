package com.mymesag.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier

class MissedCallReplyActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val phone = intent.getStringExtra(EXTRA_PHONE).orEmpty()
        val contact = intent.getStringExtra(EXTRA_CONTACT).orEmpty()
        val target = intent.getStringExtra(EXTRA_TARGET).orEmpty()
        if (phone.isBlank() || target.isBlank()) {
            finish()
            return
        }
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MissedCallReplyScreen(
                        phoneNumber = phone,
                        contactName = contact,
                        targetDeviceId = target,
                        onDone = { finish() }
                    )
                }
            }
        }
    }

    companion object {
        const val EXTRA_PHONE = "extra_phone"
        const val EXTRA_CONTACT = "extra_contact"
        const val EXTRA_TARGET = "extra_target"
    }
}
