package com.mymesag.ui

import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.DevicesOther
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.mymesag.MyMesagApp
import com.mymesag.data.MqttConnection
import com.mymesag.network.MqttState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as MyMesagApp
    val scope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Main) }

    var mqttState by remember { mutableStateOf(MqttConnection.manager.state.value) }
    var notificationAccess by remember { mutableStateOf(false) }
    var pairedDevices by remember { mutableStateOf(0) }
    var savedBroker by remember { mutableStateOf<String?>(null) }
    var romType by remember { mutableStateOf("") }
    var romInstructions by remember { mutableStateOf<String?>(null) }
    var romWarning by remember { mutableStateOf<String?>(null) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                mqttState = MqttConnection.manager.state.value
                notificationAccess = com.mymesag.notification.NotificationAccess.isGranted(context)
                romType = com.mymesag.notification.RomCompatibility.detectRomType().name
                romInstructions = com.mymesag.notification.RomCompatibility.getKeepAliveInstructions(context)
                romWarning = com.mymesag.notification.RomCompatibility.getRomSpecificWarnings()
                scope.launch {
                    pairedDevices = app.pairingManager.pairedDeviceIds().size
                    savedBroker = app.mqttConfigStore.savedBrokerUrl()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    DisposableEffect(Unit) {
        val job = scope.launch {
            MqttConnection.manager.state.collect { mqttState = it }
        }
        onDispose { job.cancel() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("诊断信息") },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            StatusItem(
                icon = if (mqttState == MqttState.CONNECTED) Icons.Default.CloudDone else Icons.Default.CloudOff,
                title = "MQTT 连接",
                status = when (mqttState) {
                    MqttState.CONNECTED -> "已连接"
                    MqttState.CONNECTING -> "连接中..."
                    MqttState.RECONNECTING -> "重连中..."
                    MqttState.DISCONNECTED -> "未连接"
                    MqttState.ERROR -> "错误"
                },
                isOk = mqttState == MqttState.CONNECTED
            )
            Spacer(Modifier.height(8.dp))
            StatusItem(
                icon = if (notificationAccess) Icons.Default.Notifications else Icons.Default.NotificationsOff,
                title = "通知使用权",
                status = if (notificationAccess) "已开启" else "未开启",
                isOk = notificationAccess
            )
            Spacer(Modifier.height(8.dp))
            StatusItem(
                icon = Icons.Default.DevicesOther,
                title = "配对设备",
                status = "$pairedDevices 台设备",
                isOk = pairedDevices > 0
            )
            Spacer(Modifier.height(8.dp))
            StatusItem(
                icon = Icons.Default.Router,
                title = "MQTT 服务器",
                status = savedBroker ?: "未配置",
                isOk = !savedBroker.isNullOrBlank()
            )

            Spacer(Modifier.height(16.dp))

            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("操作", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    MqttConnection.manager.disconnect()
                                    delay(500)
                                    val broker = app.mqttConfigStore.savedBrokerUrl()
                                    val clientId = app.mqttConfigStore.savedClientId()
                                    val username = app.mqttConfigStore.savedUsername()
                                    val password = app.mqttConfigStore.savedPassword()
                                    val caCertPem = app.mqttConfigStore.savedCaCertPem()
                                    val willTopic = app.presenceManager.willTopic()
                                    if (!broker.isNullOrBlank() && !clientId.isNullOrBlank()) {
                                        MqttConnection.manager.connect(
                                            com.mymesag.network.MqttConfig(
                                                brokerUrl = broker,
                                                clientId = clientId,
                                                username = username,
                                                password = password,
                                                willTopic = willTopic,
                                                willPayload = "offline",
                                                caCertPem = caCertPem
                                            )
                                        )
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Text("  强制重连")
                        }
                        Button(
                            onClick = { com.mymesag.notification.NotificationAccess.openSettings(context) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Notifications, contentDescription = null, modifier = Modifier.size(16.dp))
                            Text("  通知权限")
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                val userId = app.deviceIdentity.userId()
                                val deviceId = app.deviceIdentity.deviceId()
                                val topic = "test/$userId/$deviceId"
                                val payload = "Diagnostic test at ${System.currentTimeMillis()}"
                                MqttConnection.manager.publish(topic, payload, qos = 1)
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("测试发布消息") }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            val intent = android.content.Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(intent)
                            android.widget.Toast.makeText(
                                context,
                                "请在通知使用权设置中：先关闭 MyMesag，等待 3 秒，再重新开启",
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("重新绑定通知监听器") }
                }
            }

            if (romWarning != null) {
                Spacer(Modifier.height(12.dp))
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
                            romWarning!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF424242)
                        )
                    }
                }
            }

            if (romInstructions != null) {
                Spacer(Modifier.height(12.dp))
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
                            romInstructions!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF424242)
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("排查指南", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(8.dp))
                    if (mqttState != MqttState.CONNECTED) {
                        Text(
                            "MQTT 未连接：检查网络连接，确认 MQTT 服务器地址和账号密码正确",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFC62828)
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                    if (!notificationAccess) {
                        Text(
                            "通知使用权未开启：点击「通知权限」按钮开启",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFC62828)
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                    if (pairedDevices == 0) {
                        Text(
                            "未配对设备：请先进行设备配对",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFC62828)
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                    Text(
                        "如果以上都正常但仍无法收发：\n" +
                            "1. 手机设置搜索「自启动」，确保 MyMesag 已开启\n" +
                            "2. 手机设置搜索「电池优化」，将 MyMesag 设为「不优化」\n" +
                            "3. 查看 Logcat 日志，搜索 MqttManager / MyMesagApp 标签",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun StatusItem(
    icon: ImageVector,
    title: String,
    status: String,
    isOk: Boolean
) {
    val statusColor = if (isOk) Color(0xFF2E7D32) else Color(0xFFC62828)
    val statusIcon = if (isOk) Icons.Default.CheckCircle else Icons.Default.Error
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
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
                modifier = Modifier.size(28.dp)
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp)
            ) {
                Text(title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(status, style = MaterialTheme.typography.bodyLarge)
            }
            Icon(statusIcon, contentDescription = null, tint = statusColor, modifier = Modifier.size(22.dp))
        }
    }
}
