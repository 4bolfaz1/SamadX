package ir.abdev.samadx.data

import org.json.JSONObject
import ir.abdev.samadx.util.Jalali
import java.time.LocalDate

/** تمام عملیات سرور در این کلاس جمع شده است. */
data class Upcoming(val meals: List<Option>, val cachedAt: Long?, val cacheError: String?)

class Repository(private val api: ApiClient, private val store: Store) {

    suspend fun login(username: String, password: String) {
        api.login(username, password)
    }

    // ---- کش ----
    // هفته‌ها، اعتبار، گزارش عملکرد و کد تحویل روی گوشی ذخیره می‌شوند (Store)؛ سلف‌ها تا خروج از حساب.
    // رابط کاربری همیشه اول نسخه‌ی کش را نشان می‌دهد (peek*/cached*) و تا رسیدن پاسخ سرور نوار پیشرفت کوچک می‌بیند.

    /** سلف‌ها: یک بار گرفته و تا خروج از حساب نگه داشته می‌شوند. */
    private var selfsMem: List<Self>? = null

    suspend fun selfs(): List<Self> {
        selfsMem?.let { return it }
        store.selfsJson?.let { raw ->
            runCatching { Parsers.selfs(org.json.JSONArray(raw)) }.getOrNull()?.let { selfsMem = it; return it }
        }
        val a = api.get("/rest/reservations/selfs").arr()
        store.selfsJson = a.toString()
        return Parsers.selfs(a).also { selfsMem = it }
    }

    fun cachedSelfs(): List<Self>? = selfsMem
        ?: store.selfsJson?.let { raw -> runCatching { Parsers.selfs(org.json.JSONArray(raw)) }.getOrNull() }?.also { selfsMem = it }

    /** سلف انتخاب‌شده؛ اگر هنوز انتخاب نشده اولین سلف مجاز برگزیده می‌شود. */
    suspend fun ensureSelf(): Self {
        val id = store.selfId
        if (id > 0) return Self(id, store.selfName ?: "")
        val first = selfs().firstOrNull() ?: throw ApiException("هیچ سلفی برای شما تعریف نشده است.")
        selectSelf(first)
        return first
    }

    fun selectSelf(self: Self) {
        store.selfId = self.id
        store.selfName = self.name
    }

    /**
     * برنامه‌ی یک هفته (شروع هفته = شنبه) همراه با رزروهای کاربر.
     *
     * سیاست کش (بر اساس زمان دریافت از سرور):
     * - هفته‌ی گذشته: آرشیو؛ اگر کش داریم همیشه همان (بدون امکان تازه‌سازی). ۱۰ هفته‌ی اخیر نگه داشته می‌شود.
     *   (اگر کش از زمانی است که هفته هنوز تمام نشده بود، یک بار برای نسخه‌ی نهایی از سرور گرفته می‌شود.)
     * - هفته‌ی جاری: تا ۳۰ دقیقه کش ترجیح داده می‌شود؛ اگر همه‌ی وعده‌هایش تحویل‌شده یا مهلت‌گذشته باشد
     *   زودهنگام آرشیو می‌شود (دیگر هرگز از سرور گرفته نمی‌شود).
     * - هفته‌ی آینده: تا ۲۴ ساعت کش ترجیح داده می‌شود.
     * - force (کشیدن برای تازه‌سازی، پس از رزرو/لغو): سرور (به‌جز آرشیو).
     * - اگر سرور جواب ندهد و کش داشته باشیم، کش با پیام خطا و زمان آخرین دریافت برمی‌گردد.
     * - هفته‌ی بدون برنامه هم کش می‌شود (گذشته: آرشیو؛ آینده: TTL مثل بقیه، و برای هفته‌ی بعدِ جاری نیم ساعت).
     */
    suspend fun week(self: Self, weekStart: LocalDate, force: Boolean = false): WeekPlan {
        val key = "${self.id}:$weekStart"
        val currentStart = Jalali.weekStart(LocalDate.now())
        val isPast = weekStart.isBefore(currentStart)
        val isFuture = weekStart.isAfter(currentStart)
        val now = System.currentTimeMillis()
        val st = store.getWeekJson(key)
        val cached = st?.let { parseCached(weekStart, it) }

        if (st != null && cached != null) {
            // هفته‌ی جاریِ تمام‌شده (همه تحویل‌شده یا مهلت‌گذشته) مثل گذشته زودهنگام آرشیو می‌شود
            val settled = !isFuture && !isPast && cached.isSettled
            val fresh = when {
                isPast -> st.fetchedAt >= weekEndMs(weekStart) || cached.isSettled // نسخه‌ی نهایی آرشیو شده است
                settled -> true
                force -> false
                isFuture -> now - st.fetchedAt <= futureTtl(weekStart, currentStart, cached.days.isEmpty())
                else -> now - st.fetchedAt <= CURRENT_TTL_MS
            }
            if (fresh) return cached.copy(archived = isPast || settled)
        }
        try {
            val plan = fetchWeek(self, weekStart)
            return plan.copy(archived = isPast || (!isFuture && plan.isSettled))
        } catch (e: ApiException) {
            if (cached != null) return cached.copy(cacheError = e.userMessage, archived = isPast || (!isFuture && cached.isSettled))
            // نه کش داریم و نه برنامه‌ی کامل: اگر اینترنت هست، حداقل رزروهای خود کاربر را از اندپوینت سبک‌تر نشان بده
            if (e !is OfflineException) reservesOnly(self, weekStart)?.let { return it }
            throw e
        }
    }

