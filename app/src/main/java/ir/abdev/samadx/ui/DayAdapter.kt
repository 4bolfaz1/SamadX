package ir.abdev.samadx.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import ir.abdev.samadx.R
import ir.abdev.samadx.data.DayPlan
import ir.abdev.samadx.data.Option
import ir.abdev.samadx.databinding.ItemDayBinding
import ir.abdev.samadx.databinding.ItemOptionBinding
import ir.abdev.samadx.util.Fa
import ir.abdev.samadx.util.Jalali
import com.google.android.material.color.MaterialColors
import android.graphics.drawable.GradientDrawable
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** هر ردیف = یک روز؛ زیر هر روز کارت‌های گزینه‌های غذا ساخته می‌شود (حداکثر ۷ روز، پس ساده نگه داشتیم). */
class DayAdapter(
    private val onReserve: (Option) -> Unit,
    private val onCancel: (Option) -> Unit,
    private val onCode: (Option) -> Unit
) : RecyclerView.Adapter<DayAdapter.VH>() {

    private var days: List<DayPlan> = emptyList()
    private var busy: Set<String> = emptySet()

    fun submit(days: List<DayPlan>, busy: Set<String>) {
        this.days = days
        this.busy = HashSet(busy)
        notifyDataSetChanged()
    }

    fun setBusy(busy: Set<String>) {
        this.busy = HashSet(busy)
        notifyDataSetChanged()
    }

    class VH(val b: ItemDayBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemDayBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = days.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val day = days[position]
        val b = holder.b
        b.dayName.text = Jalali.weekdayName(day.date)
        b.dayDate.text = Jalali.format(day.date)
        styleHeader(b, ChronoUnit.DAYS.between(LocalDate.now(), day.date))

        b.optionsContainer.removeAllViews()
        val inflater = LayoutInflater.from(b.root.context)
        for (option in day.options) {
            val ob = ItemOptionBinding.inflate(inflater, b.optionsContainer, true)
            bindOption(ob, option)
        }
    }

    /**
     * برچسب نسبی («پریروز»…«۳ روز بعد») با رنگ جدا: گذشته = خاکستری کم‌رنگ، آینده = کهربایی،
     * امروز = پررنگ‌ترین (برچسب توپر با رنگ اصلی + سرِ روز با پس‌زمینه‌ی رنگی و نوشته‌ی ضخیم).
     */
    /** ضخیم‌کردن با paint (بدون تغییر فونت)، تا با بازیافت ردیف‌ها حالت امروز به روزهای دیگر نچسبد. */
    private fun bold(t: android.widget.TextView, on: Boolean) {
        if (t.paint.isFakeBoldText == on) return
        t.paint.isFakeBoldText = on
        t.requestLayout()
        t.invalidate()
    }

    private fun styleHeader(b: ItemDayBinding, diff: Long) {
        val density = b.root.resources.displayMetrics.density
        fun color(attr: Int) = MaterialColors.getColor(b.root, attr)
        fun pill(fill: Int) = GradientDrawable().apply {
            cornerRadius = 999f * density
            setColor(fill)
        }
        val m = com.google.android.material.R.attr.colorOnSurface
        val mv = com.google.android.material.R.attr.colorOnSurfaceVariant

        b.relLabel.text = Fa.relativeDay(diff, "بعد", "قبل")
        when {
            diff == 0L -> {
                val onContainer = color(com.google.android.material.R.attr.colorOnPrimaryContainer)
                b.dayHeader.background = GradientDrawable().apply {
                    cornerRadius = 16f * density
                    setColor(color(com.google.android.material.R.attr.colorPrimaryContainer))
                }
                b.dayName.setTextColor(onContainer)
                bold(b.dayName, true)
                b.dayDate.setTextColor(onContainer)
                b.relLabel.background = pill(color(androidx.appcompat.R.attr.colorPrimary))
                b.relLabel.setTextColor(color(com.google.android.material.R.attr.colorOnPrimary))
                bold(b.relLabel, true)
            }
            diff > 0 -> {
                bold(b.dayName, false); bold(b.relLabel, false)
                b.dayHeader.background = null
                b.dayName.setTextColor(color(m))
                b.dayDate.setTextColor(color(mv))
                b.relLabel.background = pill(color(com.google.android.material.R.attr.colorTertiaryContainer))
                b.relLabel.setTextColor(color(com.google.android.material.R.attr.colorOnTertiaryContainer))
            }
            else -> {
                bold(b.dayName, false); bold(b.relLabel, false)
                b.dayHeader.background = null
                b.dayName.setTextColor(color(mv))
                b.dayDate.setTextColor(color(mv))
                b.relLabel.background = pill(color(com.google.android.material.R.attr.colorSurfaceContainerHighest))
                b.relLabel.setTextColor(color(mv))
            }
        }
    }

    private fun bindOption(ob: ItemOptionBinding, o: Option) {
        val reserve = o.reserve
        val reserved = reserve != null
        val consumed = reserve?.consumed == true
        val working = o.key in busy

        ob.meal.text = if (o.foodTypeTitle.isBlank()) o.mealName else "${o.mealName} · ${o.foodTypeTitle}"
        ob.food.text = o.foodName
        ob.sides.isVisible = o.sides.isNotBlank()
        ob.sides.text = o.sides
        ob.rating.text = if (o.score > 0.0) "★ ${Fa.score(o.score)}" else ""
        ob.price.text = Fa.money(o.price)

        // کارتِ رزروشده با رنگ ثانویه‌ی Material 3 مشخص می‌شود
        val attr = if (reserved) {
            com.google.android.material.R.attr.colorSecondaryContainer
        } else {
            com.google.android.material.R.attr.colorSurfaceContainerHighest
        }
        ob.card.setCardBackgroundColor(MaterialColors.getColor(ob.card, attr))

        ob.btnReserve.isVisible = false
        ob.btnCancel.isVisible = false
        ob.btnCode.isVisible = false
        ob.status.isVisible = false

        fun showStatus(textRes: Int, iconRes: Int) {
            ob.status.setText(textRes)
            ob.status.setChipIconResource(iconRes)
            ob.status.isChipIconVisible = true
            ob.status.isVisible = true
        }

        when {
            consumed -> showStatus(R.string.status_consumed, R.drawable.ic_check_circle)

            reserved -> {
                ob.btnCode.isVisible = true
                ob.btnCode.isEnabled = !working
                ob.btnCode.setOnClickListener { onCode(o) }
                if (o.canCancel) {
                    ob.btnCancel.isVisible = true
                    ob.btnCancel.isEnabled = !working
                    ob.btnCancel.text = if (working) "…" else ob.root.context.getString(R.string.cancel_reserve)
                    ob.btnCancel.setOnClickListener { onCancel(o) }
                } else {
                    showStatus(R.string.status_reserved_locked, R.drawable.ic_lock)
                }
            }

            o.canReserve -> {
                ob.btnReserve.isVisible = true
                ob.btnReserve.isEnabled = !working
                ob.btnReserve.text = if (working) "…" else ob.root.context.getString(R.string.reserve)
                ob.btnReserve.setOnClickListener { onReserve(o) }
            }

            else -> showStatus(R.string.status_closed, R.drawable.ic_lock)
        }
    }
}
