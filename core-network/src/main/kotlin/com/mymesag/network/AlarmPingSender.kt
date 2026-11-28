package com.mymesag.network

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import org.eclipse.paho.client.mqttv3.MqttPingSender
import org.eclipse.paho.client.mqttv3.internal.ClientComms
import java.util.concurrent.atomic.AtomicInteger

/**
 * 唤醒型心跳发送器。Paho 默认的 TimerPingSender 基于 java.util.Timer，CPU 深睡期间不触发，
 * 心跳要等下次唤醒才补发，broker 在约 1.5×keepAlive 后判定超时踢线。改用 AlarmManager 的
 * allow-while-idle 闹钟，Doze 中也能按时唤醒执行 checkForActivity。
 *
 * 只换发送器仍不够：Paho 的存活判定默认用 System.nanoTime()（CLOCK_MONOTONIC，内核 suspend
 * 期间不推进），醒来后会误判「还没到点」而只重排闹钟不发实包，需配合 MqttManager 的
 * BootTimeTimer 才真正生效。代价是每个心跳周期多一次强制唤醒，keepAlive 越长唤醒越少。
 */
class AlarmPingSender(context: Context) : MqttPingSender {

    private val appContext = context.applicationContext
    private val alarmManager = appContext.getSystemService(AlarmManager::class.java)
    private val thread = HandlerThread("mqtt-ping").apply { start() }
    private val handler = Handler(thread.looper)

    private val action = "$PING_ACTION.${SEQ.incrementAndGet()}"
    private val pendingIntent: PendingIntent = PendingIntent.getBroadcast(
        appContext, 0,
        Intent(action).setPackage(appContext.packageName),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private var comms: ClientComms? = null

    @Volatile
    private var stopped = false

    @Volatile
    private var exactDenied = false

    /** 最近一次心跳检查真正执行的时刻（elapsedRealtime），用于判断心跳循环是否被系统冻住。 */
    @Volatile
    var lastRunAt: Long = 0L
        private set

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (stopped) return
            // checkForActivity 会写 socket，不能在主线程做；goAsync 保住广播期间的进程优先级
            val pending = goAsync()
            val posted = handler.post {
                try {
                    lastRunAt = SystemClock.elapsedRealtime()
                    comms?.checkForActivity()
                    Log.d(TAG, "ping tick ran, comms bound=${comms != null}")
                } catch (e: Exception) {
                    Log.w(TAG, "checkForActivity failed: ${e.message}")
                } finally {
                    pending.finish()
                }
            }
            if (!posted) pending.finish()
        }
    }

    init {
        val filter = IntentFilter(action)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            appContext.registerReceiver(receiver, filter)
        }
    }

    override fun init(comms: ClientComms) {
        this.comms = comms
    }

    override fun start() {
        lastRunAt = SystemClock.elapsedRealtime()
        val delay = comms?.keepAlive ?: DEFAULT_KEEPALIVE_MS
        Log.d(TAG, "start: schedule ping in ${delay}ms")
        schedule(delay)
    }

    override fun stop() {
        if (stopped) return
        stopped = true
        alarmManager.cancel(pendingIntent)
        try {
            appContext.unregisterReceiver(receiver)
        } catch (e: IllegalArgumentException) {
            // 已注销
        }
        thread.quitSafely()
    }

    override fun schedule(delayInMilliseconds: Long) {
        if (stopped) return
        val triggerAt = SystemClock.elapsedRealtime() + delayInMilliseconds.coerceAtLeast(1L)
        alarmManager.cancel(pendingIntent)
        if (exactDenied) {
            setInexact(triggerAt)
            return
        }
        try {
            // 电源白名单内的应用无需 SCHEDULE_EXACT_ALARM 即可调用；否则可能抛 SecurityException
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent
            )
        } catch (e: SecurityException) {
            exactDenied = true
            Log.w(TAG, "exact alarm denied, fallback to inexact allow-while-idle: ${e.message}")
            setInexact(triggerAt)
        }
    }

    /** 降级路径：仍能在 Doze 中触发，但会被系统合并延后（约 9 分钟一次），长心跳才够用。 */
    private fun setInexact(triggerAt: Long) {
        alarmManager.setAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent
        )
    }

    companion object {
        private const val TAG = "AlarmPingSender"
        private const val PING_ACTION = "com.mymesag.network.MQTT_PING"
        private const val DEFAULT_KEEPALIVE_MS = 60_000L
        private val SEQ = AtomicInteger(0)
    }
}