    /**
     * پشتیبان: GET /rest/reserves (فقط رزروهای کاربر + remainCredit). نتیجه‌ی «ناقص» است و ذخیره/آرشیو نمی‌شود.
     * اگر این هم شکست بخورد null برمی‌گردد تا خطای اصلی نمایش داده شود.
     */
    private suspend fun reservesOnly(self: Self, weekStart: LocalDate): WeekPlan? = try {
        val p = api.get("/rest/reserves", mapOf("weekStartDate" to "$weekStart 00:00:00", "selfType" to "NORMAL")).obj()
        val (plan, remain) = Parsers.reservesWeek(weekStart, p, self.id)
        remain?.let { syncCredit(it) }
        plan
    } catch (e: ApiException) {
        null
    }

    /** موجودی دقیقِ سرور (ریال) را روی اعتبار ذخیره‌شده می‌نویسد (فقط اگر قبلاً اعتبار ذخیره شده باشد). */
    private fun syncCredit(rial: Long) {
        val st = store.getCreditJson() ?: return
        runCatching {
            val p = JSONObject(st.json)
            p.put("credit", rial)
            store.putCreditJson(p.toString(), st.fetchedAt)
        }
    }

    /**
     * مدت اعتبار کش هفته‌ی آینده: ۲۴ ساعت؛ اما اگر هفته‌ی بعدِ هفته‌ی جاری هنوز خالی است
     * (برنامه‌اش ممکن است هر لحظه منتشر شود) فقط نیم ساعت.
     */
    private fun futureTtl(weekStart: LocalDate, currentStart: LocalDate, empty: Boolean): Long =
        if (empty && weekStart == currentStart.plusWeeks(1)) CURRENT_TTL_MS else FUTURE_TTL_MS

    private fun weekEndMs(weekStart: LocalDate): Long =
        weekStart.plusWeeks(1).atStartOfDay(TEHRAN).toInstant().toEpochMilli()

    /** قدیمی‌ترین هفته‌ای که می‌شود به آن رفت (۱۰ هفته‌ی گذشته). */
    fun minWeekStart(): LocalDate = Jalali.weekStart(LocalDate.now()).minusWeeks(ARCHIVE_WEEKS)

    private var archiveCheckedOn: LocalDate? = null

