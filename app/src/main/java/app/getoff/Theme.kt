package app.getoff

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.asImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.journeyapps.barcodescanner.BarcodeEncoder
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

// Paleta «off.» (canvas «Desenchufe — UI/UX», 7 · Nueva identidad › A · Pausa). Un solo acento, naranja en los dos modos.
@Immutable
internal data class Palette(
    val bg: Color, val card: Color, val track: Color, val dim: Color,
    val ink: Color, val muted: Color, val body: Color, val faint: Color, val line: Color,
    val mossLight: Color, val shield: Color, val irl: Color, val errBg: Color, val errFg: Color,
)

internal val LightPalette = Palette(
    bg = Color(0xFFF4F4F1), card = Color(0xFFFFFFFF), track = Color(0xFFE4E4DF), dim = Color(0xFFCFCFC8),
    ink = Color(0xFF111111), muted = Color(0xFF6B6B66), body = Color(0xFF3A3A36), faint = Color(0xFF8A8A84), line = Color(0xFFE4E4DF),
    mossLight = Color(0xFFFFA690), shield = Color(0xFFFFC9BB), irl = Color(0xFFC2410C), errBg = Color(0xFFFFEDE6), errFg = Color(0xFFB23A12),
)

internal val DarkPalette = Palette(
    bg = Color(0xFF0E0E0F), card = Color(0xFF1A1A1C), track = Color(0xFF2A2A2D), dim = Color(0xFF3A3A3D),
    ink = Color(0xFFF2F2EF), muted = Color(0xFF9A9A96), body = Color(0xFFC9C9C4), faint = Color(0xFF74746F), line = Color(0xFF2A2A2D),
    mossLight = Color(0xFFFF9C85), shield = Color(0xFF4A2219), irl = Color(0xFFFF8A5C), errBg = Color(0xFF2A1A16), errFg = Color(0xFFFF8A70),
)

internal val LocalPalette = staticCompositionLocalOf { LightPalette }

/** Cambia de modo desde el punto del logo (posición en la raíz). Null: sin interruptor. */
internal val LocalToggleDark = compositionLocalOf<((Offset) -> Unit)?> { null }

// Los nombres de siempre, leídos de la paleta: solo dentro de la composición (en un Canvas, capturarlos antes).
internal val Bg: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.bg
internal val Card: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.card
internal val SheetBg: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.card
internal val Track: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.track
internal val Track2: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.track
internal val Dim: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.dim
internal val Sand: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.ink // tinta: texto principal
internal val Muted: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.muted
internal val Body: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.body
internal val Faint: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.faint // lo que todavía no se ganó
internal val Line: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.line // separador entre filas
internal val ErrBg: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.errBg
internal val ErrFg: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.errFg
// Un color por fuente de puntos: solo puntitos y gráficos. Las fijas son iguales en los dos modos.
internal val Accent = Color(0xFFFF5A36)
internal val Moss = Accent // bloques
internal val MossLight: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.mossLight // descanso largo
internal val Gold = Color(0xFFC08A1E) // redes
internal val Ember = Accent // meta y racha
internal val Rose = Color(0xFFD9719A) // desbloqueos
internal val Sky = Color(0xFF4F9AD6) // pasos
internal val Lilac = Color(0xFF8E7BD6) // ejercicio
internal val Irl: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.irl // IRL
internal val Mint: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.irl // sesiones: mismo tono que IRL
internal val Shield: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.shield // comodín

/** Lo que suma, en el orden en que se apila. Cada fuente con su color en toda la app. */
@Composable
@ReadOnlyComposable
internal fun Day.sources() = listOf(
    blocks to Moss, restBonus to MossLight, sessionBonus to Mint, unlockBonus to Rose, stepBonus to Sky, exerciseBonus to Lilac,
)

/** Español si el sistema está en español; si no, inglés. Se lee cada vez: cambiar el idioma recrea la app. */
internal val isEs get() = Locale.getDefault().language == "es"
// ponytail: textos en pares español/inglés al lado de donde se usan; pasar a strings.xml si llega un tercer idioma.
internal fun t(es: String, en: String) = if (isEs) es else en
/** Formato de fechas y números del idioma de los textos. */
internal val loc: Locale get() = if (isEs) Locale.forLanguageTag("es-AR") else Locale.US

/** Geist, variable: un archivo para todos los pesos. */
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
internal val Geist = androidx.compose.ui.text.font.FontFamily(
    listOf(400, 500, 600, 700).map { w ->
        androidx.compose.ui.text.font.Font(
            R.font.geist, FontWeight(w),
            variationSettings = androidx.compose.ui.text.font.FontVariation.Settings(androidx.compose.ui.text.font.FontVariation.weight(w)),
        )
    },
)
internal fun LocalDate.label(pattern: String) =
    format(DateTimeFormatter.ofPattern(pattern, loc)).replace(".", "").replaceFirstChar { it.uppercase() }

internal fun clock(millis: Long): String = java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.systemDefault())
    .toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm"))

