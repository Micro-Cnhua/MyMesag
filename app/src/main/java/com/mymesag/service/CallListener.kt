package com.mymesag.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.mymesag.data.DeviceIdentity
import com.mymesag.model.CallEvent
import com.mymesag.network.MqttManager
import com.mymesag.network.MqttTopics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * A 端来电监听（阶段 5 / 项目书 2.4、10.1）：
 * - API 31+ 用 TelephonyCallback，API 26–30 用 PhoneStateListener + listen()；
 * - 取号采用方案一（READ_CALL_LOG 读 CallLog，API 29+ phoneNumber 恒为 null），无权限时降级为不含号码（方案三）。
 */
class CallListener(
    private val context: Context,
    private val manager: MqttManager,
    private val deviceIdentity: DeviceIdentity
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { encodeDefaults = true }
    private val tm = context.getSystemService(TelephonyManager::class.java)

    @Volatile
    private var currentCallId: String? = null

    // API 31+ 的 TelephonyCallback 需保持强引用，否则会被 GC 导致收不到回调
    private var telephonyCallbackRef: Any? = null

    fun start() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // TelephonyCallback 是 API 31+ 类，必须在此分支内局部创建，避免旧版本加载时报 NoClassDefFoundError
                val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                    override fun onCallStateChanged(state: Int) = handleState(state)
                }
                telephonyCallbackRef = callback
                tm.registerTelephonyCallback(ContextCompat.getMainExecutor(context), callback)
            } else {
                @Suppress("DEPRECATION")
                tm.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE)
            }
            Log.d(TAG, "call listener started (sdk=${Build.VERSION.SDK_INT})")
        } catch (e: Exception) {
            Log.e(TAG, "start error", e)
        }
    }

    @Suppress("DEPRECATION")
    private val phoneStateListener = object : PhoneStateListener() {
        override fun onCallStateChanged(state: Int, phoneNumber: String?) = handleState(state, phoneNumber)
    }

    private fun handleState(state: Int, phoneNumber: String? = null) {
        when (state) {
            TelephonyManager.CALL_STATE_RINGING -> {
                currentCallId = UUID.randomUUID().toString()
                emit(currentCallId!!, "ringing", phoneNumber)
            }
            TelephonyManager.CALL_STATE_IDLE -> {
                // 通话结束：下发结束事件，让 B 端撤下「来电」通知
                currentCallId?.let { id ->
                    emit(id, "ended", null)
                    currentCallId = null
                }
            }
            else -> Unit // OFFHOOK 等状态可选择性通知，阶段 5 暂只处理响铃
        }
    }

    private fun emit(callId: String, state: String, phoneNumber: String?) {
        scope.launch {
            // 只用系统回调的 phoneNumber（API<29 才有）；CallLog 取号在响铃时拿到的是上一次通话，故弃用
            val number = phoneNumber?.takeIf { it.isNotBlank() }
            val contactName = resolveContactName(number)
            val userId = deviceIdentity.userId()
            val deviceId = deviceIdentity.deviceId()
            val event = CallEvent(
                id = callId,
                from = deviceId,
                ts = System.currentTimeMillis(),
                seq = System.currentTimeMillis(),
                state = state,
                number = number,
                contactName = contactName,
                subscriptionId = null
            )
            val payload = json.encodeToString(CallEvent.serializer(), event)
            if (manager.isConnected) {
                manager.publish(MqttTopics.call(userId, deviceId), payload)
                Log.d(TAG, "call event state=$state number=$number contact=$contactName")
            } else {
                Log.d(TAG, "not connected, skip call event state=$state")
            }
        }
    }

    /** READ_CONTACTS 将号码解析为联系人姓名。无权限或无匹配时返回 null。 */
    private fun resolveContactName(number: String?): String? {
        if (number.isNullOrEmpty()) return null
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }
        return runCatching {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            val projection = arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME)
            val cursor = context.contentResolver.query(uri, projection, null, null, null)
            cursor?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull()
    }

    companion object {
        private const val TAG = "CallListener"
    }
}