    /**
     * هفته‌های گذشته‌ای که پیش از پایانشان کش شده‌اند (یا هفته‌ی دقیقاً قبل) را یک بار از سرور می‌گیرد
     * تا نسخه‌ی نهایی (با وضعیت «تحویل شد» و …) آرشیو شود. روزی یک بار؛ با اولین خطا متوقف می‌شود
     * و بار بعد دوباره تلاش می‌کند.
     */
    suspend fun archivePastWeeks() {
        val today = LocalDate.now()
        if (archiveCheckedOn == today) return
        val self = ensureSelf()
        val current = Jalali.weekStart(today)
        for (i in 1..ARCHIVE_WEEKS) {
            val ws = current.minusWeeks(i)
            val st = store.getWeekJson("${self.id}:$ws")
            if (st != null && (st.fetchedAt >= weekEndMs(ws) || parseCached(ws, st)?.isSettled == true)) continue // قبلاً نهایی شده
            if (st == null && i > 1) continue // هفته‌ی دیده‌نشده؛ با باز شدنش گرفته می‌شود
            fetchWeek(self, ws)
        }
        archiveCheckedOn = today
    }

    private fun parseCached(weekStart: LocalDate, st: Store.Stored): WeekPlan? =
        runCatching {
            val o = JSONObject(st.json)
            if (o.optBoolean(EMPTY_MARK)) {
                WeekPlan(weekStart, emptyList(), emptyList(), o.optString("message").ifEmpty { null }, cachedAt = st.fetchedAt)
            } else {
                Parsers.week(weekStart, o).copy(cachedAt = st.fetchedAt)
            }
        }.getOrNull()

    private suspend fun fetchWeek(self: Self, weekStart: LocalDate): WeekPlan {
        // آرشیو: ۱۰ هفته‌ی اخیر (تا ۱۱ هفته بعد از شروع آن هفته نگه داشته می‌شود)
        val expires = weekStart.plusWeeks(ARCHIVE_WEEKS + 1).atStartOfDay(TEHRAN).toInstant().toEpochMilli()
        val key = "${self.id}:$weekStart"
        return try {
            val env = api.get(
                "/rest/reservations/programs/v2",
                mapOf("selfId" to self.id.toString(), "weekStartDate" to "$weekStart 00:00:00")
            )
            val payload = env.obj()
            val plan = Parsers.week(weekStart, payload)
            // هفته‌ی خالی هم کش می‌شود (گذشته: آرشیو؛ آینده: مثل بقیه‌ی هفته‌های آینده)
            store.putWeekJson(key, payload.toString(), expires)
            // رزروهای تحویل‌شده دیگر به کد نیازی ندارند
            plan.reserves.filter { it.consumed }.forEach { store.removeCode(it.id) }
            plan
        } catch (e: ApiException) {
            // کد 10108005: برای این هفته برنامه‌ی غذایی تعریف نشده (سرور 400 می‌دهد).
            if (e.code == NO_PROGRAM) {
                val marker = JSONObject().put(EMPTY_MARK, true).put("message", e.userMessage)
                store.putWeekJson(key, marker.toString(), expires)
                WeekPlan(weekStart, emptyList(), emptyList(), e.userMessage)
            } else {
                throw e
            }
        }
    }

    /** رزرو یک گزینه؛ شناسه‌ی رزرو جدید را برمی‌گرداند. */
    suspend fun reserve(o: Option): Long {
        val body = JSONObject()
            .put("foodTypeId", o.foodTypeId)
            .put("mealTypeId", o.mealTypeId)
            .put("selectedCount", o.minCount)
            .put("freeFoodSelected", false)
            .put("selected", true)
        val env = api.call("PUT", "/rest/reserves/${o.programId}/reserve", body = body)
        val id = (env.payload as? JSONObject)?.lng("id") ?: 0L
        adjustCredit(-(o.price * o.minCount))
        markLogDirty()
        // کد تحویل همان لحظه‌ی رزرو گرفته و ذخیره می‌شود (خطای آن مانع رزرو نیست).
        if (id > 0) {
            try { fetchPickupCode(id, o.date) } catch (e: ApiException) { /* بعداً هنگام نیاز دوباره گرفته می‌شود */ }
        }
        return id
    }

