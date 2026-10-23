package app.getoff

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.WeekFields

// «Compartir mi semana»: cuatro diseños para que no se comparta siempre lo mismo. Se dibujan a 1080×1920,
// igual que en el lienzo de diseño (Off · tarjeta semanal), así las medidas se copian tal cual.

data class WeekDay(val date: LocalDate, val lockedMin: Int, val met: Boolean, val today: Boolean, val future: Boolean)

/** Lo de la semana que muestran las tarjetas, de lunes a hoy. */
data class WeekStats(
    val monday: LocalDate,
    val days: List<WeekDay>, // lunes a domingo
    val lockedMin: Int,
    val points: Int,
    val met: Int,
    val counted: Int, // días cerrados, más hoy si ya cumplió
    val streak: Int,
    val steps: Int,
    val exerciseMin: Int,
    val recoveredMin: Int,
) {
    val weekNumber get() = monday.get(WeekFields.ISO.weekOfWeekBasedYear())
}

fun weekStats(s: Snapshot): WeekStats {
    val today = s.today.date
    val monday = weekOf(today)
    val byDate = s.days.associateBy { it.date }
    val days = (0L..6L).map(monday::plusDays).map { d ->
        val day = byDate[d]
        WeekDay(d, (day?.blocks ?: 0) * 30, day?.met == true, d == today, d.isAfter(today))
    }
    val week = s.days.filter { !it.date.isBefore(monday) }
    val irl = s.rondaLedger.filter { !it.date.isBefore(monday) }.sumOf { it.amount }
    return WeekStats(
        monday, days, days.sumOf { it.lockedMin }, week.sumOf { it.total } + irl,
        week.count { it.met }, week.count { it.closed || it.met }, s.streak,
        week.sumOf { it.steps }, week.sumOf { it.exerciseMin }, recoveredMinutes(s.days.map { it.socialMin }),
    )
}

enum class CardStyle(private val es: String, private val en: String) {
    NUMBER("Número", "Number"), TICKET("Ticket", "Receipt"), OFF("Apagado", "Lights off"), POSTER("Póster", "Poster");

    val label get() = t(es, en)
}

/** `scale` < 1 para las vistas previas del selector. */
fun renderWeek(ctx: Context, style: CardStyle, w: WeekStats, scale: Float = 1f): Bitmap {
    val bmp = Bitmap.createBitmap((W * scale).toInt(), (H * scale).toInt(), Bitmap.Config.ARGB_8888)
    val cv = Canvas(bmp)
    cv.scale(scale, scale)
    val ink = Pens(ctx)
    when (style) {
        CardStyle.NUMBER -> number(cv, ink, w)
        CardStyle.TICKET -> ticket(cv, ink, w)
        CardStyle.OFF -> lightsOff(cv, ink, w)
        CardStyle.POSTER -> poster(cv, ink, w)
    }
    return bmp
}

// ---------- Textos ----------

private fun hm(min: Int) = "${min / 60}:${"%02d".format(min % 60)}"
private val WeekStats.hours get() = lockedMin / 60

/** Fechas de la semana: «19–25 OCT» o «26 OCT – 1 NOV». */
private fun range(w: WeekStats): String {
    val end = w.monday.plusDays(6)
    fun mon(d: LocalDate) = d.month.getDisplayName(TextStyle.SHORT, loc).removeSuffix(".").uppercase()
    return if (w.monday.month == end.month) "${w.monday.dayOfMonth}–${end.dayOfMonth} ${mon(end)}"
    else "${w.monday.dayOfMonth} ${mon(w.monday)} – ${end.dayOfMonth} ${mon(end)}"
}

/** Los cuatro datos chicos: puntos y racha siempre; ejercicio y pasos si hubo, si no las metas. */
private fun highlights(w: WeekStats) = buildList {
    add(thousands(w.points) to t("puntos", "points"))
    add("${w.streak}" to t("días de racha", "day streak"))
    if (w.exerciseMin > 0) add(duration(w.exerciseMin) to t("de ejercicio", "of exercise"))
    if (w.steps > 0) add(thousands(w.steps) to t("pasos", "steps"))
    if (size < 4) add("${w.met}/${w.counted}" to t("metas", "goals"))
}.take(4)

