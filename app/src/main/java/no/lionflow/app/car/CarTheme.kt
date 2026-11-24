package no.lionflow.app.car

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.car.app.CarContext
import androidx.core.content.ContextCompat

/** Colours for the screens LionFlow draws itself in the car, in a dark and a light version. */
class CarPalette(
    val dark: Boolean,
    val bg: Int, val bg2: Int, val card: Int, val raised: Int, val line: Int,
    val text: Int, val muted: Int,
    /** The LionFlow green for fills and lines, and the one readable as text on the background. */
    val accent: Int, val accentText: Int, val glow: Int,
    val orange: Int, val red: Int, val driven: Int,
    /** Soft shadows: dark below right, light above left (the raised, "neumorphic" look). */
    val shadowDark: Int, val shadowLight: Int,
    val onAccent: Int,
)

object CarTheme {
    private const val KEY = "carTheme"   // "dark", "light", or empty = follow the car

    val DARK = CarPalette(
        true,
        bg = 0xFF16181C.toInt(), bg2 = 0xFF1D2025.toInt(), card = 0xFF1F2227.toInt(), raised = 0xFF272B31.toInt(), line = 0xFF32363D.toInt(),
        text = 0xFFF3F4F6.toInt(), muted = 0xFF8E939B.toInt(),
        accent = 0xFFC6F36B.toInt(), accentText = 0xFFC6F36B.toInt(), glow = 0x66C6F36B,
        orange = 0xFFFF9F43.toInt(), red = 0xFFFF5A5F.toInt(), driven = 0xFF3C4148.toInt(),
        shadowDark = 0xCC08090B.toInt(), shadowLight = 0x332F343B,
        onAccent = 0xFF14161A.toInt(),
    )
    val LIGHT = CarPalette(
        false,
        bg = 0xFFE6E9EE.toInt(), bg2 = 0xFFDCE0E6.toInt(), card = 0xFFECEFF3.toInt(), raised = 0xFFF3F5F8.toInt(), line = 0xFFD2D7DE.toInt(),
        text = 0xFF1A1D22.toInt(), muted = 0xFF68707A.toInt(),
        accent = 0xFF7DBB12.toInt(), accentText = 0xFF4C7F00.toInt(), glow = 0x557DBB12,
        orange = 0xFFE8770E.toInt(), red = 0xFFD63B40.toInt(), driven = 0xFFC2C8D0.toInt(),
        shadowDark = 0x8C9AA4B3.toInt(), shadowLight = 0xFFFFFFFF.toInt(),
        onAccent = 0xFFFFFFFF.toInt(),
    )

    /** The palette in use. Set from [refresh] by each screen before it draws. */
    @Volatile var p: CarPalette = DARK
        private set

    fun refresh(ctx: CarContext): CarPalette {
        val s = CarHub.app(ctx).settings.raw(KEY)
        p = when (s) { "light" -> LIGHT; "dark" -> DARK; else -> if (ctx.isDarkMode) DARK else LIGHT }
        return p
    }

    fun toggle(ctx: CarContext) {
        val next = if (refresh(ctx).dark) "light" else "dark"
        CarHub.app(ctx).settings.setRaw(KEY, next)
        refresh(ctx)
    }
}

/** Drawing helpers shared by the car screens LionFlow draws itself. */
internal class Ink(val d: Float, val pal: CarPalette) {
    fun dp(v: Float) = v * d
    val regular: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    val bold: Typeface = Typeface.create("sans-serif", Typeface.BOLD)
    val light: Typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)

    fun text(size: Float, color: Int, face: Typeface = regular, align: Paint.Align = Paint.Align.LEFT) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = dp(size); this.color = color; typeface = face; textAlign = align }

    /** A flat card with a fine edge (a little darker or lighter while pressed). Flat is quick to draw. */
    fun card(c: Canvas, r: RectF, radius: Float = dp(22f), fill: Int = pal.card, pressed: Boolean = false) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = if (pressed) (if (pal.dark) lighten(fill, 0.08f) else darken(fill, 0.06f)) else fill
        c.drawRoundRect(r, radius, radius, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = dp(1f); p.color = pal.line
        val h = p.strokeWidth / 2
        c.drawRoundRect(RectF(r.left + h, r.top + h, r.right - h, r.bottom - h), radius, radius, p)
    }

    /** A round badge with an icon; [lit] fills it with the LionFlow green. */
    fun badge(c: Canvas, ctx: Context, cx: Float, cy: Float, r: Float, icon: Int, lit: Boolean, tint: Int? = null) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = if (lit) pal.accent else pal.raised
        c.drawCircle(cx, cy, r, p)
        icon(c, ctx, icon, cx, cy, r * 1.0f, tint ?: if (lit) pal.onAccent else pal.text)
    }

    fun icon(c: Canvas, ctx: Context, res: Int, cx: Float, cy: Float, size: Float, color: Int) {
        val dr = ContextCompat.getDrawable(ctx, res)?.mutate() ?: return
        dr.setTint(color)
        val h = size / 2
        dr.setBounds((cx - h).toInt(), (cy - h).toInt(), (cx + h).toInt(), (cy + h).toInt())
        dr.draw(c)
    }

    fun ellipsize(s: String, p: Paint, width: Float): String {
        if (width <= 0f || p.measureText(s) <= width) return s
        var e = s
        while (e.isNotEmpty() && p.measureText("$e…") > width) e = e.dropLast(1)
        return "$e…"
    }

    companion object {
        fun lighten(c: Int, f: Float): Int = mix(c, 0xFFFFFFFF.toInt(), f)
        fun darken(c: Int, f: Float): Int = mix(c, 0xFF000000.toInt(), f)
        fun mix(a: Int, b: Int, f: Float): Int {
            fun ch(x: Int, s: Int) = (x shr s) and 0xFF
            fun m(s: Int) = (ch(a, s) + (ch(b, s) - ch(a, s)) * f).toInt().coerceIn(0, 255)
            return (ch(a, 24) shl 24) or (m(16) shl 16) or (m(8) shl 8) or m(0)
        }
    }
}
