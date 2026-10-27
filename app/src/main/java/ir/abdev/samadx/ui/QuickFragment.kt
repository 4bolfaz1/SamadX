package ir.abdev.samadx.ui

import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.Intent
import android.os.Bundle
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import ir.abdev.samadx.Graph
import ir.abdev.samadx.R
import ir.abdev.samadx.data.AccountInfo
import ir.abdev.samadx.data.ApiException
import ir.abdev.samadx.data.AuthRequiredException
import ir.abdev.samadx.data.Meals
import ir.abdev.samadx.data.Option
import ir.abdev.samadx.data.Repository
import ir.abdev.samadx.data.Store
import ir.abdev.samadx.data.Upcoming
import com.google.android.material.chip.Chip
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
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

/**
 * تحویل سریع: QR و کد تحویل وعده‌های رزروشده‌ی همه‌ی حساب‌ها.
 *
 * - «وعده» (Slot) = یک روز + یک نوع وعده (ناهار، شام، سحری، افطاری، …)؛ با قبلی/بعدی بینشان حرکت می‌کنید.
 * - اگر چند حساب برای همان وعده رزرو دارند، بالای QR یک نوار حساب می‌آید: با یک لمس (یا کشیدن QR به
 *   چپ/راست) QR حساب بعدی بدون هیچ بارگذاری یا تعویض حساب نشان داده می‌شود؛ دکمه‌ی «تحویل شد · حساب بعدی»
 *   حساب فعلی را علامت می‌زند و مستقیم به حساب بعدی می‌رود.
 */
class QuickFragment : Fragment() {

    private class Item(val acc: AccountInfo, val repo: Repository, val store: Store, val o: Option) {
        val doneKey: String get() = "${acc.id}:${o.reserve?.id}"
        val codeKey: String get() = "${acc.id}:${o.key}:${o.reserve?.id}"
    }

    private class Slot(val date: String, val mealTypeId: Int, val items: List<Item>) {
        val key: String get() = "$date:$mealTypeId"
        val mealName: String get() = items.first().o.mealName
    }

    private class Part(val acc: AccountInfo, val up: Upcoming)

    private var _b: FragmentQuickBinding? = null
    private val b get() = _b!!

    private var slots: List<Slot> = emptyList()
    private var slotIndex = 0
    private var accIndex = 0
    private var chipsKey: String? = null
    private var cachedAt: Long? = null
    private var cacheError: String? = null
    private var currentCode: String? = null
    private var codeKey: String? = null
    private var codeFailed = false
    private var notice: NoticeBar? = null
    private var loadJob: Job? = null
    private val boost = BrightnessBoost { activity?.window }

    private fun slot(): Slot? = slots.getOrNull(slotIndex)
    private fun item(): Item? = slot()?.let { it.items.getOrNull(accIndex) ?: it.items.firstOrNull() }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _b = FragmentQuickBinding.inflate(inflater, container, false)
        return b.root
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        notice = NoticeBar(b.cacheNotice)
        b.swipe.setOnRefreshListener {
            b.swipe.isRefreshing = false // نشانگر پیشرفت همان نوار بالای صفحه است
            load(overlay = false, force = true)
        }
        b.btnNext.setOnClickListener { moveSlot(+1) }
        b.btnPrev.setOnClickListener { moveSlot(-1) }
        b.btnShare.setOnClickListener { shareCurrent() }
        b.btnDone.setOnClickListener { markDone() }

