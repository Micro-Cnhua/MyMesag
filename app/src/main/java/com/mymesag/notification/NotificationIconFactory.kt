package com.mymesag.notification

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.util.LruCache
import androidx.core.content.ContextCompat
import com.mymesag.R
import kotlin.math.roundToInt

/**
 * B 端同步通知的大图标工厂：本地取源应用图标作为大图标，按包名缓存，重复通知零开销。
 * 源应用未安装 / 取图标异常时返回 null，由调用方回退到 MyMesag 默认图标。
 * 全程本地操作，不联网；含 PackageManager IPC 与位图渲染，请在 IO 线程调用。
 */
object NotificationIconFactory {

    private const val CACHE_SIZE = 40
    private const val ICON_DP = 64f

    private val cache = LruCache<String, Bitmap>(CACHE_SIZE)

    @Volatile
    private var defaultBitmap: Bitmap? = null

    /** 源应用图标。未安装或异常时返回 null。 */
    fun sourceIcon(context: Context, pkg: String): Bitmap? {
        cache.get(pkg)?.let { return it }
        val size = dpToPx(context, ICON_DP)
        val bmp = try {
            val drawable = context.packageManager.getApplicationIcon(pkg)
            drawableToBitmap(drawable, size)
        } catch (e: PackageManager.NameNotFoundException) {
            null
        } catch (e: Exception) {
            null
        } ?: return null
        cache.put(pkg, bmp)
        return bmp
    }

    /** MyMesag 默认大图标（源应用未安装时回退）。 */
    fun defaultIcon(context: Context): Bitmap? {
        defaultBitmap?.let { return it }
        val size = dpToPx(context, ICON_DP)
        val drawable = ContextCompat.getDrawable(context, R.mipmap.ic_launcher) ?: return null
        val bmp = drawableToBitmap(drawable, size) ?: return null
        defaultBitmap = bmp
        return bmp
    }

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
}
