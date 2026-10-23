package ir.abdev.samadx.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

// ------------------------------------------------------------------ مدل‌ها

data class Self(val id: Long, val name: String)

/** رزرو ثبت‌شده‌ی کاربر برای یک برنامه. */
data class Reserve(
    val id: Long,
    val programId: Long,
    val foodTypeId: Long,
    val mealTypeId: Int,
    val consumed: Boolean,
    val selected: Boolean,
    val count: Int,
    val price: Long,
    val foodNames: String
)

/** یک گزینه‌ی قابل رزرو (یک نوع غذا از یک برنامه‌ی روزانه) به‌همراه رزرو فعلی کاربر (اگر دارد). */
data class Option(
    val programId: Long,
    val date: String, // yyyy-MM-dd
    val mealTypeId: Int,
    val mealName: String,
    val foodTypeId: Long,
    val foodTypeTitle: String,
    val foodName: String,
    val sides: String,
    val price: Long,
    val score: Double,
    val votes: Int,
    val canReserve: Boolean,
    val canCancel: Boolean,
    val minCount: Int,
    val reserve: Reserve?
) {
    val key: String get() = "$programId:$foodTypeId"
}

data class DayPlan(val date: LocalDate, val options: List<Option>)

data class WeekPlan(
    val weekStart: LocalDate,
    val days: List<DayPlan>,
    val reserves: List<Reserve>,
    val message: String? = null,
    /** اگر از کش (نه سرور) آمده: زمان آخرین دریافت از سرور (میلی‌ثانیه)؛ وگرنه null. */
    val cachedAt: Long? = null,
    /** اگر به‌خاطر خطای سرور از کش نمایش داده شده: پیام خطا. */
    val cacheError: String? = null,
    val archived: Boolean = false,
    /** فقط رزروهای کاربر (از /rest/reserves)؛ برنامه‌ی کامل در دسترس نبود. هرگز کش/آرشیو نمی‌شود. */
    val partial: Boolean = false
) {
    /**
     * «تمام‌شده»: هر وعده یا تحویل شده، یا نه رزرو دارد و نه دیگر قابل رزرو است (مهلت تمام شده).
     * چنین هفته‌ای دیگر هرگز تغییر نمی‌کند؛ رزروِ تحویل‌نشده (حتی اگر قفل شده) تمام‌شده حساب نمی‌شود.
     */
    val isSettled: Boolean
        get() = days.isNotEmpty() && days.all { d ->
            d.options.all { o -> o.reserve?.consumed == true || (o.reserve == null && !o.canReserve) }
        }

    val activeReserves: List<Reserve> get() = reserves.filter { it.selected }
    val totalPrice: Long get() = activeReserves.sumOf { it.price * it.count }
}

data class CreditChange(val date: String, val amount: Long, val receipt: String?)

data class Credit(
    val credit: Long,
    val subsidy: Long,
    val changes: List<CreditChange>,
    val cachedAt: Long? = null,
    val cacheError: String? = null
)

enum class ActivityKind { RESERVE, CANCEL, DELIVERY, CREDIT_IN, CREDIT_SENT, CREDIT_RECEIVED, SYSTEM_CANCEL, OTHER }

/** یک ردیف گزارش عملکرد. amountRial: مثبت = افزایش اعتبار، منفی = کاهش، null = بدون اثر مالی. */
data class ActivityItem(
    val id: Long,
    val kind: ActivityKind,
    val title: String,
    val detail: String,
    val extra: String?,
    val amountRial: Long?,
    val occur: String
)

data class TransferResult(val recipient: String, val amountRial: Long)

data class PickupCode(
    val code: String,
    val food: String,
    val meal: String,
    val self: String,
    val remain: Int,
    val valid: Boolean
)

// ------------------------------------------------------------------ پارسرها

internal fun JSONObject.str(key: String): String? =
    if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

internal fun JSONObject.lng(key: String, def: Long = 0L): Long =
    if (isNull(key)) def else optLong(key, def)

private fun JSONArray?.objects(): List<JSONObject> {
    if (this == null) return emptyList()
    val out = ArrayList<JSONObject>(length())
    for (i in 0 until length()) optJSONObject(i)?.let { out.add(it) }
    return out
}

object Parsers {

    fun selfs(arr: JSONArray): List<Self> =
        arr.objects().map { Self(it.lng("id"), it.str("name") ?: "سلف ${it.lng("id")}") }

    fun reserve(o: JSONObject) = Reserve(
        id = o.lng("id"),
        programId = o.lng("programId"),
        foodTypeId = o.lng("foodTypeId"),
        mealTypeId = o.optInt("mealTypeId"),
        consumed = o.optBoolean("consumed"),
        selected = o.optBoolean("selected", true),
        count = maxOf(1, o.optInt("selectedCount", 1)),
        price = o.lng("price"),
        foodNames = o.str("foodNames") ?: ""
    )