        // لمس QR: نور کامل (۵ ثانیه) یا، اگر خطا دارد، تلاش دوباره؛ کشیدن افقی: حساب بعدی/قبلی
        val detector = GestureDetector(requireContext(), object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (codeFailed) retryCode() else boost.boost()
                return true
            }
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (abs(vx) > abs(vy) && abs(vx) > 500f) {
                    moveAccount(if (vx < 0) +1 else -1) // راست‌به‌چپ = حساب بعدی (مثل ترتیب نوار)
                    return true
                }
                return false
            }
        })
        b.qrBox.setOnTouchListener { _, ev -> detector.onTouchEvent(ev) }
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

    // ------------------------------------------------------------ بارگذاری (همه‌ی حساب‌ها)

    /** اول نسخه‌ی کش‌شده‌ی همه‌ی حساب‌ها، تا رسیدن پاسخ سرور نوار پیشرفت کوچک بالای صفحه. */
    private fun load(overlay: Boolean, force: Boolean) {
        loadJob?.cancel()
        val accs = Graph.accounts.all()
        if (overlay) {
            val parts = accs.mapNotNull { a -> Graph.repoOf(a).peekUpcoming()?.let { Part(a, it) } }
            if (parts.isNotEmpty()) apply(parts)
        }
        setLoading(true)
        val activeId = Graph.accounts.active()?.id
        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            val parts = ArrayList<Part>()
            var activeError: Exception? = null
            val failed = ArrayList<String>()
            coroutineScope {
                accs.map { a ->
                    async<Pair<Part?, Pair<AccountInfo, Exception>?>> {
                        try {
                            Part(a, Graph.repoOf(a).upcomingMeals(force)) to null
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            null to (a to e)
                        }
                    }
                }.awaitAll()
            }.forEach { (part, err) ->
                if (part != null) parts += part
                if (err != null) {
                    if (err.first.id == activeId) activeError = err.second
                    else failed += Graph.labelOf(err.first)
                }
            }
            setLoading(false)
            if (_b == null) return@launch
            val ae = activeError
            if (ae is AuthRequiredException) { forceLogin(ae.message); return@launch }
            if (parts.isNotEmpty()) {
                apply(parts)
                val msg = (ae as? ApiException)?.userMessage
                if (msg != null) snack(msg)
                else if (failed.isNotEmpty()) snack(getString(R.string.quick_account_failed, failed.joinToString("، ")))
            } else {
                val text = (ae as? ApiException)?.userMessage ?: ae?.let { getString(R.string.error_unexpected, it.message ?: "") }
                if (text != null) {
                    if (slots.isEmpty()) showMessage(text + "\n\n" + getString(R.string.pull_to_retry)) else snack(text)
                } else {
                    slots = emptyList()
                    showMessage(getString(R.string.quick_none))
                }
            }
        }
    }

    /** نتیجه‌ی حساب‌ها را به «وعده»‌ها تبدیل می‌کند و جای فعلی کاربر را (تا حد امکان) نگه می‌دارد. */
    private fun apply(parts: List<Part>) {
        val keepSlot = slot()?.key
        val keepAcc = item()?.acc?.id
        val grouped = LinkedHashMap<String, MutableList<Item>>()
        val meta = HashMap<String, Pair<String, Int>>()
        for (p in parts) {
            val repo = Graph.repoOf(p.acc)
            val store = Graph.storeOf(p.acc)
            for (o in p.up.meals) {
                val k = "${o.date}:${o.mealTypeId}"
                grouped.getOrPut(k) { ArrayList() } += Item(p.acc, repo, store, o)
                meta[k] = o.date to o.mealTypeId
            }
        }
        slots = grouped.entries
            .map { (k, items) -> Slot(meta.getValue(k).first, meta.getValue(k).second, items) }
            .sortedWith(compareBy({ it.date }, { Meals.rank(it.mealTypeId) }))
        cachedAt = parts.mapNotNull { it.up.cachedAt }.minOrNull()
        cacheError = parts.firstNotNullOfOrNull { it.up.cacheError }
        if (slots.isEmpty()) { showMessage(getString(R.string.quick_none)); return }
        slotIndex = slots.indexOfFirst { it.key == keepSlot }.coerceAtLeast(0)
        val s = slots[slotIndex]
        accIndex = s.items.indexOfFirst { it.acc.id == keepAcc }.takeIf { it >= 0 } ?: firstUndone(s)
        show()
    }

    private fun firstUndone(s: Slot): Int = s.items.indexOfFirst { it.doneKey !in done }.takeIf { it >= 0 } ?: 0

    // ------------------------------------------------------------ حرکت

    private fun moveSlot(dir: Int) {
        val n = slotIndex + dir
        if (n !in slots.indices) return
        slotIndex = n
        accIndex = firstUndone(slots[n])
        show()
    }

    private fun moveAccount(dir: Int) {
        val s = slot() ?: return
        if (s.items.size < 2) return
        accIndex = (accIndex + dir + s.items.size) % s.items.size
        show()
    }

    /** علامت «تحویل شد» برای حساب فعلی و رفتن به حساب بعدیِ تحویل‌نشده (و بعد از آخری، وعده‌ی بعد). */
    private fun markDone() {
        val s = slot() ?: return
        val it = item() ?: return
        if (it.doneKey in done) { // برداشتن علامت
            done.remove(it.doneKey)
            show()
            return
        }
        done += it.doneKey
        val n = s.items.size
        for (step in 1 until n) {
            val idx = (accIndex + step) % n
            if (s.items[idx].doneKey !in done) { accIndex = idx; show(); return }
        }
        if (n > 1) snack(getString(R.string.quick_all_done))
        if (slotIndex < slots.size - 1) moveSlot(+1) else show()
    }

    private fun showMessage(text: String) {
        val v = _b ?: return
        v.content.isVisible = false
        v.message.text = text
        v.message.isVisible = true
    }

    private fun show() {
        val s = slot() ?: return
        val o = item() ?: return
        b.message.isVisible = false
        b.content.isVisible = true

        notice?.update(cachedAt?.let { cacheNoticeText(it, cacheError) })

        // خط اول: تاریخ نسبی + وعده؛ خط دوم: تاریخ کامل (نسبی به‌تنهایی کافی نیست)
        val date = LocalDate.parse(o.o.date)
        b.day.text = Fa.relativeDay(ChronoUnit.DAYS.between(LocalDate.now(), date)) + " · " + s.mealName
        b.dayDate.text = Jalali.weekdayName(date) + " " + Jalali.format(date)
        b.food.text = o.o.foodName
        b.sides.isVisible = o.o.sides.isNotBlank()
        b.sides.text = o.o.sides
        b.selfName.text = o.store.selfName.orEmpty()
        b.selfName.isVisible = !o.store.selfName.isNullOrBlank()

        bindAccounts(s)

        // قبلی / بعدی بین وعده‌ها
        val many = slots.size > 1
        b.btnPrev.isVisible = many
        b.btnNext.isVisible = many
        b.btnPrev.isEnabled = slotIndex > 0
        b.btnNext.isEnabled = slotIndex < slots.size - 1
        b.remaining.isVisible = many
        b.remaining.text = getString(R.string.quick_position, Fa.count(slotIndex + 1), Fa.count(slots.size))

        // کد تحویل (از کش ماندگار؛ فقط بار اول از سرور)
        if (o.o.reserve == null) {
            currentCode = null
            codeKey = null
            codeFailed = false
            b.qrBox.isVisible = false
            b.code.text = ""
            b.btnShare.isEnabled = false
            return
        }
        if (codeKey == o.codeKey && currentCode != null) return // همین کد از قبل نشان داده شده؛ چشمک نزند
        loadCode(o)
    }

    /** نوار حساب‌ها و دکمه‌ی «تحویل شد» (فقط وقتی چند حساب همین وعده را دارند). */
    private fun bindAccounts(s: Slot) {
        val multi = s.items.size > 1
        b.accountsScroll.isVisible = multi
        b.multiHint.isVisible = multi
        b.btnDone.isVisible = multi
        if (!multi) { chipsKey = null; return }

        val names = s.items.map { Graph.labelOf(it.acc) }
        // اگر نام کوچک دو حساب یکی بود، نام کامل نشان داده می‌شود
        val short = names.map { it.substringBefore(' ') }
        val labels = names.mapIndexed { i, full -> if (short.count { it == short[i] } > 1) full else short[i] }

        val key = s.key + "|" + s.items.joinToString(",") { it.acc.id }
        val group = b.accountChips
        if (chipsKey != key) {
            chipsKey = key
            group.removeAllViews()
            s.items.forEachIndexed { i, _ ->
                val chip = layoutInflater.inflate(R.layout.item_account_chip, group, false) as Chip
                chip.id = View.generateViewId()
                chip.setOnClickListener { accIndex = i; show() }
                group.addView(chip)
            }
        }
        s.items.forEachIndexed { i, it ->
            val chip = group.getChildAt(i) as Chip
            val isDone = it.doneKey in done
            chip.text = (if (isDone) "✓ " else "") + labels[i]
            chip.alpha = if (isDone) 0.6f else 1f
        }
        (group.getChildAt(accIndex) as? Chip)?.let { group.check(it.id) }
        b.accountsScroll.post { // چیپ انتخاب‌شده در دید بماند
            val c = _b?.accountChips?.getChildAt(accIndex) ?: return@post
            _b?.accountsScroll?.smoothScrollTo(c.left - 24, 0)
        }

        val cur = s.items[accIndex]
        val othersLeft = s.items.indices.any { it != accIndex && s.items[it].doneKey !in done }
        b.btnDone.setText(
            when {
                cur.doneKey in done -> R.string.quick_undo
                othersLeft -> R.string.quick_done_next
                else -> R.string.quick_done
            }
        )
    }

    private fun retryCode() {
        val it = item() ?: return
        if (it.o.reserve != null) loadCode(it)
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

    private fun loadCode(target: Item) {
        showCodeLoading()
        val key = target.codeKey
        viewLifecycleOwner.lifecycleScope.launch {
            var failed = false
            val pc = safely(onError = { failed = true; snack(it) }) { target.repo.pickupCode(target.o) }
            val v = _b ?: return@launch
            if (item()?.codeKey != key) return@launch // کاربر به وعده/حساب دیگری رفته است
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
        val o = item()?.o ?: return
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
        /** علامت‌های «تحویل شد» این نشست (فقط روی گوشی و فقط برای راحتی کار؛ وضعیت واقعی از سرور می‌آید). */
        val done = HashSet<String>()

        const val QR_SIZE_DP = 260 // با اندازه‌ی qr_box در fragment_quick.xml یکی باشد
    }
}