/** Fecha o título a la izquierda, datos chicos a la derecha. */
@Composable
internal fun Header(title: String, onClick: (() -> Unit)? = null, right: @Composable RowScope.() -> Unit) =
    Row(Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = Muted, fontSize = 14.sp, modifier = Modifier.weight(1f)
            .then(if (onClick != null) Modifier.heightIn(min = 44.dp).clickable(role = Role.Button, onClick = onClick).wrapContentHeight(Alignment.CenterVertically) else Modifier))
        right()
    }

@Composable
/**
 * Una fila: punto de color, nombre, detalle y valor. `faint`: todavía no se ganó (punto hueco, apagado).
 * Sin color ni `faint`: fila neutra, sin punto.
 */
internal fun Source(
    color: Color?, label: String, hint: String, value: String,
    last: Boolean = false, faint: Boolean = false, chevron: Boolean = false, onClick: (() -> Unit)? = null,
) {
    val fg = if (faint) Faint else Sand
    Column {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 44.dp)
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (color != null) Box(Modifier.size(6.dp).clip(CircleShape).background(color))
            else if (faint) Box(Modifier.size(6.dp).clip(CircleShape).border(1.dp, Faint, CircleShape))
            Text(label, color = fg, fontSize = 15.sp, modifier = Modifier.weight(1f))
            if (hint.isNotEmpty()) Text(hint, color = if (faint) Faint else Muted, fontSize = 13.sp)
            if (value.isNotEmpty()) Text(value, color = fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.End, modifier = Modifier.widthIn(min = 36.dp))
            if (chevron) Text("›", color = Faint, fontSize = 16.sp)
        }
        if (!last) Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
    }
}

/** «off.»: el nombre con el punto en el color de acento. */
@Composable
internal fun Wordmark(modifier: Modifier = Modifier) {
    // Easter egg: tocarlo apaga o prende la luz. Sin ripple, para que no se note.
    val toggle = LocalToggleDark.current
    val dark = LocalPalette.current == DarkPalette
    var dot by remember { mutableStateOf(Offset.Zero) }
    Row(modifier.then(if (toggle == null) Modifier else Modifier.clickable(
        interactionSource = null, indication = null, role = Role.Button,
        onClickLabel = if (dark) t("Prender la luz", "Lights on") else t("Apagar la luz", "Lights off"),
    ) { toggle(dot) })) {
        Text("off", color = Sand, fontSize = 17.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp)
        Text(".", color = Accent, fontSize = 17.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.onGloballyPositioned { dot = it.boundsInRoot().center })
    }
}

enum class LineIcons { MENU, TODAY, RONDA, HISTORY }

/** Íconos de línea propios, del mismo trazo que la tipografía: dos rayas, un círculo, dos círculos, barras. */
@Composable
internal fun LineIcon(icon: LineIcons, color: Color, label: String) =
    Canvas(Modifier.size(24.dp).semantics { contentDescription = label }) {
        val w = 1.8.dp.toPx()
        val u = size.width / 24
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
            drawLine(color, androidx.compose.ui.geometry.Offset(x1 * u, y1 * u), androidx.compose.ui.geometry.Offset(x2 * u, y2 * u), w, StrokeCap.Round)
        fun ring(x: Float, y: Float, r: Float) =
            drawCircle(color, r * u, androidx.compose.ui.geometry.Offset(x * u, y * u), style = Stroke(w))
        when (icon) {
            LineIcons.MENU -> { line(4f, 9f, 20f, 9f); line(4f, 15f, 14f, 15f) }
            LineIcons.TODAY -> { ring(12f, 12f, 8f); drawCircle(color, 2.2f * u, center) }
            LineIcons.RONDA -> { ring(9f, 12f, 5.5f); ring(15f, 12f, 5.5f) }
            LineIcons.HISTORY -> { line(6f, 19f, 6f, 13f); line(12f, 19f, 12f, 6f); line(18f, 19f, 18f, 10f) }
        }
    }

@Composable
internal fun TextLink(text: String, onClick: () -> Unit) = Text(
    text, color = Muted, fontSize = 14.sp,
    modifier = Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button, onClick = onClick)
        .padding(horizontal = 6.dp, vertical = 12.dp),
)

internal fun signed(n: Int) = if (n > 0) "+$n" else "$n"

internal fun ago(millis: Long): String {
    val days = java.time.temporal.ChronoUnit.DAYS.between(
        java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.systemDefault()).toLocalDate(), LocalDate.now())
    return when (days) {
        0L -> t("hoy", "today")
        1L -> t("ayer", "yesterday")
        else -> t("hace $days días", "$days days ago")
    }
}

@Composable
internal fun Qr(text: String) {
    val bmp = remember(text) {
        BarcodeEncoder().encodeBitmap(text, BarcodeFormat.QR_CODE, 720, 720, mapOf(EncodeHintType.MARGIN to 0))
    }
    Box(Modifier.size(264.dp).clip(RoundedCornerShape(20.dp)).background(Color.White).padding(16.dp)) {
        Image(bmp.asImageBitmap(), contentDescription = t("Código QR", "QR code"), modifier = Modifier.fillMaxSize())
    }
}