    fun week(weekStart: LocalDate, payload: JSONObject): WeekPlan {
        val reserves = payload.optJSONArray("userWeekReserves").objects().map { reserve(it) }

        val options = ArrayList<Option>()
        val weeks = payload.optJSONArray("selfWeekPrograms")
        if (weeks != null) {
            for (i in 0 until weeks.length()) {
                for (program in weeks.optJSONArray(i).objects()) {
                    options.addAll(programOptions(program, reserves))
                }
            }
        }

        val days = options
            .groupBy { it.date }
            .toSortedMap()
            .map { (date, list) -> DayPlan(LocalDate.parse(date), list.sortedBy { it.mealTypeId }) }

        return WeekPlan(weekStart, days, reserves)
    }

    /**
     * پاسخ /rest/reserves (فقط رزروهای کاربر) را به یک هفته‌ی «ناقص» تبدیل می‌کند؛ گزینه‌ای جز رزروها ندارد
     * و رزرو/لغو در آن غیرفعال است. فقط رزروهای همان سلف نگه داشته می‌شوند. remainCredit (ریال) هم برمی‌گردد.
     */
    fun reservesWeek(weekStart: LocalDate, payload: JSONObject, selfId: Long): Pair<WeekPlan, Long?> {
        val reserves = ArrayList<Reserve>()
        val days = ArrayList<DayPlan>()
        for (d in payload.optJSONArray("weekDays").objects()) {
            val opts = ArrayList<Option>()
            for (m in d.optJSONArray("mealTypes").objects()) {
                val r = m.optJSONObject("reserve") ?: continue
                if (!r.optBoolean("selected", true)) continue
                if (r.has("selfId") && !r.isNull("selfId") && r.lng("selfId") != selfId) continue
                val rv = reserve(r)
                reserves += rv
                opts += Option(
                    programId = rv.programId,
                    date = r.str("programDate") ?: d.str("date") ?: continue,
                    mealTypeId = rv.mealTypeId,
                    mealName = m.str("name") ?: "",
                    foodTypeId = rv.foodTypeId,
                    foodTypeTitle = r.str("foodTypeTitle") ?: "",
                    foodName = rv.foodNames,
                    sides = (r.str("besideFoodNames") ?: "").split(",").map { it.trim() }.filter { it.isNotEmpty() }.joinToString("، "),
                    price = rv.price,
                    score = 0.0,
                    votes = 0,
                    canReserve = false,
                    canCancel = false,
                    minCount = rv.count,
                    reserve = rv
                )
            }
            if (opts.isNotEmpty()) days += DayPlan(LocalDate.parse(d.str("date") ?: continue), opts)
        }
        val remain = if (payload.has("remainCredit") && !payload.isNull("remainCredit")) payload.lng("remainCredit") else null
        return WeekPlan(weekStart, days, reserves, partial = true) to remain
    }

    private fun programOptions(p: JSONObject, reserves: List<Reserve>): List<Option> {
        if (p.optBoolean("hideInPanel")) return emptyList()
        val programId = p.lng("programId")
        val date = p.str("date") ?: return emptyList()
        val mealId = p.optInt("mealTypeId")
        val mealName = p.str("mealTypeName") ?: ""
        val canReserve = !p.optBoolean("reserveRuleViolated")
        val canCancel = !p.optBoolean("cancelRuleViolated")

        fun reserveFor(foodTypeId: Long) =
            reserves.firstOrNull { it.programId == programId && it.foodTypeId == foodTypeId && it.selected }

        val out = ArrayList<Option>()
        val types = p.optJSONArray("programFoodTypes").objects()
        for (t in types) {
            if (t.optBoolean("hideInPanel")) continue
            val foodTypeId = t.lng("foodTypeId")
            val firstFood = t.optJSONArray("foodList").objects().firstOrNull()
            val sides = t.optJSONArray("besideFoodList").objects().mapNotNull { it.str("title") }
            out.add(
                Option(
                    programId = programId,
                    date = date,
                    mealTypeId = mealId,
                    mealName = mealName,
                    foodTypeId = foodTypeId,
                    foodTypeTitle = t.str("foodTypeTitle") ?: p.str("foodTypeTitle") ?: "",
                    foodName = t.str("foodNames") ?: p.str("foodName") ?: "",
                    sides = sides.joinToString("، "),
                    price = t.lng("price", p.lng("price")),
                    score = firstFood?.optDouble("averageScore", 0.0) ?: 0.0,
                    votes = firstFood?.optInt("voteCount") ?: 0,
                    canReserve = canReserve,
                    canCancel = canCancel,
                    minCount = maxOf(1, t.optInt("validMinCount", 1)),
                    reserve = reserveFor(foodTypeId)
                )
            )
        }
        if (out.isEmpty()) {
            // اگر سرور programFoodTypes نداد، از فیلدهای سطح بالا استفاده می‌کنیم.
            val foodTypeId = p.lng("foodTypeId")
            out.add(
                Option(
                    programId, date, mealId, mealName, foodTypeId,
                    p.str("foodTypeTitle") ?: "", p.str("foodName") ?: "", "",
                    p.lng("price"), 0.0, 0, canReserve, canCancel,
                    maxOf(1, p.optInt("validTotalCount", 1)), reserveFor(foodTypeId)
                )
            )
        }
        return out
    }

