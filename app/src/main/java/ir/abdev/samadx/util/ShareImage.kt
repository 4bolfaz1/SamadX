package ir.abdev.samadx.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.res.ResourcesCompat
import ir.abdev.samadx.R
import java.io.File
import java.io.FileOutputStream

/**
 * تصویر قابل اشتراک: عنوان وعده، غذا، QR، خود کد، راهنما و معرفی برنامه در گوشه‌ی پایین.
 * رنگ‌ها ثابت (روشن) هستند تا QR همیشه سیاه‌روی‌سفید و خوانا باشد.
 */
object ShareImage {

    private const val W = 1080
    private const val PAD = 72
    private val BG = Color.parseColor("#F5F3FA")
    private val INK = Color.parseColor("#1D1B20")
    private val MUTED = Color.parseColor("#625B71")

    fun create(
        ctx: Context,
        accent: Int,
        title: String,
        food: String,
        sides: String,
        code: String
    ): Uri {
        val regular = ResourcesCompat.getFont(ctx, R.font.vazirmatn) ?: Typeface.DEFAULT
        val bold = Typeface.create(regular, Typeface.BOLD)
        val textW = W - 2 * PAD

        fun layout(text: String, size: Float, color: Int, face: Typeface, align: Layout.Alignment = Layout.Alignment.ALIGN_CENTER, width: Int = textW, spacing: Float = 0f): StaticLayout {
            val p = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color
                textSize = size
                typeface = face
                letterSpacing = spacing
            }
            return StaticLayout.Builder.obtain(text, 0, text.length, p, width)
                .setAlignment(align)
                .setLineSpacing(0f, 1.15f)
                .build()
        }

        val titleL = layout(title, 52f, accent, bold)
        val foodL = layout(food, 66f, INK, bold)
        val sidesL = if (sides.isNotBlank()) layout(sides, 38f, MUTED, regular) else null
        val codeL = layout(code, 104f, accent, bold, spacing = 0.1f)
        val noteL = layout(ctx.getString(R.string.pickup_note), 34f, MUTED, regular)

        val qrBox = 760
        val qrPx = 700
        val footerH = 190

        var h = PAD + titleL.height + 14 + foodL.height
        if (sidesL != null) h += 10 + sidesL.height
        h += 56 + qrBox + 44 + codeL.height + 20 + noteL.height + 64 + footerH

        val bmp = Bitmap.createBitmap(W, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(BG)

        var y = PAD.toFloat()
        fun draw(l: StaticLayout, gap: Int = 0) {
            c.save()
            c.translate(PAD.toFloat(), y)
            l.draw(c)
            c.restore()
            y += l.height + gap
        }
        draw(titleL, 14)
        draw(foodL)
        if (sidesL != null) { y += 10; draw(sidesL) }
        y += 56

        // کارت سفید QR
        val card = RectF((W - qrBox) / 2f, y, (W + qrBox) / 2f, y + qrBox)
        val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#E3DEEC"); style = Paint.Style.STROKE; strokeWidth = 3f
        }
        c.drawRoundRect(card, 40f, 40f, cardPaint)
        c.drawRoundRect(card, 40f, 40f, stroke)
        val qr = QrCode.bitmap(code, qrPx)
        c.drawBitmap(qr, card.left + (qrBox - qrPx) / 2f, card.top + (qrBox - qrPx) / 2f, null)
        y += qrBox + 44

        draw(codeL, 20)
        draw(noteL)
        y += 64

        // گوشه‌ی پایین: معرفی برنامه (آیکون + نام + توضیح + لینک توسعه‌دهنده)
        val footer = RectF(PAD.toFloat(), y, (W - PAD).toFloat(), y + footerH)
        val fp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        c.drawRoundRect(footer, 36f, 36f, fp)
        c.drawRoundRect(footer, 36f, 36f, stroke)

        val iconSize = 120
        val iconRight = footer.right - 32f
        val iconTop = footer.top + (footerH - iconSize) / 2f
        ContextCompat.getDrawable(ctx, R.mipmap.ic_launcher)?.let { d ->
            d.setBounds(iconRight.toInt() - iconSize, iconTop.toInt(), iconRight.toInt(), iconTop.toInt() + iconSize)
            d.draw(c)
        }
        val tw = (footer.width() - 32 - iconSize - 24 - 32).toInt()
        val nameL = layout(ctx.getString(R.string.app_name), 40f, INK, bold, Layout.Alignment.ALIGN_CENTER, tw)
        val introL = layout(ctx.getString(R.string.share_intro), 28f, MUTED, regular, Layout.Alignment.ALIGN_CENTER, tw)
        val devL = layout("github.com/4bolfaz1/SamadX", 26f, accent, regular, Layout.Alignment.ALIGN_CENTER, tw)
        val blockH = nameL.height + introL.height + devL.height
        var ty = footer.top + (footerH - blockH) / 2f
        for (l in listOf(nameL, introL, devL)) {
            c.save()
            c.translate(footer.left + 32f, ty)
            l.draw(c)
            c.restore()
            ty += l.height
        }

        val dir = File(ctx.cacheDir, "shared").apply { mkdirs() }
        val file = File(dir, "pickup-code.png")
        FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        return FileProvider.getUriForFile(ctx, ctx.packageName + ".files", file)
    }
}
