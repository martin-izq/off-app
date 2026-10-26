package app.getoff

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.core.content.FileProvider
import androidx.core.content.res.ResourcesCompat
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

// Tarjetas para compartir en historias (9:16): un pozo saldado y el año. La semana tiene sus propias, en WeekCards.kt.

/** La landing (GitHub Pages): lo que se ve en las tarjetas. Sin Play Store, de ahí se baja el APK. */
const val LANDING = "martin-izq.github.io/off-app"
/** El link que se comparte: abre la landing en el botón de descarga. */
const val DOWNLOAD_URL = "https://$LANDING/#download"

/** Lo que dice una tarjeta: de qué es, el número grande con su unidad y hasta cinco filas. */
data class ShareCard(val eyebrow: String, val big: String, val unit: String, val rows: List<Pair<String, String>>)

private fun Long.date() = Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).toLocalDate()

/** Horas con el celu bloqueado (los bloques de 30 min), para el número grande. */
private fun offTime(days: List<Day>): String {
    val min = days.sumOf { it.blocks } * 30
    return if (min < 60) "$min min" else "${min / 60} h"
}

fun potCard(f: Final, group: String, me: String): ShareCard {
    val all = outcome(f).sortedByDescending { it.delta }
    val together = f.from > 0
    val pot = f.members.sumOf { it.debt } + all.sumOf { it.fine }
    val rows = all.take(4).map { (if (it.id == me) t("Vos", "You") else it.name) to signed(it.delta) } +
        listOfNotNull(all.filter { it.fine > 0 }.maxByOrNull { it.fine }?.let {
            t("Más desbloqueos", "Most unlocks") to (if (it.id == me) t("vos", "you") else it.name)
        })
    return ShareCard(
        t("$group · cuenta saldada", "$group · settled up"),
        if (together) duration(((f.to - f.from) / 60_000).toInt()) else "$pot",
        if (together) t("juntos, celus boca abajo", "together, phones face down") else t("puntos en el pozo", "points in the pot"),
        rows.take(5),
    )
}

/** El año de «Tu año off»: se ofrece en diciembre y hasta el 15 de enero. Null el resto del año. */
fun wrappedYear(today: LocalDate): Int? = when {
    today.monthValue == 12 -> today.year
    today.monthValue == 1 && today.dayOfMonth <= 15 -> today.year - 1
    else -> null
}

fun yearCard(s: Snapshot, year: Int): ShareCard {
    val days = s.days.filter { it.date.year == year }
    val irl = s.rondaLedger.filter { it.date.year == year }.sumOf { it.amount }
    val meetings = s.groups.sumOf { g -> g.meetings.count { it.to.date().year == year } }
    val steps = days.sumOf { it.steps }
    val rows = buildList {
        add(t("Puntos", "Points") to thousands(days.sumOf { it.total } + irl))
        add(t("Días con la meta", "Days on goal") to "${days.count { it.met }}")
        add(t("Mejor racha", "Best streak") to "${bestStreak(days.map(s::kept))}")
        if (steps > 0) add(t("Pasos con el celu bloqueado", "Steps with my phone locked") to thousands(steps))
        if (meetings > 0) add(t("Encuentros IRL", "IRL meetups") to "$meetings")
    }
    return ShareCard(t("Mi $year off", "My $year off"), offTime(days), t("con el celu bloqueado", "with my phone locked"), rows)
}

internal const val W = 1080
internal const val H = 1920
internal const val INK = 0xFF111111.toInt()
internal const val MUTED = 0xFF6B6B66.toInt()
internal const val HAIR = 0xFFE4E4DF.toInt()
internal const val GROUND = 0xFFF4F4F1.toInt()
internal const val ORANGE = 0xFFFF5A36.toInt()

fun renderCard(ctx: Context, c: ShareCard): Bitmap {
    val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
    val cv = Canvas(bmp)
    cv.drawColor(GROUND)
    val geist = ResourcesCompat.getFont(ctx, R.font.geist)
    fun paint(size: Float, color: Int, weight: Int = 400, align: Paint.Align = Paint.Align.LEFT) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = geist; textSize = size; this.color = color; textAlign = align; fontVariationSettings = "'wght' $weight"
    }
    /** Recorta con «…» lo que no entra en `max` px. */
    fun fit(text: String, p: Paint, max: Float): String {
        if (p.measureText(text) <= max) return text
        var s = text
        while (s.isNotEmpty() && p.measureText("$s…") > max) s = s.dropLast(1)
        return "$s…"
    }
    val m = 96f
    val wide = W - 2 * m

    // off.
    val logo = paint(84f, INK, 700).apply { letterSpacing = -0.04f }
    cv.drawText("off", m, 210f, logo)
    cv.drawText(".", m + logo.measureText("off"), 210f, paint(84f, ORANGE, 700))

    cv.drawText(fit(c.eyebrow, paint(48f, MUTED, 500), wide), m, 580f, paint(48f, MUTED, 500))
    // El número grande se achica hasta entrar en el ancho.
    val big = paint(340f, INK, 600).apply { letterSpacing = -0.05f }
    while (big.measureText(c.big) > wide && big.textSize > 120f) big.textSize -= 10f
    val bigY = 640f + big.textSize * 0.78f
    cv.drawText(c.big, m, bigY, big)
    cv.drawText(fit(c.unit, paint(56f, MUTED, 500), wide), m, bigY + 96f, paint(56f, MUTED, 500))

    var y = bigY + 200f
    val label = paint(46f, INK)
    val value = paint(46f, INK, 600, Paint.Align.RIGHT)
    val hair = Paint().apply { color = HAIR; strokeWidth = 3f }
    c.rows.take(5).forEach { (k, v) ->
        cv.drawLine(m, y, W - m, y, hair)
        val vw = value.measureText(v)
        cv.drawText(fit(k, label, wide - vw - 40f), m, y + 74f, label)
        cv.drawText(v, W - m, y + 74f, value)
        y += 116f
    }
    if (c.rows.isNotEmpty()) cv.drawLine(m, y, W - m, y, hair)

    cv.drawText(t("Lo que pasa off, cuenta.", "What happens off, counts."), m, H - 210f, paint(58f, INK, 600))
    cv.drawText(LANDING, m, H - 130f, paint(42f, MUTED))
    return bmp
}

/** Arma la imagen y abre el menú de compartir. */
fun shareCard(ctx: Context, c: ShareCard) = shareBitmap(ctx, renderCard(ctx, c))

fun shareBitmap(ctx: Context, bmp: Bitmap) {
    val file = File(File(ctx.cacheDir, "share").apply { mkdirs() }, "off.png")
    file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.share", file)
    val send = Intent(Intent.ACTION_SEND).setType("image/png")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_TEXT, t("Lo que pasa off, cuenta. ", "What happens off, counts. ") + DOWNLOAD_URL)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    send.clipData = ClipData.newRawUri(null, uri) // la vista previa del menú de compartir
    ctx.startActivity(Intent.createChooser(send, null))
}
