package app.getoff

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.format.DateTimeFormatter

// ---------- Historial ----------

@Composable
internal fun History(s: Snapshot, show: (Sheet) -> Unit) = Column(
    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp),
) {
    val today = s.today.date
    val ctx = LocalContext.current
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(t("Historial", "History"), color = Sand, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        if (s.days.isNotEmpty()) TextLink(t("Compartir mi semana", "Share my week")) { show(Sheet.ShareWeek) }
    }
    // En diciembre (y hasta el 15 de enero): el año entero, para compartir.
    wrappedYear(today)?.let { year ->
        Spacer(Modifier.height(16.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
        Source(Accent, t("Tu $year off", "Your $year off"), t("compartir", "share"), "", chevron = true) { shareCard(ctx, yearCard(s, year)) }
    }
    val chartHeight = Modifier.fillMaxWidth().padding(top = 24.dp).height(120.dp)
    if (s.days.none { it.date != today }) {
        // Sin días cerrados todavía: barras vacías y una explicación.
        Text(t("Tu historial arranca hoy", "Your history starts today"), color = Muted, fontSize = 17.sp, modifier = Modifier.padding(top = 24.dp))
        Row(chartHeight, horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.Bottom) {
            repeat(14) { i -> Box(Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)).background(if (i == 13) Dim else Track)) }
        }
        Text(t("Cada día que pase aparece acá, con lo que sumó cada fuente. Volvé mañana para ver el primero.",
            "Each day shows up here with what each source added. Come back tomorrow to see the first one."),
            color = Body, fontSize = 15.sp, lineHeight = 22.sp, modifier = Modifier.padding(top = 24.dp))
        return@Column
    }

    val dates = (13 downTo 0).map { today.minusDays(it.toLong()) }
    val byDate = s.days.associateBy { it.date }
    val irl = s.rondaLedger.groupBy { it.date }.mapValues { (_, e) -> e.sumOf { it.amount } }
    val window = dates.mapNotNull { byDate[it] }
    val entries = s.rondaLedger.filter { it.date in dates }

    Text(t("Últimos 14 días", "Last 14 days"), color = Muted, fontSize = 17.sp, modifier = Modifier.padding(top = 24.dp))
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("${window.sumOf { it.total } + entries.sumOf { it.amount }}", color = Sand,
            fontSize = 88.sp, fontWeight = FontWeight.SemiBold, lineHeight = 92.sp, letterSpacing = (-4).sp)
        val metas = window.count { it.met }
        Text(t("puntos · $metas ${if (metas == 1) "meta" else "metas"}", "points · $metas ${if (metas == 1) "goal" else "goals"}"), color = Muted, fontSize = 17.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(bottom = 14.dp))
    }

    // Una barra por día, apilada con el color de cada fuente; lo de IRL arriba de todo.
    val stacks = dates.map { date ->
        val d = byDate[date]
        val segs = (d?.sources().orEmpty() + ((d?.goalBonus ?: 0) to Ember) + ((irl[date] ?: 0).coerceAtLeast(0) to Irl)).filter { it.first > 0 }
        date to segs
    }
    val top = maxOf(stacks.maxOf { (_, segs) -> segs.sumOf { it.first } }, 1)
    Row(chartHeight, horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.Bottom) {
        stacks.forEach { (date, segs) ->
            val sum = segs.sumOf { it.first }
            val bar = Modifier.weight(1f).clip(RoundedCornerShape(6.dp)).clickable(onClickLabel = t("Ver día", "See day")) { show(Sheet.Day(date)) }
            if (sum == 0) Box(bar.height(6.dp).background(Track))
            else Column(bar.fillMaxHeight(sum.toFloat() / top), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                segs.reversed().forEach { (n, c) -> Box(Modifier.fillMaxWidth().weight(n.toFloat()).background(c)) }
            }
        }
    }
    Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Text(dates.first().format(DateTimeFormatter.ofPattern("EEE d", loc)).replace(".", ""), color = Faint, fontSize = 12.sp,
            modifier = Modifier.weight(1f))
        Text(t("hoy", "today"), color = Faint, fontSize = 12.sp)
    }

    // Lo que sumó cada fuente en esos 14 días.
    Spacer(Modifier.height(24.dp))
    val rows = buildList<Triple<Color, String, Pair<String, String>>> {
        add(Triple(Moss, t("Bloqueado", "Locked"), duration(window.sumOf { it.blocks } * 30) to "+${window.sumOf { it.blocks }}"))
        window.sumOf { it.restBonus }.takeIf { it > 0 }?.let { add(Triple(MossLight, t("Descanso largo", "Long rest"), t("$it ${if (it == 1) "vez" else "veces"}", "$it ${if (it == 1) "time" else "times"}") to "+$it")) }
        window.sumOf { it.sessionBonus }.takeIf { it > 0 }?.let { add(Triple(Mint, t("Sesiones", "Sessions"), t("bloques ×2", "blocks ×2") to "+$it")) }
        window.sumOf { it.stepBonus }.takeIf { it > 0 }?.let { add(Triple(Sky, t("Pasos", "Steps"), thousands(window.sumOf { d -> d.steps }) to "+$it")) }
        window.sumOf { it.exerciseBonus }.takeIf { it > 0 }?.let { add(Triple(Lilac, t("Ejercicio", "Exercise"), duration(window.sumOf { d -> d.exerciseMin }) to "+$it")) }
        window.sumOf { it.unlockBonus }.takeIf { it > 0 }?.let { add(Triple(Rose, t("Desbloqueos", "Unlocks"), t("menos que tu promedio", "fewer than your average") to "+$it")) }
        window.sumOf { it.goalBonus }.takeIf { it > 0 }?.let { add(Triple(Ember, t("Metas", "Goals"), "bonus" to "+$it")) }
        if (entries.isNotEmpty()) add(Triple(Irl, "IRL", t("${entries.size} ${if (entries.size == 1) "encuentro" else "encuentros"}", "${entries.size} ${if (entries.size == 1) "meetup" else "meetups"}") to signed(entries.sumOf { it.amount })))
        window.sumOf { it.socialPenalty }.takeIf { it > 0 }?.let { add(Triple(Gold, t("Redes", "Social media"), t("${duration(it * SOCIAL_MIN_PER_POINT)} de más", "${duration(it * SOCIAL_MIN_PER_POINT)} over") to "−$it")) }
    }
    rows.forEachIndexed { i, (c, name, v) -> Source(c, name, v.first, v.second, last = i == rows.lastIndex) }

    val recovered = recoveredMinutes(s.days.map { it.socialMin })
    if (recovered > 0) Text(t("Esta semana recuperaste ${duration(recovered)} de redes.", "This week you took back ${duration(recovered)} from social media."), color = Muted, fontSize = 13.sp,
        modifier = Modifier.padding(top = 16.dp))

    // Los días, del más nuevo al más viejo. Tocar uno abre su detalle.
    Spacer(Modifier.height(20.dp))
    val list = s.days.reversed().take(30)
    list.forEachIndexed { i, d ->
        val r = irl[d.date] ?: 0
        val now = d.date == today
        val frozen = d.date in s.frozen
        val hint = when {
            d.met -> t("meta", "goal")
            frozen -> t("comodín", "free pass")
            now -> t("en curso", "in progress")
            else -> t("sin meta", "no goal")
        } + if (r != 0) " · IRL ${signed(r)}" else ""
        Source(if (d.met) Accent else if (frozen) Shield else Line, if (now) t("Hoy", "Today") else d.date.label(t("EEEE d", "EEEE, MMM d")), hint, signed(d.total + r),
            last = i == list.lastIndex) { show(Sheet.Day(d.date)) }
    }
}
