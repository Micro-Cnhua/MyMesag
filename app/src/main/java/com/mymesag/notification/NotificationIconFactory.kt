package com.mymesag.notification

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.net.Uri
import android.provider.Telephony
import android.telecom.TelecomManager
import android.util.LruCache
import androidx.core.content.ContextCompat
import com.mymesag.R
import com.mymesag.reply.ReplyConstants
import kotlin.math.roundToInt

/**
 * B 端同步通知的大图标工厂：本地取源应用图标作为大图标，按包名缓存，重复通知零开销。
 * 源应用未安装时按功能类别（拨号/联系人/短信）匹配本机默认应用图标，各厂商包名不同也能对上；
 * 仍取不到则返回 null，由调用方回退到 MyMesag 默认图标。
 * 全程本地操作，不联网；含 PackageManager IPC 与位图渲染，请在 IO 线程调用。
 */
object NotificationIconFactory {

    private const val CACHE_SIZE = 40
    private const val ICON_DP = 64f

    private val cache = LruCache<String, Bitmap>(CACHE_SIZE)
    private val missed = LruCache<String, Boolean>(CACHE_SIZE)

    @Volatile
    private var defaultBitmap: Bitmap? = null

    /** 源应用图标，未安装时回退本机同类默认应用图标。都取不到时返回 null。 */
    fun sourceIcon(context: Context, pkg: String, isCall: Boolean = false): Bitmap? {
        val key = "$pkg|$isCall"
        cache.get(key)?.let { return it }
        if (missed.get(key) != null) return null
        val size = dpToPx(context, ICON_DP)
        val bmp = loadIcon(context, pkg, size)
            ?: loadIcon(context, equivalentLocalPackage(context, pkg, isCall), size)
        if (bmp == null) {
            missed.put(key, true)
            return null
        }
        cache.put(key, bmp)
        return bmp
    }

    /** MyMesag 默认大图标（源应用与本机同类应用都取不到时回退）。 */
    fun defaultIcon(context: Context): Bitmap? {
        defaultBitmap?.let { return it }
        val size = dpToPx(context, ICON_DP)
        val drawable = ContextCompat.getDrawable(context, R.mipmap.ic_launcher) ?: return null
        val bmp = drawableToBitmap(drawable, size) ?: return null
        defaultBitmap = bmp
        return bmp
    }

    private fun loadIcon(context: Context, pkg: String?, size: Int): Bitmap? {
        if (pkg.isNullOrBlank()) return null
        return try {
            drawableToBitmap(context.packageManager.getApplicationIcon(pkg), size)
        } catch (e: PackageManager.NameNotFoundException) {
            null
        } catch (e: Exception) {
            null
        }
    }

    /** 远端包名 → 本机同类应用包名：按系统默认应用/角色解析，不依赖厂商包名一致。 */
    private fun equivalentLocalPackage(context: Context, pkg: String, isCall: Boolean): String? = when {
        isCall || pkg in DIALER_PKGS -> defaultDialer(context)
        pkg in CONTACTS_PKGS -> resolveCategory(context, Intent.CATEGORY_APP_CONTACTS)
        ReplyConstants.isSms(pkg) ->
            defaultSms(context) ?: resolveCategory(context, Intent.CATEGORY_APP_MESSAGING)
        else -> null
    }

    private fun defaultDialer(context: Context): String? {
        val role = runCatching {
            context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage
        }.getOrNull()
        // 仅用于解析包名，不会真的拨出
        return role?.takeIf { it.isNotBlank() }
            ?: resolveIntent(context, Intent(Intent.ACTION_DIAL, Uri.parse("tel:")))
    }

    private fun defaultSms(context: Context): String? = runCatching {
        Telephony.Sms.getDefaultSmsPackage(context)
    }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun resolveCategory(context: Context, category: String): String? =
        resolveIntent(context, Intent(Intent.ACTION_MAIN).addCategory(category))

    private fun resolveIntent(context: Context, intent: Intent): String? = runCatching {
        context.packageManager.resolveActivity(intent, 0)?.activityInfo?.packageName
    }.getOrNull()

    private fun drawableToBitmap(drawable: Drawable, size: Int): Bitmap? = try {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(canvas)
        bmp
    } catch (e: Exception) {
        null
    }

    private fun dpToPx(context: Context, dp: Float): Int =
        (dp * context.resources.displayMetrics.density).roundToInt().coerceAtLeast(1)

    /** 拨号/来电类应用包名（各厂商）。 */
    val DIALER_PKGS = setOf(
        // AOSP / 小米 / OPPO / vivo
        "com.android.incallui", "com.android.dialer", "com.android.phone",
        "com.android.server.telecom",
        // 谷歌（Pixel 及海外多数机型的 Google Dialer）
        "com.google.android.dialer",
        // 三星 OneUI
        "com.samsung.android.dialer", "com.samsung.android.incallui",
        "com.samsung.android.app.telephoneui",
        // 荣耀
        "com.hihonor.incallui"
    )

    /** 联系人/通讯录类应用包名（华为/荣耀拨号集成在联系人）。 */
    val CONTACTS_PKGS = setOf(
        "com.android.contacts", "com.google.android.contacts",
        "com.samsung.android.contacts", "com.huawei.contacts", "com.hihonor.contacts"
    )

    /** 来电/拨号/联系人类应用包名兜底列表（主判据为通知类别 CATEGORY_CALL）：点击跳回本机拨号器并预填号码。 */
    val CALL_APP_PKGS = DIALER_PKGS + CONTACTS_PKGS
}