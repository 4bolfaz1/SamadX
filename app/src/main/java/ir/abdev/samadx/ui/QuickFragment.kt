package ir.abdev.samadx.ui

import android.animation.ObjectAnimator
import android.content.ClipData
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import ir.abdev.samadx.Graph
import ir.abdev.samadx.R
import ir.abdev.samadx.data.Option
import ir.abdev.samadx.databinding.FragmentQuickBinding
import ir.abdev.samadx.util.Fa
import ir.abdev.samadx.util.Jalali
import ir.abdev.samadx.util.ShareImage
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

/** تحویل سریع: QR و کد تحویل نزدیک‌ترین وعده‌ی رزروشده، بدون هیچ مرحله‌ی اضافه. */
class QuickFragment : Fragment() {

    private var _b: FragmentQuickBinding? = null
    private val b get() = _b!!

    private var meals: List<Option> = emptyList()
    private var index = 0
    private var cachedAt: Long? = null
    private var cacheError: String? = null
    private var currentCode: String? = null
    private var codeKey: String? = null
    private var codeFailed = false
    private var notice: NoticeBar? = null
    private var loadJob: Job? = null
    private val boost = BrightnessBoost { activity?.window }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _b = FragmentQuickBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        notice = NoticeBar(b.cacheNotice)
        b.swipe.setOnRefreshListener {
            b.swipe.isRefreshing = false // نشانگر پیشرفت همان نوار بالای صفحه است
            load(overlay = false, force = true)
        }
        b.btnNext.setOnClickListener {
            if (meals.isNotEmpty()) {
                index = (index + 1) % meals.size
                show()
            }
        }
        b.btnShare.setOnClickListener { shareCurrent() }
        b.qrBox.setOnClickListener { if (codeFailed) retryCode() } // لمس QR خطادار = تلاش دوباره
        b.qr.setOnClickListener { boost.boost() } // ۵ ثانیه نور کامل
        load(overlay = true, force = false)
    }

    override fun onPause() {
        boost.restore()
        super.onPause()
    }

    override fun onDestroyView() {
        loadJob?.cancel()
        setLoading(false)
        notice = null
        _b = null
        super.onDestroyView()
    }

    /** اول نسخه‌ی کش‌شده، تا رسیدن پاسخ سرور نوار پیشرفت کوچک بالای صفحه. */
    private fun load(overlay: Boolean, force: Boolean) {
        loadJob?.cancel()
        if (overlay) {
            Graph.repo.peekUpcoming()?.let { cached ->
                meals = cached.meals
                cachedAt = cached.cachedAt
                cacheError = null
                index = 0
                if (meals.isEmpty()) showMessage(getString(R.string.quick_none)) else show()
            }
        }
        setLoading(true)
        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            val up = safely(onError = {
                if (meals.isEmpty()) showMessage(it + "\n\n" + getString(R.string.pull_to_retry)) else snack(it)
            }) { Graph.repo.upcomingMeals(force) }
            setLoading(false)
            if (up != null && _b != null) {
                val keepKey = meals.getOrNull(index)?.key
                meals = up.meals
                cachedAt = up.cachedAt
                cacheError = up.cacheError
                index = meals.indexOfFirst { it.key == keepKey }.coerceAtLeast(0)
                if (meals.isEmpty()) showMessage(getString(R.string.quick_none)) else show()
            }
        }
    }

    private fun showMessage(text: String) {
        val v = _b ?: return
        v.content.isVisible = false
        v.message.text = text
        v.message.isVisible = true
    }

    private fun show() {
        val o = meals.getOrNull(index) ?: return
        b.message.isVisible = false
        b.content.isVisible = true

        notice?.update(cachedAt?.let { cacheNoticeText(it, cacheError) })

        // خط اول: تاریخ نسبی + وعده؛ خط دوم: تاریخ کامل (نسبی به‌تنهایی کافی نیست)
        val date = LocalDate.parse(o.date)
        b.day.text = Fa.relativeDay(ChronoUnit.DAYS.between(LocalDate.now(), date)) + " · " + o.mealName
        b.dayDate.text = Jalali.weekdayName(date) + " " + Jalali.format(date)
        b.food.text = o.foodName
        b.sides.isVisible = o.sides.isNotBlank()
        b.sides.text = o.sides
        b.selfName.text = Graph.store.selfName.orEmpty()
        b.selfName.isVisible = !Graph.store.selfName.isNullOrBlank()

        b.btnNext.isVisible = meals.size > 1
        b.remaining.isVisible = meals.size > 1
        b.remaining.text = getString(R.string.quick_remaining, Fa.count(meals.size - 1))

        // کد تحویل (از کش ماندگار؛ فقط بار اول از سرور)
        val reserve = o.reserve
        if (reserve == null) {
            currentCode = null
            codeKey = null
            codeFailed = false
            b.qrBox.isVisible = false
            b.code.text = ""
            b.btnShare.isEnabled = false
            return
        }
        if (codeKey == codeKeyOf(o) && currentCode != null) return // همین کد از قبل نشان داده شده؛ چشمک نزند
        loadCode(o)
    }

    private fun codeKeyOf(o: Option): String = "${o.key}:${o.reserve?.id}"

    private fun retryCode() {
        val o = meals.getOrNull(index) ?: return
        if (o.reserve != null) loadCode(o)
    }

    /** حالت بارگذاری: جای QR رزرو است و انیمیشن موجی دیده می‌شود. */
    private fun showCodeLoading() {
        currentCode = null
        codeKey = null
        codeFailed = false
        b.code.text = ""
        b.btnShare.isEnabled = false
        b.qr.animate().cancel()
        b.qrLoading.animate().cancel()
        b.qrError.animate().cancel()
        b.qrError.isVisible = false
        b.qr.isVisible = false
        b.qrLoading.failed = false
        b.qrLoading.alpha = 1f
        b.qrLoading.isVisible = true
        b.qrBox.isVisible = true
    }

    /** حالت خطا: QR ترک‌خورده با نشانه‌ی «دریافت نشد»؛ کمی می‌لرزد تا توجه جلب شود. */
    private fun showCodeFailed() {
        val v = _b ?: return
        codeFailed = true
        v.qr.isVisible = false
        v.qrLoading.animate().cancel()
        v.qrLoading.alpha = 1f
        v.qrLoading.isVisible = true
        v.qrLoading.failed = true
        v.qrBox.isVisible = true
        v.qrError.alpha = 0f
        v.qrError.isVisible = true
        v.qrError.animate().alpha(1f).setDuration(260).start()
        val shake = 10f * resources.displayMetrics.density
        ObjectAnimator.ofFloat(v.qrBox, "translationX", 0f, -shake, shake, -shake * 0.6f, shake * 0.6f, 0f)
            .setDuration(380).start()
    }

    private fun loadCode(o: Option) {
        showCodeLoading()
        val key = codeKeyOf(o)
        val shownIndex = index
        viewLifecycleOwner.lifecycleScope.launch {
            var failed = false
            val pc = safely(onError = { failed = true; snack(it) }) { Graph.repo.pickupCode(o) }
            val v = _b ?: return@launch
            if (shownIndex != index) return@launch // کاربر به وعده‌ی دیگری رفته است
            if (pc == null) {
                if (failed) showCodeFailed()
                return@launch
            }
            if (!pc.valid || pc.code.isBlank()) {
                snack(getString(R.string.pickup_invalid))
                v.qrBox.isVisible = false
                return@launch
            }
            currentCode = pc.code
            codeKey = key
            v.code.text = pc.code
            v.btnShare.isEnabled = true
            val sizePx = (QR_SIZE_DP * resources.displayMetrics.density).roundToInt()
            v.qr.setImageBitmap(ir.abdev.samadx.util.QrCode.bitmap(pc.code, sizePx))
            // QR نرم ظاهر می‌شود و انیمیشن بارگذاری محو می‌شود
            v.qr.alpha = 0f
            v.qr.isVisible = true
            v.qr.animate().alpha(1f).setDuration(300).start()
            v.qrLoading.animate().alpha(0f).setDuration(300)
                .withEndAction { _b?.qrLoading?.isVisible = false }.start()
        }
    }

    /** تصویری شامل QR، توضیح و معرفی برنامه می‌سازد و برای اشتراک‌گذاری باز می‌کند. */
    private fun shareCurrent() {
        val code = currentCode ?: return
        val o = meals.getOrNull(index) ?: return
        val ctx = requireContext().applicationContext
        val accent = MaterialColors.getColor(b.root, androidx.appcompat.R.attr.colorPrimary)
        // در تصویر اشتراک‌گذاری تاریخ نسبی نمی‌آید (فردا/۳ روز دیگر بعد از ارسال معنی ندارد): فقط تاریخ کامل
        val date = LocalDate.parse(o.date)
        val title = Jalali.weekdayName(date) + " " + Jalali.format(date) + " · " + o.mealName
        b.btnShare.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            val uri = try {
                withContext(Dispatchers.Default) { ShareImage.create(ctx, accent, title, o.foodName, o.sides, code) }
            } catch (e: Exception) {
                null
            }
            _b?.btnShare?.isEnabled = currentCode != null
            if (uri == null) {
                if (_b != null) snack(getString(R.string.share_failed))
                return@launch
            }
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TEXT, getString(R.string.share_text, code))
                clipData = ClipData.newRawUri("", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, getString(R.string.share_chooser)))
        }
    }

    private companion object {
        const val QR_SIZE_DP = 260 // با اندازه‌ی qr_box در fragment_quick.xml یکی باشد
    }
}