    /** refundRial: مبلغ برگشتی به اعتبار (قیمت × تعداد)؛ آفلاین روی اعتبار کش‌شده اعمال می‌شود. */
    suspend fun cancel(reserveId: Long, refundRial: Long) {
        api.call("DELETE", "/rest/reserves/$reserveId")
        store.removeCode(reserveId)
        adjustCredit(refundRial)
        markLogDirty()
    }

    /** کد تحویل یک رزرو: اول از کش، وگرنه از سرور (و ذخیره تا پایان روز استفاده). */
    suspend fun pickupCode(o: Option): PickupCode {
        val reserveId = o.reserve?.id ?: throw ApiException("این وعده رزرو نشده است.")
        store.getCodeJson(reserveId)?.let { raw ->
            runCatching { Parsers.pickupCode(JSONObject(raw)) }.getOrNull()
                ?.takeIf { it.valid && it.code.isNotBlank() }?.let { return it }
        }
        return fetchPickupCode(reserveId, o.date)
    }

    private suspend fun fetchPickupCode(reserveId: Long, serveDate: String): PickupCode {
        val env = api.get(
            "/rest/forget-card-codes/print",
            mapOf("reserveId" to reserveId.toString(), "count" to "1", "dailySale" to "false")
        )
        val p = env.obj()
        val pc = Parsers.pickupCode(p)
        if (pc.valid && pc.code.isNotBlank()) {
            // تا پایان روزِ استفاده؛ با تحویل غذا یا لغو رزرو زودتر پاک می‌شود.
            val expires = runCatching {
                LocalDate.parse(serveDate).plusDays(1).atStartOfDay(TEHRAN).toInstant().toEpochMilli()
            }.getOrDefault(System.currentTimeMillis() + WEEK_MS)
            store.putCodeJson(reserveId, p.toString(), expires)
        }
        return pc
    }

    /** آخرین اعتبار ذخیره‌شده (cachedAt = زمان آخرین دریافت از سرور) یا null. */
    fun cachedCredit(): Credit? = store.getCreditJson()?.let { st ->
        runCatching { Parsers.credit(JSONObject(st.json)).copy(cachedAt = st.fetchedAt) }.getOrNull()
    }

    /**
     * اعتبار: فقط بار اول (یا تازه‌سازی دستی / پس از افزایش اعتبار) از سرور گرفته می‌شود.
     * رزرو، لغو و انتقال آفلاین روی مقدار ذخیره‌شده اعمال می‌شوند (adjustCredit).
     * اگر سرور جواب ندهد، آخرین مقدار ذخیره‌شده با پیام خطا برمی‌گردد.
     */
    suspend fun credit(force: Boolean = false): Credit {
        if (!force) cachedCredit()?.let { return it }
        try {
            val p = api.get("/rest/users/nurture-profiles").obj()
            store.putCreditJson(p.toString(), System.currentTimeMillis())
            return Parsers.credit(p)
        } catch (e: ApiException) {
            cachedCredit()?.let { return it.copy(cacheError = e.userMessage) }
            throw e
        }
    }

    /** تغییر آفلاین مبلغ اعتبار ذخیره‌شده (ریال)؛ زمان آخرین دریافت از سرور تغییر نمی‌کند. */
    private fun adjustCredit(deltaRial: Long) {
        val st = store.getCreditJson() ?: return
        runCatching {
            val p = JSONObject(st.json)
            p.put("credit", p.lng("credit") + deltaRial)
            store.putCreditJson(p.toString(), st.fetchedAt)
        }
    }

    /**
     * آدرس صفحه‌ی شارژ کیف پول برای باز شدن در مرورگر.
     * فقط یک درخواست لازم است: توکن ورود یک‌بارمصرف؛ انتخاب مبلغ و درگاه را خود صفحه انجام می‌دهد.
     */
    suspend fun walletChargeUrl(): String {
        val token = api.get("/rest/users/me/login-token").obj().str("loginAsToken")
            ?: throw ApiException("توکن ورود به صفحه‌ی پرداخت دریافت نشد.")
        // مبلغ فعلی را نگه می‌داریم تا پس از بازگشت، مقدار افزایش مشخص شود
        store.paymentBase = cachedCredit()?.credit
        return "https://saba.tvu.ac.ir/j_security_check?loginAsToken=$token" +
            "&redirect=/nurture/user/credit/charge/view.rose?callBackAppUrl=$WALLET_CALLBACK"
    }

