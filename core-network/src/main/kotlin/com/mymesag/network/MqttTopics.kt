package com.mymesag.network

/**
 * 主题构造（对应项目书 3.3 表 7）。
 * 发布方使用自身 deviceId；订阅方按 userId 前缀的订阅表达式接收。
 */
object MqttTopics {

    fun notify(userId: String, deviceId: String) = "notify/$userId/$deviceId"
    fun notifyWildcard(userId: String) = "notify/$userId/+"

    fun reply(userId: String, deviceId: String) = "reply/$userId/$deviceId"
    fun replyWildcard(userId: String) = "reply/$userId/+"

    fun ack(userId: String, deviceId: String) = "ack/$userId/$deviceId"
    fun ackWildcard(userId: String) = "ack/$userId/+"

    fun call(userId: String, deviceId: String) = "call/$userId/$deviceId"
    fun callWildcard(userId: String) = "call/$userId/+"

    fun presence(userId: String, deviceId: String) = "presence/$userId/$deviceId"
    fun presenceWildcard(userId: String) = "presence/$userId/+"

    fun config(userId: String, deviceId: String) = "config/$userId/$deviceId"
    fun configWildcard(userId: String) = "config/$userId/+"

    fun pair(userId: String) = "pair/$userId"

    fun missedCallReply(userId: String, deviceId: String) = "missedcall/$userId/$deviceId"
    fun missedCallReplyWildcard(userId: String) = "missedcall/$userId/+"
}