private fun letter(d: WeekDay) = t("LMMJVSD", "MTWTFSS")[d.date.dayOfWeek.value - 1].toString()

// ---------- Dibujo ----------

private const val PALE = 0xFFFFC9BB.toInt()
private const val TABLE = 0xFFE9E8E2.toInt()
private const val NIGHT = 0xFF0E0E0F.toInt()
private const val NIGHT_INK = 0xFFF2F2EF.toInt()
private const val NIGHT_MUTED = 0xFF9A9A96.toInt()
private const val NIGHT_BODY = 0xFFC9C9C4.toInt()
private const val NIGHT_FAINT = 0xFF74746F.toInt()
private const val NIGHT_LINE = 0xFF2A2A2D.toInt()
private const val WHITE = 0xFFFFFFFF.toInt()

private class Pens(ctx: Context) {
    private val geist: Typeface? = ResourcesCompat.getFont(ctx, R.font.geist)
    private val geistMono: Typeface? = ResourcesCompat.getFont(ctx, R.font.geist_mono)

    fun sans(size: Float, color: Int, weight: Int = 400, spacing: Float = 0f, align: Paint.Align = Paint.Align.LEFT) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = geist; textSize = size; this.color = color; textAlign = align
            fontVariationSettings = "'wght' $weight"; letterSpacing = spacing
        }

    fun mono(size: Float, color: Int, spacing: Float = 0f, align: Paint.Align = Paint.Align.LEFT) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = geistMono; textSize = size; this.color = color; textAlign = align; letterSpacing = spacing }

    fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
}

/** Achica la letra hasta que el texto entre en `max` px. */
private fun Paint.fit(text: String, max: Float) = apply { while (measureText(text) > max && textSize > 24f) textSize -= 4f }

/** Corta en líneas de hasta `max` px, por palabras. */
private fun Paint.wrap(text: String, max: Float): List<String> = text.split(" ").fold(listOf("")) { lines, word ->
    val next = (lines.last() + " " + word).trim()
    if (measureText(next) <= max || lines.last().isEmpty()) lines.dropLast(1) + next else lines + word
}

/** «off.» con el punto en otro color. `x` es el borde izquierdo, o el centro con `center`. */
private fun Canvas.wordmark(ink: Pens, x: Float, y: Float, size: Float, color: Int, dot: Int, center: Boolean = false) {
    val p = ink.sans(size, color, 700, -0.04f)
    val left = if (center) x - (p.measureText("off") + p.measureText(".")) / 2 else x
    drawText("off", left, y, p)
    drawText(".", left + p.measureText("off"), y, ink.sans(size, dot, 700, -0.04f))
}

