package com.mymesag.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier

/** B 端回复输入页（阶段 4 / 项目书 2.3）。 */
class ReplyActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val replyToId = intent.getStringExtra(EXTRA_REPLY_TO)
        val pkg = intent.getStringExtra(EXTRA_PKG).orEmpty()
        val target = intent.getStringExtra(EXTRA_TARGET).orEmpty()
        val appName = intent.getStringExtra(EXTRA_APP_NAME).orEmpty()
        val original = intent.getStringExtra(EXTRA_ORIGINAL).orEmpty()
        if (replyToId.isNullOrEmpty()) {
            finish()
            return
        }
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ReplyScreen(replyToId, pkg, target, appName, original, onDone = { finish() })
                }
            }
        }
    }

    companion object {
        const val EXTRA_REPLY_TO = "extra_reply_to"
        const val EXTRA_PKG = "extra_pkg"
        const val EXTRA_TARGET = "extra_target"
        const val EXTRA_APP_NAME = "extra_app_name"
        const val EXTRA_ORIGINAL = "extra_original"
    }
}
