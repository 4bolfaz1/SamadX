package ir.abdev.samadx.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import ir.abdev.samadx.Graph
import ir.abdev.samadx.R
import ir.abdev.samadx.data.PickupCode
import ir.abdev.samadx.data.Option
import ir.abdev.samadx.data.WeekPlan
import ir.abdev.samadx.databinding.DialogCodeBinding
import ir.abdev.samadx.databinding.FragmentWeekBinding
import ir.abdev.samadx.util.Fa
import ir.abdev.samadx.util.Jalali
import ir.abdev.samadx.util.QrCode
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.time.LocalDate
import kotlin.math.roundToInt

/** برنامه‌ی هفتگی: مرور هفته‌ها، رزرو/لغو، کد تحویل (QR) و رزرو همه. */
class WeekFragment : Fragment() {

    private var _b: FragmentWeekBinding? = null
    private val b get() = _b!!

    private val repo get() = Graph.repo
    private var weekStart: LocalDate = Jalali.weekStart(LocalDate.now())
    private var plan: WeekPlan? = null
    private val busy = HashSet<String>()
    private lateinit var adapter: DayAdapter
    private var loadJob: Job? = null
    private var notice: NoticeBar? = null
    /** جهت آخرین جابه‌جایی هفته (+۱ بعدی، −۱ قبلی) تا اولین نمایش محتوای جدید با انیمیشن بیاید؛ ۰ = بی‌انیمیشن. */
    private var enterDir = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedInstanceState?.getString(KEY_WEEK)?.let { weekStart = LocalDate.parse(it) }
        repo.minWeekStart().let { if (weekStart.isBefore(it)) weekStart = it } // بیش از ۱۰ هفته عقب‌تر نمی‌شود رفت
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_WEEK, weekStart.toString())
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _b = FragmentWeekBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        notice = NoticeBar(b.cacheNotice)
        adapter = DayAdapter(::onReserve, ::onCancel, ::onCode)
        b.list.layoutManager = LinearLayoutManager(requireContext())
        b.list.adapter = adapter

        b.swipe.setOnRefreshListener {
            b.swipe.isRefreshing = false // نشانگر پیشرفت همان نوار بالای صفحه است
            load(overlay = false, force = true)
        }
        b.btnPrev.setOnClickListener {
            if (canGoPrev()) { weekStart = weekStart.minusWeeks(1); enterDir = -1; load() }
        }
        b.btnNext.setOnClickListener {
            if (canGoNext()) { weekStart = weekStart.plusWeeks(1); enterDir = 1; load() }
        }
        // لمس عنوان هفته = پرش به هفته‌ی جاری
        b.weekTitle.setOnClickListener { goToCurrentWeek() }
        b.fabReserveAll.setOnClickListener { confirmReserveAll() }

        load()
    }

    override fun onDestroyView() {
        loadJob?.cancel()
        setLoading(false)
        notice = null
        _b = null
        super.onDestroyView()
    }

    // ------------------------------------------------------------ بارگذاری

    /**
     * overlay = true: هفته عوض شده/اولین بارگذاری؛ اول نسخه‌ی کش‌شده نشان داده می‌شود.
     * تا رسیدن پاسخ سرور نوار پیشرفت کوچک بالای صفحه دیده می‌شود.
     */
    private fun load(overlay: Boolean = true, force: Boolean = false) {
        loadJob?.cancel()
        val start = weekStart
        if (overlay) {
            plan = repo.peekWeek(start)
            if (plan != null) {
                render()
            } else {
                adapter.submit(emptyList(), busy)
                b.empty.isVisible = false
                b.fabReserveAll.isVisible = false
            }
        }
        updateHeader()
        setLoading(true)

        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            val result = safely(onError = { if (plan == null) showMessage(it) else snack(it) }) {
                val self = repo.ensureSelf()
                repo.week(self, start, force)
            }
            setLoading(false)
            if (result != null) {
                plan = result
                render()
                // اگر هنوز موجودی ذخیره نشده، یک بار (فقط همین‌جا) از سرور گرفته می‌شود
                if (repo.cachedCredit() == null) {
                    safely(onError = {}) { repo.credit() }
                    if (_b != null) updateWallet()
                }
            }
        }
    }

    private fun render() {
        val p = plan ?: return
        adapter.submit(p.days, busy)
        val isEmpty = p.days.isEmpty()
        b.empty.isVisible = isEmpty
        if (isEmpty) b.empty.text = p.message ?: getString(R.string.empty_week)
        b.fabReserveAll.isVisible = reservableSingles(p).isNotEmpty()
        updateHeader()
        playEnter()
    }

    /** محتوای هفته‌ی جدید با کمی لغزش و محو‌شدگی از سمت جهت حرکت وارد می‌شود. */
    private fun playEnter() {
        val dir = enterDir
        if (dir == 0) return
        enterDir = 0
        val v = _b?.weekContent ?: return
        val rtl = v.layoutDirection == View.LAYOUT_DIRECTION_RTL
        val shift = 40f * resources.displayMetrics.density * dir * (if (rtl) -1 else 1)
        v.animate().cancel()
        v.alpha = 0f
        v.translationX = shift
        v.animate().alpha(1f).translationX(0f).setDuration(260)
            .setInterpolator(PathInterpolator(0.2f, 0f, 0f, 1f)).start()
    }

    private fun goToCurrentWeek() {
        val current = Jalali.weekStart(LocalDate.now())
        if (weekStart != current) {
            enterDir = if (current.isAfter(weekStart)) 1 else -1
            weekStart = current
            load()
        }
    }

    private fun updateCacheNotice() {
        val p = plan
        val at = p?.cachedAt
        val text = when {
            p == null -> null
            p.partial -> getString(R.string.week_partial)
            p.archived -> getString(
                if (weekStart.isBefore(Jalali.weekStart(LocalDate.now()))) R.string.week_archived else R.string.week_settled
            ) +
                (at?.let { "\n" + cacheNoticeText(it, p.cacheError) } ?: "")
            at != null -> cacheNoticeText(at, p.cacheError)
            else -> null
        }
        notice?.update(text)
        // هفته‌ی آرشیو قابل تازه‌سازی از سرور نیست
        b.swipe.isEnabled = p?.archived != true
    }

    /**
     * به عقب: حداکثر ۱۰ هفته، و از یک هفته‌ی خالیِ گذشته دیگر عقب‌تر نمی‌شود رفت
     * (از هفته‌ی جاری و آینده همیشه می‌شود برگشت).
     */
    private fun canGoPrev(): Boolean {
        if (!weekStart.isAfter(repo.minWeekStart())) return false
        val past = weekStart.isBefore(Jalali.weekStart(LocalDate.now()))
        return !past || plan?.days?.isNotEmpty() == true
    }

    /**
     * به جلو فقط تا «آخرین هفته‌ی غیرخالی + ۱»: از هفته‌ی جاری و گذشته همیشه می‌شود جلو رفت؛
     * از هفته‌ی آینده فقط اگر خودش برنامه داشته باشد (هفته‌ی خالی سد راه است).
     */
    private fun canGoNext(): Boolean =
        !weekStart.isAfter(Jalali.weekStart(LocalDate.now())) || plan?.days?.isNotEmpty() == true

    /** موجودی کیف پول (فقط از مقدار ذخیره‌شده؛ رزرو/لغو آن را آفلاین به‌روز می‌کنند). */
    private fun updateWallet() {
        val c = repo.cachedCredit()
        b.walletBalance.isVisible = c != null
        if (c != null) {
            b.walletBalance.text = getString(
                if (c.credit < 0) R.string.week_wallet_debt else R.string.week_wallet,
                Fa.money(c.credit)
            )
        }
    }

    private fun updateNav() {
        b.btnPrev.isEnabled = canGoPrev()
        b.btnPrev.alpha = if (b.btnPrev.isEnabled) 1f else 0.38f
        b.btnNext.isEnabled = canGoNext()
        b.btnNext.alpha = if (b.btnNext.isEnabled) 1f else 0.38f
    }

    private fun updateHeader() {
        b.weekTitle.text = Jalali.weekTitle(weekStart)
        updateNav()
        updateWallet()
        updateCacheNotice()
        val p = plan
        b.weekSummary.text = when {
            p == null -> ""
            p.activeReserves.isEmpty() -> getString(R.string.week_no_reserve)
            else -> getString(R.string.week_summary, Fa.count(p.activeReserves.size), Fa.money(p.totalPrice))
        }
    }

    private fun showMessage(text: String) {
        b.empty.text = text + "\n\n" + getString(R.string.pull_to_retry)
        b.empty.isVisible = true
        playEnter()
    }

    // ------------------------------------------------------------ رزرو و لغو

    private fun onReserve(o: Option) = act(o) {
        repo.reserve(o)
        getString(R.string.reserved_ok)
    }

    private fun onCancel(o: Option) = act(o) {
        val reserve = o.reserve ?: return@act getString(R.string.cancelled_ok)
        repo.cancel(reserve.id, reserve.price * reserve.count)
        getString(R.string.cancelled_ok)
    }

    /** اجرای یک عملیات روی یک گزینه؛ دکمه‌ی همان کارت تا پایان کار غیرفعال می‌ماند. */
    private fun act(o: Option, block: suspend () -> String) {
        if (!busy.add(o.key)) return
        adapter.setBusy(busy)
        viewLifecycleOwner.lifecycleScope.launch {
            val message = safely { block() }
            busy.remove(o.key)
            adapter.setBusy(busy)
            if (message != null) {
                snack(message)
                load(overlay = false, force = true) // رزروها و جمع هزینه را تازه می‌کند
            }
        }
    }

    // ------------------------------------------------------------ رزرو همه

    /** گزینه‌های رزروپذیری که انتخاب اضافه نمی‌خواهند (برنامه‌ی تک‌گزینه‌ای و هنوز رزرو نشده). */
    private fun reservableSingles(p: WeekPlan): List<Option> =
        p.days.flatMap { it.options }
            .groupBy { it.programId }
            .values
            .filter { it.size == 1 }
            .map { it.first() }
            .filter { it.reserve == null && it.canReserve }

    private fun confirmReserveAll() {
        val p = plan ?: return
        val targets = reservableSingles(p)
        if (targets.isEmpty()) {
            snack(getString(R.string.reserve_all_none))
            return
        }
        val total = targets.sumOf { it.price * it.minCount }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.reserve_all)
            .setMessage(getString(R.string.reserve_all_confirm, Fa.count(targets.size), Fa.money(total)))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.reserve) { _, _ -> reserveAll(targets) }
            .showRtl()
    }

    private fun reserveAll(targets: List<Option>) {
        val keys = targets.map { it.key }
        busy.addAll(keys)
        adapter.setBusy(busy)
        viewLifecycleOwner.lifecycleScope.launch {
            var done = 0
            var failure: String? = null
            for (o in targets) {
                val ok = safely(onError = { failure = it }) {
                    repo.reserve(o)
                    true
                }
                if (ok == null) break // اولین خطا (مثلاً کمبود اعتبار یا پایان مهلت) کار را متوقف می‌کند
                done++
            }
            busy.removeAll(keys.toSet())
            adapter.setBusy(busy)
            val message = failure
            snack(
                if (message == null) getString(R.string.reserve_all_ok, Fa.count(done))
                else getString(R.string.reserve_all_partial, Fa.count(done), message)
            )
            load(overlay = false, force = true)
        }
    }

    // ------------------------------------------------------------ کد تحویل غذا

    private fun onCode(o: Option) {
        val reserve = o.reserve ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val code = safely { repo.pickupCode(o) } ?: return@launch
            showCodeDialog(code)
        }
    }

    private fun showCodeDialog(pc: PickupCode) {
        if (pc.code.isBlank() || !pc.valid) {
            snack(getString(R.string.pickup_invalid))
            return
        }
        val d = DialogCodeBinding.inflate(layoutInflater)
        d.description.text = listOf(pc.meal, pc.food).filter { it.isNotBlank() }.joinToString(" · ")
        d.code.text = pc.code
        val sizePx = (QR_SIZE_DP * resources.displayMetrics.density).roundToInt()
        d.qr.setImageBitmap(QrCode.bitmap(pc.code, sizePx))

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.pickup_title)
            .setView(d.root)
            .setPositiveButton(R.string.close, null)
            .setNeutralButton(R.string.copy) { _, _ ->
                val cm = requireContext().getSystemService(ClipboardManager::class.java)
                cm.setPrimaryClip(ClipData.newPlainText("pickup-code", pc.code))
                snack(getString(R.string.copied))
            }
            .showRtl()
        // با یک ضربه روی QR، ۵ ثانیه نور کامل؛ با بسته شدن دیالوگ نور فوراً عادی می‌شود.
        val boost = BrightnessBoost { dialog.window }
        d.qr.setOnClickListener { boost.boost() }
        dialog.setOnDismissListener { boost.restore() }
    }

    private companion object {
        const val KEY_WEEK = "week_start"
        const val QR_SIZE_DP = 240
    }
}