/** A · Número: el total gigante y una barra por día, alta según las horas. */
private fun number(cv: Canvas, ink: Pens, w: WeekStats) {
    val m = 96f
    cv.drawColor(GROUND)
    cv.wordmark(ink, m, 190f, 84f, INK, ORANGE)
    cv.drawText(range(w), W - m, 186f, ink.mono(32f, MUTED, 0.02f, Paint.Align.RIGHT))
    cv.drawText(t("Mi semana off", "My week off"), m, 520f, ink.sans(52f, MUTED, 500))
    val (big, unit) = if (w.lockedMin < 60) "${w.lockedMin}" to "min" else "${w.hours}" to "h"
    val bigP = ink.sans(560f, INK, 700, -0.075f).fit(big, 700f)
    cv.drawText(big, m - 12f, 960f, bigP)
    cv.drawText(unit, m + bigP.measureText(big) - 16f, 960f, ink.sans(180f, ORANGE, 600, -0.04f))
    cv.drawText(t("con el celu boca abajo", "with my phone face down"), m, 1066f, ink.sans(64f, INK, 500, -0.02f))

    // Barras de lunes a domingo.
    val gap = 20f
    val col = (W - 2 * m - 6 * gap) / 7
    val floor = 1470f
    val tall = 340f
    val most = maxOf(w.days.maxOf { it.lockedMin }, 1)
    w.days.forEachIndexed { i, d ->
        val x = m + i * (col + gap)
        val h = if (d.lockedMin > 0) d.lockedMin.toFloat() / most * tall else 14f
        val c = when { d.met -> ORANGE; d.today -> PALE; else -> HAIR }
        cv.drawRoundRect(RectF(x, floor - h, x + col, floor), 18f, 18f, ink.fill(c))
        cv.drawText(letter(d), x + col / 2, 1520f, ink.mono(30f, if (d.today) ORANGE else MUTED, align = Paint.Align.CENTER))
    }

    cv.drawRect(m, 1578f, W - m, 1581f, ink.fill(HAIR))
    val stats = highlights(w)
    val cell = (W - 2 * m) / stats.size
    stats.forEachIndexed { i, (v, label) ->
        val x = m + i * cell
        cv.drawText(v, x, 1664f, ink.sans(64f, INK, 700, -0.03f).fit(v, cell - 24f))
        cv.drawText(label, x, 1708f, ink.sans(30f, MUTED).fit(label, cell - 24f))
    }
    cv.drawText(t("Lo que pasa off, cuenta.", "What happens off, counts."), m, 1816f, ink.sans(52f, INK, 600, -0.02f))
    cv.drawText(LANDING, W - m, 1816f, ink.mono(30f, MUTED, align = Paint.Align.RIGHT))
}