@Composable
internal fun TextAction(text: String, onClick: () -> Unit) = Box(
    Modifier.fillMaxWidth().height(48.dp).clickable(role = Role.Button, onClick = onClick),
    contentAlignment = Alignment.Center,
) { Text(text, color = Muted, fontSize = 15.sp) }

// ---------- Piezas ----------

internal fun thousands(n: Int) = String.format(loc, "%,d", n)
internal fun times(m: Double) = "×" + String.format(loc, "%.1f", m).removeSuffix(",0").removeSuffix(".0")
internal fun hours(goal: Int) = duration(goal * 30)
internal fun duration(min: Int) = when {
    min < 60 -> "$min min"
    min % 60 == 0 -> "${min / 60} h"
    else -> "${min / 60} h ${min % 60} min"
}
internal fun breakdown(d: Day) = buildList {
    add(t("${d.blocks} bloques", "${d.blocks} blocks"))
    if (d.restBonus > 0) add(t("${d.restBonus} por descanso", "${d.restBonus} from rest"))
    if (d.unlockBonus > 0) add(t("${d.unlockBonus} por desbloqueos", "${d.unlockBonus} from unlocks"))
    if (d.stepBonus > 0) add(t("${d.stepBonus} por pasos", "${d.stepBonus} from steps"))
    if (d.exerciseBonus > 0) add(t("${d.exerciseBonus} por ejercicio", "${d.exerciseBonus} from exercise"))
    if (d.sessionBonus > 0) add(t("${d.sessionBonus} por sesiones", "${d.sessionBonus} from sessions"))
    if (d.socialPenalty > 0) add(t("−${d.socialPenalty} por redes", "−${d.socialPenalty} from social media"))
    if (d.goalBonus > 0) add(t("${d.goalBonus} por meta", "${d.goalBonus} from the goal"))
}.joinToString(", ")

@Composable
internal fun Title(text: String, size: Int) =
    Text(text, color = Sand, fontSize = size.sp, lineHeight = (size * 1.05).sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(bottom = 16.dp))

@Composable
internal fun Para(text: String) = Text(text, color = Body, fontSize = 17.sp, lineHeight = 25.sp)

@Composable
internal fun Rule(accent: Color, title: String, body: String) {
    CardBox(Modifier.padding(bottom = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(accent))
            Text(title, color = Sand, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        }
        Text(body, color = Body, fontSize = 15.sp, lineHeight = 22.sp)
    }
}

@Composable
internal fun CardBox(
    modifier: Modifier = Modifier,
    color: Color = Card,
    content: @Composable ColumnScope.() -> Unit,
) = Column(
    modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(color).padding(horizontal = 20.dp, vertical = 18.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
    content = content,
)

@Composable
internal fun CardBox(color: Color, content: @Composable ColumnScope.() -> Unit) = CardBox(Modifier, color, content)

@Composable
internal fun Stepper(
    value: Int,
    caption: String,
    range: IntRange = GOAL_MIN..GOAL_MAX,
    step: Int = 1,
    what: String = t("meta", "goal"),
    onChange: (Int) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Card).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RoundButton("−", t("Bajar $what", "Lower $what")) { onChange((value - step).coerceIn(range)) }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$value", color = Sand, fontSize = 56.sp, fontWeight = FontWeight.Bold, lineHeight = 56.sp)
            Text(caption, color = Muted, fontSize = 14.sp)
        }
        RoundButton("+", t("Subir $what", "Raise $what")) { onChange((value + step).coerceIn(range)) }
    }
}

@Composable
internal fun RoundButton(symbol: String, label: String, onClick: () -> Unit) = Box(
    Modifier.size(56.dp).clip(CircleShape).background(Track2)
        .clickable(onClickLabel = label, role = Role.Button, onClick = onClick),
    contentAlignment = Alignment.Center,
) { Text(symbol, color = Sand, fontSize = 28.sp) }

@Composable
internal fun PrimaryButton(text: String, enabled: Boolean = true, color: Color = Sand, dot: Color? = null, onClick: () -> Unit) = Row(
    Modifier.fillMaxWidth().height(60.dp).clip(CircleShape).background(if (enabled) color else Track2)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
    horizontalArrangement = Arrangement.Center,
    verticalAlignment = Alignment.CenterVertically,
) {
    if (dot != null && enabled) Box(Modifier.padding(end = 10.dp).size(8.dp).clip(CircleShape).background(dot))
    Text(text, color = if (enabled) Bg else Muted, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
internal fun KeyValue(key: String, value: String, color: Color = Sand) = Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
    Text(key, color = Body, fontSize = 15.sp, modifier = Modifier.weight(1f))
    Text(value, color = color, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
internal fun DetailRow(title: String, sub: String, value: String, color: Color) = Row(
    Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 6.dp),
    verticalAlignment = Alignment.CenterVertically,
) {
    Column(Modifier.weight(1f)) {
        Text(title, color = Sand, fontSize = 16.sp)
        Text(sub, color = Muted, fontSize = 13.sp)
    }
    Text(value, color = color, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
}