    /**
     * انتقال اعتبار به کاربر دیگر (بدون کارمزد). مبلغ به ریال فرستاده می‌شود؛
     * رابط کاربری تومان می‌گیرد و قبل از فراخوانی ×۱۰ می‌کند.
     */
    suspend fun transferCredit(username: String, amountRial: Long): TransferResult {
        val body = JSONObject()
            .put("username", username)
            .put("creditTransferAmount", amountRial)
            .put("nurtureProfileId", JSONObject.NULL)
        val env = api.call("PUT", "/rest/credit-transfers", body = body)
        adjustCredit(-amountRial)
        markLogDirty()
        return Parsers.transfer(env.obj(), username, amountRial)
    }

    /**
     * تغییر رمز. رمز فعلی اول با رمز ذخیره‌شده مقایسه می‌شود تا درخواست بی‌خود نرود؛
     * پس از موفقیت، رمز جدید برای ورود خودکار ذخیره می‌شود.
     */
    suspend fun changePassword(current: String, new: String) {
        if (current != store.password()) throw ApiException("رمز فعلی درست نیست.")
        val body = JSONObject()
            .put("password", current)
            .put("newPassword", new)
            .put("repeatNewPassword", new)
        api.call("PUT", "/rest/users/me/change-password", body = body)
        store.username()?.let { store.saveCredentials(it, new) }
    }

    private fun parseLog(raw: String): List<ActivityItem>? =
        runCatching { Parsers.activities(JSONObject().put("content", org.json.JSONArray(raw))).first }.getOrNull()

    fun cachedActivityLog(): List<ActivityItem>? = store.getLogJson()?.let { parseLog(it.json) }

    /** بعد از هر اقدام (رزرو، لغو، انتقال، افزایش اعتبار) صدا زده می‌شود تا دفعه‌ی بعد از سرور گرفته شود. */
    fun markLogDirty() { store.logDirty = true }

    /**
     * گزارش عملکرد ۳۰ روز اخیر: همیشه کش ترجیح داده می‌شود، مگر اینکه force باشد یا اقدامی
     * (markLogDirty) رخ داده باشد. صفحه‌به‌صفحه ۲۰۰تایی؛ معمولاً یک درخواست.
     */
    suspend fun activityLog(force: Boolean = false): List<ActivityItem> {
        if (!force && !store.logDirty) cachedActivityLog()?.let { return it }
        val to = LocalDate.now().plusDays(1)
        val from = to.minusDays(31)
        val all = ArrayList<ActivityItem>()
        val raw = org.json.JSONArray()
        var page = 0
        while (page < 5) {
            val p = api.get(
                "/rest/users/activity-logs",
                mapOf("page" to page.toString(), "size" to "200", "fromDate" to from.toString(), "toDate" to to.toString())
            ).obj()
            val (items, last) = Parsers.activities(p)
            all.addAll(items)
            p.optJSONArray("content")?.let { arr -> for (i in 0 until arr.length()) raw.put(arr.get(i)) }
            if (last) break
            page++
        }
        store.putLogJson(raw.toString())
        return all
    }

    /** هفته‌ی ذخیره‌شده بدون هیچ درخواستی (برای نمایش فوری تا رسیدن پاسخ سرور). */
    fun peekWeek(weekStart: LocalDate): WeekPlan? {
        if (store.selfId <= 0) return null
        val st = store.getWeekJson("${store.selfId}:$weekStart") ?: return null
        val current = Jalali.weekStart(LocalDate.now())
        val plan = parseCached(weekStart, st) ?: return null
        val isPast = weekStart.isBefore(current)
        return plan.copy(archived = isPast || (weekStart == current && plan.isSettled))
    }