/** B · Ticket: un recibo de la semana, renglón por renglón. */
private fun ticket(cv: Canvas, ink: Pens, w: WeekStats) {
    cv.drawColor(TABLE)
    val left = 110f
    val right = W - 110f
    val pad = 64f
    val x0 = left + pad
    val x1 = right - pad
    val cx = W / 2f
    val rows = buildList {
        add(t("PUNTOS", "POINTS") to thousands(w.points))
        add(t("RACHA", "STREAK") to t("${w.streak} DÍAS", "${w.streak} DAYS"))
        if (w.exerciseMin > 0) add(t("EJERCICIO", "EXERCISE") to duration(w.exerciseMin).uppercase())
        if (w.steps > 0) add(t("PASOS SIN MIRARLO", "STEPS, PHONE LOCKED") to thousands(w.steps))
        if (w.recoveredMin > 0) add(t("REDES RECUPERADAS", "SOCIAL MEDIA SAVED") to duration(w.recoveredMin).uppercase())
    }
    // Alto del papel: la suma de los saltos de abajo (1254), el margen de abajo y un renglón por dato.
    val paperH = 1330f + rows.size * 58f
    val top = (H - paperH) / 2
    val bottom = top + paperH

    cv.save()
    cv.rotate(-1.5f, cx, H / 2f)
    cv.drawRect(left, top, right, bottom, ink.fill(WHITE).apply { setShadowLayer(40f, 0f, 30f, 0x1F111111) })
    // Bordes picados: mordidas del color de la mesa arriba y abajo.
    var bx = left + 20f
    while (bx < right) {
        cv.drawCircle(bx, top, 20f, ink.fill(TABLE))
        cv.drawCircle(bx, bottom, 20f, ink.fill(TABLE))
        bx += 40f
    }
    val dash = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = INK; strokeWidth = 4f; pathEffect = DashPathEffect(floatArrayOf(14f, 10f), 0f) }
    fun rule(y: Float) = cv.drawLine(x0, y, x1, y, dash)
    fun line(y: Float, k: String, v: String, color: Int = INK) {
        cv.drawText(k, x0, y, ink.mono(34f, color))
        cv.drawText(v, x1, y, ink.mono(34f, color, align = Paint.Align.RIGHT))
    }

    var y = top + 150f
    cv.wordmark(ink, cx, y, 120f, INK, ORANGE, center = true)
    y += 66f
    cv.drawText(t("RESUMEN SEMANAL", "WEEKLY RECEIPT"), cx, y, ink.mono(30f, INK, 0.12f, Paint.Align.CENTER))
    y += 46f
    val f = DateTimeFormatter.ofPattern("dd.MM.yyyy")
    cv.drawText("${w.monday.format(f)} – ${w.monday.plusDays(6).format(f)}", cx, y, ink.mono(28f, MUTED, align = Paint.Align.CENTER))
    y += 44f
    rule(y)
    y += 64f
    w.days.forEach { d ->
        val name = d.date.dayOfWeek.getDisplayName(TextStyle.SHORT, loc).removeSuffix(".").uppercase()
        val tag = when { d.met -> t("META", "GOAL"); d.today -> t("EN CURSO", "TODAY"); else -> "" }
        line(y, if (tag.isEmpty()) name else "$name  ·  $tag", if (d.future) "—" else hm(d.lockedMin), if (d.met) INK else MUTED)
        y += 58f
    }
    y -= 14f
    rule(y)
    y += 120f
    cv.drawText("TOTAL OFF", x0, y - 8f, ink.sans(40f, INK, 600))
    cv.drawText("${w.hours} h", x1, y, ink.sans(112f, INK, 700, -0.04f, Paint.Align.RIGHT))
    y += 70f
    rows.forEach { (k, v) -> line(y, k, v); y += 58f }
    y -= 14f
    rule(y)
    y += 70f
    cv.drawText(t("GRACIAS POR NO SCROLLEAR", "THANKS FOR NOT SCROLLING"), cx, y, ink.mono(34f, INK, 0.06f, Paint.Align.CENTER))
    y += 46f
    cv.drawText(t("la cuenta se salda en persona", "settled up in person"), cx, y, ink.mono(28f, MUTED, align = Paint.Align.CENTER))
    // Código de barras decorativo: los minutos de cada día marcan el grosor de las rayas.
    y += 40f
    val bars = w.days.flatMap { d -> listOf(3f, 3f + d.lockedMin / 60 % 4 * 3f, 3f, 6f, 9f - d.lockedMin / 30 % 3 * 3f) }
    val barsW = bars.sumOf { it.toDouble() }.toFloat() + bars.size * 6f
    var x = cx - barsW / 2
    bars.forEach { b -> cv.drawRect(x, y, x + b, y + 110f, ink.fill(INK)); x += b + 6f }
    y += 160f
    cv.drawText(LANDING.uppercase(), cx, y, ink.mono(26f, MUTED, 0.2f, Paint.Align.CENTER))
    cv.restore()
}

