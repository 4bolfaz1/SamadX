package ir.abdev.samadx.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.MaterialColors
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * جای‌نگهدار QR تا رسیدن کد: شبکه‌ی ماژول‌های شبیه QR (با سه چشمی گوشه‌ها) که موجی مورب
 * از رنگ اصلی تم روی آن می‌گذرد. فقط وقتی دیده می‌شود انیمیشن اجرا می‌شود.
 * با failed = true به‌جای انیمیشن، تصویر ترک‌خورده‌ی خطا نشان داده می‌شود.
 */
class QrLoadingView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val base = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant)
    private val accent = MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary)
    private val error = MaterialColors.getColor(this, androidx.appcompat.R.attr.colorError)
    private var phase = 0f

    /** حالت خطا: QR ترک‌خورده و بی‌جان (بدون انیمیشن موج) که نشان می‌دهد کد دریافت نشد. */
    var failed: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            updateRunning()
            invalidate()
        }
    private var animator: ValueAnimator? = null

    private val cells: Array<BooleanArray> = buildPattern()
    private val finder: Array<BooleanArray> = Array(N) { y -> BooleanArray(N) { x -> inFinder(x, y) } }

    override fun onDraw(canvas: Canvas) {
        val pad = 14f * resources.displayMetrics.density
        val side = min(width, height) - 2 * pad
        if (side <= 0f) return
        val cell = side / N
        val left = (width - side) / 2f
        val top = (height - side) / 2f
        for (y in 0 until N) for (x in 0 until N) {
            if (!cells[y][x]) continue
            if (failed) {
                drawBroken(canvas, x, y, left, top, cell)
                continue
            }
            // موج مورب: هر ماژول بر اساس فاصله‌اش از گوشه‌ی بالا-راست، با تأخیر روشن می‌شود
            val d = (x + (N - 1 - y)) / (2f * (N - 1))
            val w = 0.5f + 0.5f * sin(2.0 * PI * (phase - d)).toFloat()
            val isFinder = finder[y][x]
            // ماژول‌های معمولی با رسیدن موج کمی بزرگ‌تر و پررنگ‌تر می‌شوند؛ چشمی‌ها ثابت‌تر می‌مانند
            val fill = if (isFinder) 0.86f else 0.64f + 0.2f * w
            val alpha = if (isFinder) 0.55f else 0.22f + 0.5f * w
            val color = ColorUtils.blendARGB(base, accent, w * (if (isFinder) 0.6f else 1f))
            paint.color = ColorUtils.setAlphaComponent(color, (alpha * 255).toInt())
            val size = cell * fill
            val l = left + x * cell + (cell - size) / 2f
            val t = top + y * cell + (cell - size) / 2f
            rect.set(l, t, l + size, t + size)
            val r = size * 0.28f
            canvas.drawRoundRect(rect, r, r, paint)
        }
    }

    /**
     * ماژول‌های خطا: با رنگ خطا و کم‌رنگ؛ یک نوار مورب از QR «افتاده» و دو طرفش جابه‌جا شده
     * (مثل کاغذ پاره)، تا هم شبیه QR باشد و هم ناموفق بودن را برساند.
     */
    private fun drawBroken(canvas: Canvas, x: Int, y: Int, left: Float, top: Float, cell: Float) {
        val diag = x + y - (N - 1) // فاصله از قطر فرعی
        if (diag in -1..1) return // شکاف
        val isFinder = finder[y][x]
        val shift = if (diag > 1) cell * 0.45f else 0f
        val size = cell * (if (isFinder) 0.86f else 0.66f)
        val l = left + x * cell + (cell - size) / 2f + shift
        val t = top + y * cell + (cell - size) / 2f + shift * 0.6f
        rect.set(l, t, l + size, t + size)
        paint.color = ColorUtils.setAlphaComponent(error, ((if (isFinder) 0.34f else 0.16f) * 255).toInt())
        val r = size * 0.28f
        canvas.drawRoundRect(rect, r, r, paint)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateRunning()
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        updateRunning()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        updateRunning()
    }

    private fun updateRunning() {
        if (!failed && isAttachedToWindow && isShown && windowVisibility == VISIBLE) start() else stop()
    }

    private fun start() {
        if (animator != null) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1500
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { phase = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun stop() {
        animator?.cancel()
        animator = null
    }

    private companion object {
        const val N = 21

        fun inFinder(x: Int, y: Int): Boolean =
            (x < 7 && y < 7) || (x >= N - 7 && y < 7) || (x < 7 && y >= N - 7)

        /** الگوی ثابت و تصادفی‌نما (همیشه یکسان)، با چشمی‌های استاندارد QR در سه گوشه. */
        fun buildPattern(): Array<BooleanArray> {
            val rnd = java.util.Random(20)
            val g = Array(N) { BooleanArray(N) { rnd.nextFloat() < 0.5f } }
            fun eye(ox: Int, oy: Int) {
                for (dy in -1..7) for (dx in -1..7) {
                    val x = ox + dx
                    val y = oy + dy
                    if (x !in 0 until N || y !in 0 until N) continue
                    val inside = dx in 0..6 && dy in 0..6
                    g[y][x] = inside && (dx == 0 || dx == 6 || dy == 0 || dy == 6 || (dx in 2..4 && dy in 2..4))
                }
            }
            eye(0, 0); eye(N - 7, 0); eye(0, N - 7)
            return g
        }
    }
}