    fun credit(p: JSONObject): Credit {
        val changes = p.optJSONArray("creditChanges").objects().map {
            CreditChange(
                date = it.str("createDate") ?: "",
                amount = it.lng("amount"),
                receipt = it.optJSONObject("receipt")?.str("receiptNumber")
            )
        }
        return Credit(p.lng("credit"), p.lng("subsidyCredit"), changes)
    }

    fun transfer(p: JSONObject, fallbackName: String, amountRial: Long): TransferResult {
        val u = p.optJSONObject("user")
        val name = listOfNotNull(u?.str("firstName"), u?.str("lastName")).joinToString(" ").ifBlank { fallbackName }
        return TransferResult(name, p.lng("creditTransferAmount", amountRial))
    }

    fun pickupCode(p: JSONObject) = PickupCode(
        code = p.str("forgotCardCode") ?: "",
        food = p.str("foodName") ?: "",
        meal = p.str("meal") ?: "",
        self = p.str("self") ?: "",
        remain = p.optInt("remainCount"),
        valid = p.optBoolean("valid", true)
    )

    private val tehran = java.time.ZoneId.of("Asia/Tehran")

    private fun serveDay(ms: Long): String =
        if (ms <= 0) "" else ir.abdev.samadx.util.Jalali.formatNoYear(
            java.time.Instant.ofEpochMilli(ms).atZone(tehran).toLocalDate()
        )

    /** payload فهرست‌شده‌ی /rest/users/activity-logs ← (ردیف‌ها، آخرین صفحه است؟). */
    fun activities(payload: JSONObject): Pair<List<ActivityItem>, Boolean> {
        val out = ArrayList<ActivityItem>()
        for (e in payload.optJSONArray("content").objects()) {
            val j = try { JSONObject(e.str("activityTypeJson") ?: "{}") } catch (ex: Exception) { JSONObject() }
            val cls = (e.str("activityTypeClassName") ?: "").substringAfterLast('.')
            val occur = (e.str("occurDate") ?: "").take(19)
            val food = listOfNotNull(j.str("foodNames"), j.str("mealTypeName"), serveDay(j.lng("serveDate")).ifEmpty { null })
                .joinToString(" · ")
            val remain = if (j.has("remainCredit")) ir.abdev.samadx.util.Fa.money(j.lng("remainCredit")) else null
            val remainLine = remain?.let { "مانده‌ی کیف پول: $it" }
            val total = j.lng("price") * maxOf(1, j.optInt("selectedCount", 1))
            val item = when (cls) {
                "MobileAppReservation" -> ActivityItem(0, ActivityKind.RESERVE, "رزرو غذا", food, remainLine, -total, occur)
                "CancelType" -> ActivityItem(0, ActivityKind.CANCEL, "لغو رزرو", food, remainLine, total, occur)
                "CancelGroupType" -> ActivityItem(0, ActivityKind.SYSTEM_CANCEL, "لغو رزرو توسط سامانه", food, remainLine, total, occur)
                "OnlineFoodDeliveryByForgottenCodeType" -> ActivityItem(0, ActivityKind.DELIVERY, "دریافت غذا", food, j.str("deviceName")?.let { "دستگاه: $it" }, null, occur)
                "ReceiveCreditLog" -> ActivityItem(0, ActivityKind.CREDIT_RECEIVED, "دریافت موجودی", "", remainLine, j.lng("receivedCredit"), occur)
                "SendCredit" -> ActivityItem(
                    0, ActivityKind.CREDIT_SENT, "انتقال موجودی",
                    "به " + listOfNotNull(j.str("receiverFirstName"), j.str("receiverLastName")).joinToString(" ").ifBlank { j.str("receiverUserName") ?: "" },
                    remainLine, -j.lng("sentCredit"), occur
                )
                "CreditIncreaseInternet" -> ActivityItem(
                    0, ActivityKind.CREDIT_IN, "افزایش موجودی", j.str("receipt")?.let { "شماره‌ی پیگیری: ${ir.abdev.samadx.util.Fa.digits(it)}" } ?: "",
                    remainLine, j.lng("diff"), occur
                )
                else -> ActivityItem(0, ActivityKind.OTHER, "فعالیت", e.str("description") ?: "", remainLine, null, occur)
            }
            out.add(item.copy(id = e.lng("id")))
        }
        return out to payload.optBoolean("last", true)
    }
}