/** C · Apagado: la pantalla negra con el punto que queda al apagarse. */
private fun lightsOff(cv: Canvas, ink: Pens, w: WeekStats) {
    val m = 96f
    val cx = W / 2f
    cv.drawColor(NIGHT)
    val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(cx, 480f, 250f, intArrayOf(0x6BFF5A36, 0x1FFF5A36, 0x00FF5A36), floatArrayOf(0f, 0.38f, 1f), Shader.TileMode.CLAMP)
    }
    cv.drawCircle(cx, 480f, 250f, glow)
    cv.drawCircle(cx, 480f, 30f, ink.fill(ORANGE))

    cv.drawText(t("ESTA SEMANA, PANTALLA APAGADA", "THIS WEEK, SCREEN OFF"), cx, 690f, ink.mono(32f, NIGHT_MUTED, 0.08f, Paint.Align.CENTER))
    val big = if (w.lockedMin < 60) "${w.lockedMin} min" else "${w.hours} h"
    cv.drawText(big, cx, 1030f, ink.sans(400f, NIGHT_INK, 700, -0.07f, Paint.Align.CENTER).fit(big, W - 2 * m))
    val phrase = if (w.lockedMin >= 24 * 60) t("Le gané más de un día entero al celu.", "I won back more than a whole day from my phone.")
    else t("Le gané ${w.hours} horas al celu.", "I won back ${w.hours} hours from my phone.")
    val pp = ink.sans(56f, NIGHT_BODY, align = Paint.Align.CENTER)
    var y = 1150f
    pp.wrap(phrase, 780f).forEach { cv.drawText(it, cx, y, pp); y += 70f }
    val stats = highlights(w).take(3).joinToString("  ·  ") { (v, l) -> "$v $l" }
    cv.drawText(stats, cx, y + 40f, ink.sans(34f, NIGHT_MUTED, align = Paint.Align.CENTER).fit(stats, W - 2 * m))

    // Un punto por día: lleno si cumplió, con borde naranja hoy.
    val r = 22f
    val gap = 36f
    val start = cx - (7 * 2 * r + 6 * gap) / 2 + r
    w.days.forEachIndexed { i, d ->
        val x = start + i * (2 * r + gap)
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4f; color = if (d.today) ORANGE else NIGHT_LINE }
        if (d.met) cv.drawCircle(x, 1480f, r, ink.fill(ORANGE)) else cv.drawCircle(x, 1480f, r - 2f, ring)
        cv.drawText(letter(d), x, 1548f, ink.mono(28f, NIGHT_FAINT, align = Paint.Align.CENTER))
    }

    cv.wordmark(ink, m, 1800f, 72f, NIGHT_INK, ORANGE)
    cv.drawText(t("La vida pasa off.", "Life happens off."), W - m, 1756f, ink.sans(44f, NIGHT_INK, 600, align = Paint.Align.RIGHT))
    cv.drawText(LANDING, W - m, 1806f, ink.mono(28f, NIGHT_MUTED, align = Paint.Align.RIGHT))
}

/** D · Póster: todo naranja y la frase en grande. */
private fun poster(cv: Canvas, ink: Pens, w: WeekStats) {
    val m = 88f
    cv.drawColor(ORANGE)
    cv.wordmark(ink, m, 190f, 84f, INK, GROUND)
    cv.drawText(t("SEMANA ${w.weekNumber}", "WEEK ${w.weekNumber}"), W - m, 186f, ink.mono(32f, INK, 0.04f, Paint.Align.RIGHT))
    val head = ink.sans(150f, INK, 800, -0.06f)
    cv.drawText(t("Esta semana", "This week"), m, 560f, head)
    cv.drawText(t("le gané", "I won back"), m, 698f, ink.sans(150f, INK, 800, -0.06f).fit(t("le gané", "I won back"), W - 2 * m))
    val big = if (w.lockedMin < 60) t("${w.lockedMin} minutos", "${w.lockedMin} minutes") else t("${w.hours} horas", "${w.hours} hours")
    cv.drawText(big, m - 8f, 990f, ink.sans(330f, GROUND, 900, -0.075f).fit(big, W - 2 * m))
    val tail = t("al celu.", "from my phone.")
    cv.drawText(tail, m, 1130f, ink.sans(150f, INK, 800, -0.06f).fit(tail, W - 2 * m))

    cv.drawRect(m, 1420f, W - m, 1424f, ink.fill(INK))
    val col = (W - 2 * m) / 2
    highlights(w).forEachIndexed { i, (v, label) ->
        val x = m + (i % 2) * col
        val y = 1512f + (i / 2) * 142f
        cv.drawText(v, x, y, ink.sans(80f, INK, 800, -0.04f).fit(v, col - 24f))
        cv.drawText(label, x, y + 46f, ink.sans(34f, INK))
    }
    cv.drawText(t("Lo que pasa off, cuenta.", "What happens off, counts."), m, 1830f, ink.sans(48f, INK, 700, -0.02f))
    cv.drawText(LANDING, W - m, 1830f, ink.mono(30f, INK, align = Paint.Align.RIGHT))
}
