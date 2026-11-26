package com.mymesag.network

import com.mymesag.crypto.SessionKeys

/**
 * 主题构造：只有「通道名 + HMAC(groupToken, channel)」两段，不含任何设备标识。
 * 组内所有设备订阅同一个主题，收件人由密文内的事件字段判定，broker 无法据此画出设备关系图谱。
 * groupToken 只在配对双方之间共享；通道名同时用于信封 AAD 绑定，密文无法被搬到别的通道重放。
 */
object MqttTopics {

    const val CH_NOTIFY = "notify"
    const val CH_REPLY = "reply"
    const val CH_MARKREAD = "markread"
    const val CH_ACK = "ack"
    const val CH_CALL = "call"
    const val CH_CONFIG = "config"
    const val CH_SYNCREQ = "syncreq"
    const val CH_PAIR = "pair"

    fun notify(groupToken: String) = "$CH_NOTIFY/${token(groupToken, CH_NOTIFY)}"

    fun reply(groupToken: String) = "$CH_REPLY/${token(groupToken, CH_REPLY)}"

    fun markRead(groupToken: String) = "$CH_MARKREAD/${token(groupToken, CH_MARKREAD)}"

    fun ack(groupToken: String) = "$CH_ACK/${token(groupToken, CH_ACK)}"

    fun call(groupToken: String) = "$CH_CALL/${token(groupToken, CH_CALL)}"

    fun config(groupToken: String) = "$CH_CONFIG/${token(groupToken, CH_CONFIG)}"

    fun syncReq(groupToken: String) = "$CH_SYNCREQ/${token(groupToken, CH_SYNCREQ)}"

    fun pair(groupToken: String) = "$CH_PAIR/${token(groupToken, CH_PAIR)}"

    private fun token(groupToken: String, channel: String) = SessionKeys.topicToken(groupToken, channel)
}