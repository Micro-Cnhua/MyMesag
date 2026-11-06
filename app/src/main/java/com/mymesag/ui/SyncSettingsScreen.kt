package com.mymesag.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mymesag.MyMesagApp
import com.mymesag.data.MqttConfigStore
import com.mymesag.data.MqttConnection
import com.mymesag.network.MqttConfig
import com.mymesag.notification.KeepAliveGuide
import com.mymesag.notification.NotificationAccess
import com.mymesag.notification.RomCompatibility
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun SyncSettingsScreen(
    onOpenMqtt: () -> Unit,
    onOpenPair: () -> Unit,
    onOpenManual: () -> Unit,
    viewModel: AppSelectViewModel = viewModel()
) {
    val context = LocalContext.current
    val syncSendEnabled by viewModel.syncSendEnabled.collectAsState()
    val syncReceiveEnabled by viewModel.syncReceiveEnabled.collectAsState()
    val syncVerification by viewModel.syncVerification.collectAsState()
    var showVerifyWarning by remember { mutableStateOf(false) }

    val romType = remember { RomCompatibility.detectRomType().name }
    val romInstructions = remember { RomCompatibility.getKeepAliveInstructions(context) }
    val romWarning = remember { RomCompatibility.getRomSpecificWarnings() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            "设置操作",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        // 使用教程（默认展开，置顶）
        TutorialCard()

        // 快速操作
        Text(
            "快速操作",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(vertical = 8.dp)
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton(
                icon = Icons.Default.Notifications,
                title = "通知使用权配置",
                description = "开启通知监听使用权，采集其他应用通知",
                onClick = { NotificationAccess.openSettings(context) }
            )
            ActionButton(
                icon = Icons.Default.Settings,
                title = "通知权限",
                description = "允许通知（本应用通知管理，应用信息页）",
                onClick = { NotificationAccess.openAppInfo(context) }
            )
            ActionButton(
                icon = Icons.Default.Cloud,
                title = "MQTT 配置",
                description = "配置服务器连接",
                onClick = onOpenMqtt
            )
            ActionButton(
                icon = Icons.Default.Devices,
                title = "设备配对",
                description = "配对其他设备",
                onClick = onOpenPair
            )

            // 设备身份（发送/接收/验证码同步开关）
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "设备身份",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "设置本设备在同步中的角色",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
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
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
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

            ActionButton(
                icon = Icons.Default.MenuBook,
                title = "额外帮助",
                description = "查看在线额外帮助",
                onClick = onOpenManual
            )
        }

        // 系统设置与保活
        SystemSettingsSection(context, romType, romInstructions, romWarning)
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

@Composable
private fun TutorialCard() {
    var expanded by remember { mutableStateOf(true) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        onClick = { expanded = !expanded }
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "使用教程",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "收起" else "展开",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (expanded) {
                Spacer(Modifier.height(12.dp))
                TutorialStep("1", "开启「通知使用权」")
                TutorialStep("2", "配置 MQTT 服务器并连接")
                TutorialStep("3", "两台设备互相配对")
                TutorialStep("4", "在「应用选择」开启要同步的应用")
                TutorialStep("5", "在「设备身份」控制发送/接收方向")
            }
        }
    }
}

@Composable
private fun TutorialStep(num: String, text: String) {
    Row(
        modifier = Modifier.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxSize()
            ) {
                Text(
                    num,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ActionButton(
    icon: ImageVector,
    title: String,
    description: String,
    onClick: () -> Unit
) {
    ElevatedCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp)
            )
            Spacer(Modifier.width(16.dp))
            Column {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SystemSettingsSection(
    context: android.content.Context,
    romType: String,
    romInstructions: String?,
    romWarning: String?
) {
    val app = context.applicationContext as MyMesagApp
    val scope = rememberCoroutineScope()
    var keepAlive by remember { mutableStateOf(MqttConfigStore.DEFAULT_KEEPALIVE) }
    LaunchedEffect(Unit) { keepAlive = app.mqttConfigStore.savedKeepAlive() }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "系统设置与保活",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(vertical = 8.dp)
        )

        // MQTT 心跳间隔（间隔越长越省电，修改后自动重连生效）
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "MQTT 心跳间隔",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "间隔越长越省电，但断线发现越慢。修改后自动重连生效。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(120, 300, 600, 900).forEach { seconds ->
                        FilterChip(
                            selected = keepAlive == seconds,
                            onClick = {
                                keepAlive = seconds
                                scope.launch {
                                    app.mqttConfigStore.saveKeepAlive(seconds)
                                    val broker = app.mqttConfigStore.savedBrokerUrl()
                                    val clientId = app.mqttConfigStore.savedClientId()
                                    if (!broker.isNullOrBlank() && !clientId.isNullOrBlank()) {
                                        val username = app.mqttConfigStore.savedUsername()
                                        val password = app.mqttConfigStore.savedPassword()
                                        val caCertPem = app.mqttConfigStore.savedCaCertPem()
                                        val willTopic = app.presenceManager.willTopic()
                                        MqttConnection.manager.disconnect()
                                        delay(500)
                                        MqttConnection.manager.connect(
                                            MqttConfig(
                                                brokerUrl = broker, clientId = clientId,
                                                username = username, password = password,
                                                keepAliveSeconds = seconds,
                                                willTopic = willTopic, willPayload = "offline",
                                                caCertPem = caCertPem
                                            )
                                        )
                                    }
                                }
                            },
                            label = { Text("${seconds}秒") }
                        )
                    }
                }
            }
        }

        OutlinedCard(
            modifier = Modifier.fillMaxWidth(),
            onClick = { context.startActivity(KeepAliveGuide.batteryOptimizationIntent(context)) }
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.BatteryFull,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(12.dp))
                Text("电池优化白名单", style = MaterialTheme.typography.bodyLarge)
            }
        }

        OutlinedCard(
            modifier = Modifier.fillMaxWidth(),
            onClick = { context.startActivity(KeepAliveGuide.autoStartIntent(context)) }
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.PowerSettingsNew,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(12.dp))
                Text("自启动设置", style = MaterialTheme.typography.bodyLarge)
            }
        }

        if (romWarning != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFEBEE))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = null,
                            tint = Color(0xFFC62828),
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            " 系统注意事项",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color(0xFFC62828)
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        romWarning,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF424242)
                    )
                }
            }
        }

        if (romInstructions != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "$romType 保活指南",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color(0xFFE65100)
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        romInstructions,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF424242)
                    )
                }
            }
        }
    }
}
