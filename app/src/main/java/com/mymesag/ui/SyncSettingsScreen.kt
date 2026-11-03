package com.mymesag.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun SyncSettingsScreen(viewModel: AppSelectViewModel = viewModel()) {
    val syncSendEnabled by viewModel.syncSendEnabled.collectAsState()
    val syncReceiveEnabled by viewModel.syncReceiveEnabled.collectAsState()
    val syncVerification by viewModel.syncVerification.collectAsState()
    var showVerifyWarning by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text("同步设置", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(12.dp))

        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("发送通知", style = MaterialTheme.typography.titleMedium)
                        Text("采集并发送本设备的通知到其他设备", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = syncSendEnabled, onCheckedChange = { viewModel.setSyncSendEnabled(it) })
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("接收通知", style = MaterialTheme.typography.titleMedium)
                        Text("接收并显示其他设备的通知", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = syncReceiveEnabled, onCheckedChange = { viewModel.setSyncReceiveEnabled(it) })
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("同步验证码短信", style = MaterialTheme.typography.titleMedium)
                    Text("开启后验证码短信也会同步", style = MaterialTheme.typography.bodySmall)
                }
                Switch(
                    checked = syncVerification,
                    onCheckedChange = { checked ->
                        if (checked && !syncVerification) {
                            showVerifyWarning = true
                        } else {
                            viewModel.setSyncVerification(checked)
                        }
                    }
                )
            }
        }
    }

    if (showVerifyWarning) {
        AlertDialog(
            onDismissRequest = { showVerifyWarning = false },
            title = { Text("风险警告") },
            text = {
                Text(
                    "同步验证码短信会通过网络传输验证码内容，存在验证码被窃取、账号被盗用的风险。\n\n" +
                        "开启后将同时打开「发送通知」并启用「短信」应用同步。\n\n" +
                        "请务必确保你连接的 MQTT 服务器是你自己信任、可控的服务器，否则验证码可能被第三方截获。"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showVerifyWarning = false
                        viewModel.setSyncVerification(true)
                        viewModel.setSyncSendEnabled(true)
                        viewModel.enableSmsApps()
                    }
                ) { Text("我已了解风险，开启") }
            },
            dismissButton = {
                TextButton(onClick = { showVerifyWarning = false }) { Text("取消") }
            }
        )
    }
}
