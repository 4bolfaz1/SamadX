package ir.abdev.samadx.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.Window
import android.view.WindowManager
import ir.abdev.samadx.util.Fa
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.TextView
import androidx.core.view.isVisible
import kotlin.math.roundToInt
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import ir.abdev.samadx.Graph
import ir.abdev.samadx.LoginActivity
import ir.abdev.samadx.R
import ir.abdev.samadx.data.ApiException
import ir.abdev.samadx.data.AuthRequiredException
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlin.coroutines.cancellation.CancellationException

/** از اندروید ۱۵ به بعد برنامه لبه‌به‌لبه است؛ این تابع فاصله‌ی نوار وضعیت/ناوبری/کیبورد را به ریشه می‌دهد. */
fun View.applySystemBarsPadding() {
    val l = paddingLeft
    val t = paddingTop
    val r = paddingRight
    val b = paddingBottom
    ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
        val bars = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or
                WindowInsetsCompat.Type.displayCutout() or
                WindowInsetsCompat.Type.ime()
        )
        v.setPadding(l + bars.left, t + bars.top, r + bars.right, b + bars.bottom)
        WindowInsetsCompat.CONSUMED
    }
}

/** پیام کوتاه بالای نوار ناوبری پایین. */
fun Fragment.snack(message: String) {
    val host = activity?.findViewById<View>(android.R.id.content) ?: return
    val bar = Snackbar.make(host, message, Snackbar.LENGTH_LONG)
    activity?.findViewById<View>(R.id.bottom_nav)?.let { bar.setAnchorView(it) }
    bar.show()
}

/** نشست این حساب نامعتبر شده (مثلاً رمز عوض شده): صفحه‌ی ورود با نام کاربری پر‌شده؛ کش‌ها می‌مانند. */
fun Fragment.forceLogin(message: String?) {
    val ctx = context ?: return
    startActivity(LoginActivity.reloginIntent(ctx, message))
}

/**
 * اجرای یک عملیات شبکه با مدیریت یکسان خطا:
 * - نیاز به ورود دوباره → صفحه‌ی ورود
 * - خطای سرور/شبکه → onError با پیام فارسی
 * در همه‌ی این حالت‌ها null برمی‌گردد.
 */
suspend fun <T> Fragment.safely(onError: (String) -> Unit = { snack(it) }, block: suspend () -> T): T? {
    return try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: AuthRequiredException) {
        forceLogin(e.message)
        null
    } catch (e: ApiException) {
        onError(e.userMessage)
        null
    } catch (e: Exception) {
        onError(getString(R.string.error_unexpected, e.message ?: e.javaClass.simpleName))
        null
    }
}

/** دیالوگ را راست‌به‌چپ نشان می‌دهد (عنوان و دکمه‌ها از راست)، مستقل از تنظیم زبان پنجره. */
fun MaterialAlertDialogBuilder.showRtl(): AlertDialog {
    val dialog = create()
    dialog.window?.decorView?.layoutDirection = View.LAYOUT_DIRECTION_RTL
    dialog.show()
    return dialog
}

/** متن نوار «نمایش از کش»؛ error خالی یعنی فقط کش ترجیح داده شده است. */
fun Fragment.cacheNoticeText(cachedAt: Long, error: String?): String =
    if (error == null) getString(R.string.cache_notice_plain, Fa.lastUpdate(cachedAt))
    else getString(R.string.cache_notice_error, error, Fa.lastUpdate(cachedAt))

/**
 * افزایش موقت نور صفحه برای اسکن QR: با boost() نور کامل می‌شود و بعد از ۵ ثانیه (یا با restore())
 * به حالت عادی برمی‌گردد.
 */
class BrightnessBoost(private val window: () -> Window?) {
    private val handler = Handler(Looper.getMainLooper())
    private val restoreTask = Runnable { restore() }

