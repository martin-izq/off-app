package app.getoff

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.widget.RemoteViews
import java.time.DayOfWeek
import java.time.LocalDate

/** A · Semana (2×2): hoy hacia la meta, la semana y la racha. */
class Widget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) = update(ctx)
    override fun onAppWidgetOptionsChanged(ctx: Context, mgr: AppWidgetManager, id: Int, options: android.os.Bundle) = update(ctx)

    companion object {
        /** Refresca los dos widgets. Lo llaman sync() (worker de 15 min y la app) y el cambio de modo. */
        fun update(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val small = mgr.getAppWidgetIds(ComponentName(ctx, Widget::class.java))
            val wide = mgr.getAppWidgetIds(ComponentName(ctx, WidgetWide::class.java))
            if (small.isEmpty() && wide.isEmpty()) return
            val s = WidgetState.read(ctx)
            // Cada widget con su tamaño real: la semana llena el alto que sobra sin deformarse.
            small.forEach { id -> val (w, h) = size(mgr, id, 160, 170); mgr.updateAppWidget(id, s.small(ctx, w, h)) }
            wide.forEach { id -> val (w, h) = size(mgr, id, 330, 170); mgr.updateAppWidget(id, s.wide(ctx, w, h)) }
        }

        /** Ancho y alto en dp que le dio el launcher (en vertical: ancho mínimo, alto máximo). */
        private fun size(mgr: AppWidgetManager, id: Int, w: Int, h: Int): Pair<Int, Int> {
            val o = mgr.getAppWidgetOptions(id)
            val ow = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
            val oh = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
            return (if (ow > 0) ow else w) to (if (oh > 0) oh else h)
        }
    }
}

/** D · Completo (4×2): total y nivel, la semana, la racha y hoy. */
class WidgetWide : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) = Widget.update(ctx)
    override fun onAppWidgetOptionsChanged(ctx: Context, mgr: AppWidgetManager, id: Int, options: android.os.Bundle) = Widget.update(ctx)
}

