package com.mymesag.network

import com.mymesag.crypto.SessionKeys

/**
 * 主题构造（对应项目书 3.3 表 7）。
 * 路由段为 HMAC(groupToken, channel)，不再暴露 userId；发布方使用自身 deviceId，
 * 订阅方按通配表达式接收。groupToken 只在配对双方之间共享。
 */
object MqttTopics {

    fun notify(groupToken: String, deviceId: String) = "notify/${token(groupToken, "notify")}/$deviceId"
    fun notifyWildcard(groupToken: String) = "notify/${token(groupToken, "notify")}/+"

    fun reply(groupToken: String, deviceId: String) = "reply/${token(groupToken, "reply")}/$deviceId"
    fun replyWildcard(groupToken: String) = "reply/${token(groupToken, "reply")}/+"

    fun markRead(groupToken: String, deviceId: String) = "markread/${token(groupToken, "markread")}/$deviceId"
    fun markReadWildcard(groupToken: String) = "markread/${token(groupToken, "markread")}/+"

    fun ack(groupToken: String, deviceId: String) = "ack/${token(groupToken, "ack")}/$deviceId"
    fun ackWildcard(groupToken: String) = "ack/${token(groupToken, "ack")}/+"

    fun call(groupToken: String, deviceId: String) = "call/${token(groupToken, "call")}/$deviceId"
    fun callWildcard(groupToken: String) = "call/${token(groupToken, "call")}/+"

    fun config(groupToken: String, deviceId: String) = "config/${token(groupToken, "config")}/$deviceId"
    fun configWildcard(groupToken: String) = "config/${token(groupToken, "config")}/+"

    fun syncReq(groupToken: String, deviceId: String) = "syncreq/${token(groupToken, "syncreq")}/$deviceId"
    fun syncReqWildcard(groupToken: String) = "syncreq/${token(groupToken, "syncreq")}/+"

    fun pair(groupToken: String) = "pair/${token(groupToken, "pair")}"

    private fun token(groupToken: String, channel: String) = SessionKeys.topicToken(groupToken, channel)
}