    /** همه‌ی وعده‌های رزروشده‌ی تحویل‌نشده (هفته‌ی جاری و هفته‌های بعد) فقط از کش؛ null اگر هفته‌ی جاری ذخیره نشده. */
    fun peekUpcoming(): Upcoming? {
        val today = LocalDate.now()
        val start = Jalali.weekStart(today)
        val cur = peekWeek(start)
        val list = ArrayList<Option>()
        var at: Long? = cur?.cachedAt
        cur?.let { list += upcomingOf(it, today) }
        for (i in 1 until UPCOMING_WEEKS) {
            val next = peekWeek(start.plusWeeks(i.toLong())) ?: break
            list += upcomingOf(next, today)
            next.cachedAt?.let { at = minOf(at ?: it, it) }
            if (next.days.isEmpty()) break
        }
        if (cur == null && list.isEmpty()) return null
        return Upcoming(list.sortedWith(compareBy({ it.date }, { Meals.rank(it.mealTypeId) })), at, null)
    }

    /**
     * همه‌ی وعده‌های رزروشده‌ی تحویل‌نشده از امروز به بعد، به ترتیب نزدیکی (صفحه‌ی تحویل سریع).
     * هفته‌ی جاری و تا دو هفته‌ی بعد بررسی می‌شود (توقف در اولین هفته‌ی خالی)؛ force فقط هفته‌ی جاری را تازه می‌کند.
     */
    suspend fun upcomingMeals(force: Boolean = false): Upcoming {
        val self = ensureSelf()
        val today = LocalDate.now()
        val start = Jalali.weekStart(today)
        val list = ArrayList<Option>()
        var cachedAt: Long? = null
        var cacheError: String? = null
        for (i in 0 until UPCOMING_WEEKS) {
            val plan = try {
                week(self, start.plusWeeks(i.toLong()), force && i == 0)
            } catch (e: ApiException) {
                if (i == 0) throw e
                cacheError = cacheError ?: e.userMessage // هفته‌های بعد در دسترس نبود؛ بقیه نمایش داده می‌شود
                break
            }
            list += upcomingOf(plan, today)
            plan.cachedAt?.let { cachedAt = minOf(cachedAt ?: it, it) }
            cacheError = cacheError ?: plan.cacheError
            if (i >= 1 && plan.days.isEmpty()) break
        }
        return Upcoming(list.sortedWith(compareBy({ it.date }, { Meals.rank(it.mealTypeId) })), cachedAt, cacheError)
    }

    private fun upcomingOf(plan: WeekPlan, today: LocalDate): List<Option> =
        plan.days.flatMap { it.options }
            .filter { o -> o.reserve?.let { it.selected && !it.consumed } == true && LocalDate.parse(o.date) >= today }
            .sortedWith(compareBy({ it.date }, { Meals.rank(it.mealTypeId) }))

    /** رمز یا نشست این حساب نامعتبر شده: توکن‌ها و رمز پاک می‌شوند اما حساب و کش‌هایش می‌مانند. */
    fun invalidateSession() {
        api.reset()
        store.clearTokens()
        store.clearPassword()
    }

    private companion object {
        const val CURRENT_TTL_MS = 30 * 60 * 1000L // هفته‌ی جاری
        const val FUTURE_TTL_MS = 24 * 3600 * 1000L // هفته‌های آینده
        const val ARCHIVE_WEEKS = 10L
        const val UPCOMING_WEEKS = 3 // تحویل سریع: هفته‌ی جاری + حداکثر دو هفته‌ی بعد
        const val WEEK_MS = 7L * 24 * 3600 * 1000
        /** مبنای تاریخ‌ها (پایان هفته و روز) همیشه تهران است. */
        val TEHRAN: java.time.ZoneId = java.time.ZoneId.of("Asia/Tehran")
        const val NO_PROGRAM = 10108005
        /** نشانه‌ی کش «هفته‌ی بدون برنامه» (سرور برای آن پاسخ خالی نمی‌دهد، خطا می‌دهد). */
        const val EMPTY_MARK = "__empty"

        /** آدرس بازگشت به همین برنامه پس از پرداخت (deep link در Manifest ثبت شده). */
        const val WALLET_CALLBACK = "samadx://wallet"
    }
}
