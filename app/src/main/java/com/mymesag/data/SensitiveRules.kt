package com.mymesag.data

/**
 * 默认敏感规则（项目书 2.2）：银行类按包名关键词，验证码类按标题/正文关键词。
 * 【硬约束】敏感应用排除优先于白名单：命中即不得同步。
 */
object SensitiveRules {

    private val bankPkgKeywords = listOf(
        "bank", "pay", "wallet", "alipay", "unionpay", "云闪付", "银行", "金融"
    )

    private val verificationKeywords = listOf(
        "验证码", "校验码", "动态码", "动态口令", "verification code"
    )

    fun isSensitive(pkg: String, title: String?, text: String?, allowVerification: Boolean = false): Boolean {
        val pkgLower = pkg.lowercase()
        if (bankPkgKeywords.any { pkgLower.contains(it) }) return true
        if (allowVerification) return false
        val content = "${title ?: ""} ${text ?: ""}"
        return verificationKeywords.any { content.contains(it) }
    }
}