/** Lo que muestran los widgets, leído del Store sin recalcular nada. */
private class WidgetState(
    val dark: Boolean,
    val total: Int,
    val level: Int,
    val levelProgress: Float,
    val today: Int, // puntos hacia la meta (puede ser negativo)
    val goal: Int,
    val met: Boolean,
    val streak: Int,
    val week: List<Float>, // lun..dom: 1 cumplido, 0..1 hoy, -1 comodín, 0 nada
) {
    private val ink = if (dark) 0xFFF2F2EF.toInt() else 0xFF111111.toInt()
    private val muted = if (dark) 0xFF9A9A96.toInt() else 0xFF6B6B66.toInt()
    private val line = if (dark) 0xFF2A2A2D.toInt() else 0xFFE4E4DF.toInt()
    private val soft = if (dark) 0xFF4A2219.toInt() else 0xFFFFC9BB.toInt()

    private fun base(ctx: Context, layout: Int) = RemoteViews(ctx.packageName, layout).apply {
        setInt(R.id.root, "setBackgroundResource", if (dark) R.drawable.widget_bg_dark else R.drawable.widget_bg)
        setTextViewText(R.id.brand, colored("off.", 3, 4, ACCENT))
        setTextColor(R.id.brand, ink)
        setOnClickPendingIntent(
            R.id.root, PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE),
        )
    }

    private val todayText get() = if (today > 0) "+$today" else "$today"
    private fun streakText(): CharSequence = t("Racha $streak", "Streak $streak").let { colored(it, it.length - "$streak".length, it.length, ACCENT, bold = true) }

    fun small(ctx: Context, wDp: Int, hDp: Int) = base(ctx, R.layout.widget).apply {
        setTextViewText(R.id.today, todayText)
        setTextColor(R.id.today, if (today < 0) ink else ACCENT)
        setTextViewText(R.id.goal, if (met) t("meta cumplida", "goal met") else t("de $goal hoy", "of $goal today"))
        setTextColor(R.id.goal, muted)
        setImageViewBitmap(R.id.week, weekBitmap(ctx, wDp - 28, (hDp - 134).coerceIn(24, 56), 7f, 4f))
        setTextViewText(R.id.streak, streakText())
        setTextColor(R.id.streak, muted)
    }

    fun wide(ctx: Context, wDp: Int, hDp: Int) = base(ctx, R.layout.widget_wide).apply {
        setTextViewText(R.id.total, thousands(total))
        setTextColor(R.id.total, ink)
        setTextViewText(R.id.level, t("Nivel $level", "Level $level"))
        setTextColor(R.id.level, muted)
        setImageViewBitmap(R.id.levelBar, levelBitmap(ctx))
        setTextColor(R.id.weekLabel, muted)
        setTextViewText(R.id.streak, streakText())
        setTextColor(R.id.streak, muted)
        setImageViewBitmap(R.id.week, weekBitmap(ctx, (wDp - 168).coerceAtLeast(120), (hDp - 84).coerceIn(24, 72), 9f, 5f))
        val rest = if (met) t(" · meta cumplida", " · goal met") else t(" de $goal hoy · faltan ${(goal - today).coerceAtLeast(1)}", " of $goal today · ${(goal - today).coerceAtLeast(1)} to go")
        setTextViewText(R.id.todayLine, colored(todayText + rest, 0, todayText.length, if (today < 0) ink else ACCENT, bold = true))
        setTextColor(R.id.todayLine, muted)
    }

    // RemoteViews no arma barras propias: van como bitmap.
    private fun weekBitmap(ctx: Context, wDp: Int, hDp: Int, radiusDp: Float, gapDp: Float): Bitmap {
        val px = ctx.resources.displayMetrics.density
        val w = (wDp * px).toInt()
        val h = (hDp * px).toInt()
        val gap = gapDp * px
        val r = radiusDp * px
        val seg = (w - gap * 6) / 7
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        week.forEachIndexed { i, v ->
            val box = RectF(i * (seg + gap), 0f, i * (seg + gap) + seg, h.toFloat())
            paint.color = when {
                v >= 1f -> ACCENT
                v < 0f -> soft
                else -> line
            }
            c.drawRoundRect(box, r, r, paint)
            if (v > 0f && v < 1f) {
                // Hoy: se llena de abajo hacia arriba según la meta.
                c.save()
                c.clipRect(box.left, box.bottom - box.height() * v, box.right, box.bottom)
                paint.color = ACCENT
                c.drawRoundRect(box, r, r, paint)
                c.restore()
            }
        }
        return bmp
    }

    private fun levelBitmap(ctx: Context): Bitmap {
        val px = ctx.resources.displayMetrics.density
        val w = (120 * px).toInt()
        val h = (4 * px).toInt()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = line
        c.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), h / 2f, h / 2f, paint)
        paint.color = ink
        c.drawRoundRect(RectF(0f, 0f, maxOf(h.toFloat(), w * levelProgress), h.toFloat()), h / 2f, h / 2f, paint)
        return bmp
    }

    companion object {
        const val ACCENT = 0xFFFF5A36.toInt()

        fun read(ctx: Context): WidgetState {
            val store = Store(ctx)
            val today = LocalDate.now()
            val d = store.load(today)
            val goal = d?.goal ?: store.goalFor(today)
            // Mismo total que Snapshot.totalPoints: días más IRL.
            val total = store.scoredDays().sumOf { it.total } + store.rondaLedger().sumOf { it.amount }
            val frozen = store.frozen
            val monday = today.with(DayOfWeek.MONDAY)
            val week = (0..6).map { monday.plusDays(it.toLong()) }.map { date ->
                when {
                    date == today -> if (d?.met == true) 1f else ((d?.toGoal ?: 0).toFloat() / goal).coerceIn(0f, 0.99f)
                    date.isAfter(today) -> 0f
                    store.load(date)?.met == true -> 1f
                    date in frozen -> -1f
                    else -> 0f
                }
            }
            // Mismo criterio que la app: lo elegido con el logo manda; si no, el modo del sistema.
            val night = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            return WidgetState(
                dark = store.darkMode ?: night,
                total = total, level = level(total), levelProgress = levelProgress(total),
                today = d?.toGoal ?: 0, goal = goal, met = d?.met == true,
                streak = streakBefore(store, today) + if (d?.met == true) 1 else 0,
                week = week,
            )
        }

        private fun colored(text: String, from: Int, to: Int, color: Int, bold: Boolean = false) = SpannableString(text).apply {
            setSpan(ForegroundColorSpan(color), from, to, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (bold) setSpan(StyleSpan(android.graphics.Typeface.BOLD), from, to, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }
}
