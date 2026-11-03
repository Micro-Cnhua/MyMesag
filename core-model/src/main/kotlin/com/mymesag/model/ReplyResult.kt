package com.mymesag.model

/** 回执原因码（项目书 5.3 表 9）。 */
object ReplyResult {
    const val OK = "OK"
    const val NOT_FOUND = "NOT_FOUND"
    const val NO_REPLY_ACTION = "NO_REPLY_ACTION"
    const val OFFLINE_TIMEOUT = "OFFLINE_TIMEOUT"
    const val DECRYPT_FAILED = "DECRYPT_FAILED"
    const val SEQ_REPLAY = "SEQ_REPLAY"
    const val NOT_PAIRED = "NOT_PAIRED"
    const val UNSUPPORTED_VERSION = "UNSUPPORTED_VERSION"
}