    fun boost() {
        set(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL)
        handler.removeCallbacks(restoreTask)
        handler.postDelayed(restoreTask, BOOST_MS)
    }

    fun restore() {
        handler.removeCallbacks(restoreTask)
        set(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
    }

    private fun set(value: Float) {
        window()?.let { w -> w.attributes = w.attributes.also { it.screenBrightness = value } }
    }

    private companion object {
        const val BOOST_MS = 5_000L
    }
}

/** نوار پیشرفت بالای MainActivity را برای این صفحه روشن/خاموش می‌کند. */
fun Fragment.setLoading(on: Boolean) {
    (activity as? ir.abdev.samadx.MainActivity)?.setLoading(this, on)
}

/**
 * نوار «آخرین به‌روزرسانی»: ظاهر/ناپدید شدن و تغییر طول متنش نرم انجام می‌شود
 * (ارتفاع، فاصله‌ی پایین و شفافیت با هم تغییر می‌کنند) تا هر چه زیر آن است یک‌باره نپرد.
 * update(null) = پنهان. اگر هنوز چیدمان صفحه انجام نشده، بدون انیمیشن و فوری نشان داده می‌شود.
 */
class NoticeBar(private val view: TextView) {
    private var animator: ValueAnimator? = null
    private var showing = false
    private val fullMargin = (view.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin

    fun update(text: CharSequence?) {
        if (text == null) hide() else show(text)
    }

    private fun lp() = view.layoutParams as ViewGroup.MarginLayoutParams

    private fun show(text: CharSequence) {
        if (showing && view.text.toString() == text.toString()) return
        val parent = view.parent as? View
        val l = lp()
        val width = (parent?.let { it.width - it.paddingLeft - it.paddingRight } ?: 0) - l.marginStart - l.marginEnd
        if (width <= 0) { snap(text); return }

        val wasVisible = view.isVisible
        val fromH = if (wasVisible) view.height else 0
        val fromMargin = if (wasVisible) l.bottomMargin else 0
        val fromAlpha = if (wasVisible) view.alpha else 0f
        view.text = text
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        showing = true
        view.isVisible = true
        run(fromH, view.measuredHeight, fromMargin, fullMargin, fromAlpha, 1f) { reset() }
    }

    private fun hide() {
        if (!showing) {
            if (view.isVisible && animator == null) snap(null)
            return
        }
        showing = false
        if (!view.isVisible || view.height == 0) { snap(null); return }
        run(view.height, 0, lp().bottomMargin, 0, view.alpha, 0f) {
            view.isVisible = false
            reset()
        }
    }

    private fun snap(text: CharSequence?) {
        animator?.cancel()
        animator = null
        if (text != null) view.text = text
        view.isVisible = text != null
        showing = text != null
        reset()
    }

    private fun reset() {
        val l = lp()
        l.height = ViewGroup.LayoutParams.WRAP_CONTENT
        l.bottomMargin = fullMargin
        view.layoutParams = l
        view.alpha = 1f
    }

    private fun run(h0: Int, h1: Int, m0: Int, m1: Int, a0: Float, a1: Float, onEnd: () -> Unit) {
        animator?.cancel()
        val l = lp()
        val anim = ValueAnimator.ofFloat(0f, 1f)
        anim.duration = 280
        anim.interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
        anim.addUpdateListener {
            val t = it.animatedValue as Float
            l.height = (h0 + (h1 - h0) * t).roundToInt()
            l.bottomMargin = (m0 + (m1 - m0) * t).roundToInt()
            view.alpha = a0 + (a1 - a0) * t
            view.layoutParams = l
        }
        anim.addListener(object : AnimatorListenerAdapter() {
            private var cancelled = false
            override fun onAnimationCancel(a: Animator) { cancelled = true }
            override fun onAnimationEnd(a: Animator) {
                if (cancelled) return
                animator = null
                onEnd()
            }
        })
        animator = anim
        anim.start()
    }
}
