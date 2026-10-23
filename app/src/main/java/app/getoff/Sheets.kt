package app.getoff

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import java.time.LocalDate

/** Qué hoja está abierta sobre la pantalla. */
sealed interface Sheet {
    data class Source(val kind: Kind) : Sheet
    data object Goal : Sheet
    data object Level : Sheet
    data object Streak : Sheet
    data object Settings : Sheet
    data object Rules : Sheet
    data object Session : Sheet // la hoja de Bloqueado con la sesión abierta
    data object RondaRules : Sheet
    data class Meeting(val f: Final) : Sheet
    data class Day(val date: LocalDate) : Sheet
    data object ShareWeek : Sheet
}

enum class Kind { BLOCKS, REST, STEPS, EXERCISE, SOCIAL, UNLOCKS }

@Composable
fun SheetContent(sheet: Sheet, s: Snapshot, open: (Sheet?) -> Unit, onChanged: () -> Unit) =
    Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 32.dp)) {
        when (sheet) {
            is Sheet.Source -> SourceSheet(sheet.kind, s, false) { onChanged(); open(null) }
            Sheet.Session -> SourceSheet(Kind.BLOCKS, s, true) { onChanged(); open(null) }
            Sheet.Goal -> GoalSheet(s) { onChanged(); open(null) }
            Sheet.Level -> LevelSheet(s)
            Sheet.Streak -> StreakSheet(s)
            Sheet.Settings -> SettingsSheet(s, open, onChanged)
            Sheet.Rules -> { SheetTitle(t("Cómo se suma", "How points work")); RulesList() }
            Sheet.RondaRules -> RondaRulesSheet()
            is Sheet.Meeting -> MeetingSheet(sheet.f, s.myId)
            is Sheet.Day -> DaySheet(sheet.date, s)
            Sheet.ShareWeek -> ShareWeekSheet(s)
        }
    }

/** Elegir con qué diseño compartir la semana. Cada semana arranca en otro, para no compartir siempre lo mismo. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ShareWeekSheet(s: Snapshot) {
    val ctx = LocalContext.current
    val w = remember(s) { weekStats(s) }
    val styles = CardStyle.entries
    val pager = rememberPagerState(initialPage = w.weekNumber % styles.size) { styles.size }
    SheetTitle(t("Compartir mi semana", "Share my week"))
    HorizontalPager(pager, Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 40.dp), pageSpacing = 12.dp) { i ->
        val preview by produceState<ImageBitmap?>(null, w, i) {
            value = withContext(Dispatchers.Default) { renderWeek(ctx, styles[i], w, 0.5f).asImageBitmap() }
        }
        Box(Modifier.fillMaxWidth().aspectRatio(9f / 16f).clip(RoundedCornerShape(16.dp)).background(Track)) {
            preview?.let { Image(it, contentDescription = styles[i].label, modifier = Modifier.fillMaxWidth().aspectRatio(9f / 16f)) }
        }
    }
    Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(styles[pager.currentPage].label, color = Sand, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(styles.size) { i ->
                Box(Modifier.size(if (i == pager.currentPage) 18.dp else 6.dp, 6.dp).clip(CircleShape)
                    .background(if (i == pager.currentPage) Sand else Dim))
            }
        }
    }
    Spacer(Modifier.height(20.dp))
    PrimaryButton(t("Compartir", "Share")) { shareBitmap(ctx, renderWeek(ctx, styles[pager.currentPage], w)) }
}

@Composable
private fun SheetTitle(text: String) =
    Text(text, color = Sand, fontSize = 28.sp, fontWeight = FontWeight.SemiBold, lineHeight = 32.sp,
        modifier = Modifier.padding(top = 8.dp, bottom = 16.dp))

@Composable
private fun Big(value: String, hint: String, color: Color = Sand) =
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(value, color = color, fontSize = 80.sp, fontWeight = FontWeight.SemiBold, lineHeight = 84.sp, letterSpacing = (-3).sp)
        Text(hint, color = Muted, fontSize = 17.sp, modifier = Modifier.padding(bottom = 14.dp))
    }

@Composable
private fun Kicker(text: String) = Text(text, color = Muted, fontSize = 15.sp, modifier = Modifier.padding(top = 8.dp))

@Composable
private fun Label(text: String) = Text(text, color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 24.dp, bottom = 8.dp))

@Composable
private fun Note(text: String, top: Int = 20) = Text(text, color = Muted, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = top.dp))

@Composable
private fun Hairline() = Box(Modifier.fillMaxWidth().height(1.dp).background(Line))

/** Fila sin punto, con el valor en el color que haga falta. */
@Composable
private fun Item(label: String, value: String, color: Color = Muted, bold: Boolean = false, last: Boolean = false, onClick: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, color = Sand, fontSize = 15.sp, modifier = Modifier.weight(1f))
        if (value.isNotEmpty()) Text(value, color = color, fontSize = 15.sp, fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal)
        if (onClick != null) Text("›", color = Faint, fontSize = 16.sp)
    }
    if (!last) Hairline()
}

@Composable
private fun OutlineButton(text: String, onClick: () -> Unit) = Box(
    Modifier.fillMaxWidth().height(56.dp).clip(CircleShape).border(1.dp, Sand, CircleShape).clickable(role = Role.Button, onClick = onClick),
    contentAlignment = Alignment.Center,
) { Text(text, color = Sand, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }

@Composable
internal fun ThinBar(fraction: Float, color: Color, marker: Float? = null) =
    Box(Modifier.fillMaxWidth().height(18.dp), contentAlignment = Alignment.CenterStart) {
        Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(Track2)) {
            Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(6.dp).clip(CircleShape).background(color))
        }
        marker?.let { m -> Box(Modifier.fillMaxWidth(m.coerceIn(0f, 1f))) { Box(Modifier.align(Alignment.CenterEnd).size(2.dp, 18.dp).background(Sand)) } }
    }

/** Conectar Health Connect: pedir permiso si está, o mandar a instalarlo. */
private fun connectHealth(ctx: android.content.Context, health: Health, ask: ActivityResultLauncher<Set<String>>) {
    if (health == Health.ASK) ask.launch(healthRequest(ctx))
    else runCatching {
        ctx.startActivity(Intent(Intent.ACTION_VIEW,
            Uri.parse("market://details?id=com.google.android.apps.healthdata&url=healthconnect%3A%2F%2Fonboarding")))
    }
}

// ---------- Fuentes ----------

@Composable
private fun SourceSheet(kind: Kind, s: Snapshot, sessionOpen: Boolean, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val d = s.today
    val ask = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) {}
    // Pasos y ejercicio sin Health Connect: una sola hoja para conectarlo.
    if ((kind == Kind.STEPS || kind == Kind.EXERCISE) && s.health != Health.ON) {
        SourceHead(Sky, t("Pasos y ejercicio", "Steps and exercise"))
        Big("—", t("sin conectar", "not connected"))
        Next(t("Health Connect no está conectado", "Health Connect isn't connected"), 0f, Sky)
        RuleText(t("Leemos tus pasos y tu ejercicio desde Health Connect. Nada sale de tu celu y no escribimos nada ahí.", "We read your steps and exercise from Health Connect. Nothing leaves your phone and we never write there."))
        if (s.health != Health.NONE) {
            Spacer(Modifier.height(24.dp))
            PrimaryButton(t("Conectar Health Connect", "Connect Health Connect")) { connectHealth(ctx, s.health, ask) }
        }
        return
    }
    when (kind) {
        Kind.BLOCKS, Kind.REST -> {
            val blocks = kind == Kind.BLOCKS
            val color = if (blocks) Moss else MossLight
            SourceHead(color, if (blocks) t("Bloqueado", "Locked") else t("Descanso largo", "Long rest"))
            if (blocks) Big("+${d.blocks}", t("${duration(d.blocks * 30)} contados", "${duration(d.blocks * 30)} counted")) else Big("+${d.restBonus}", t("por 2 h seguidas", "for 2 h straight"))
            val unit = if (blocks) 30 else 120
            val min = s.lastRestMin
            if (min == null) Next(if (blocks) t("Bloqueá el celu 30 min para el primero", "Lock your phone for 30 min to get the first one") else t("Bloqueá el celu 2 h seguidas para el primero", "Lock your phone 2 h straight to get the first one"), 0f, color)
            else Next(t("Tu último descanso: ${duration(min)} · faltaron ${unit - min % unit} min", "Your last break: ${duration(min)} · ${unit - min % unit} min short"), (min % unit) / unit.toFloat(), color)
            RuleText(if (blocks) t("1 punto cada 30 min con el celu bloqueado, de 8 a 24 h. Cada 2 h seguidas, 1 más.", "1 point every 30 min with your phone locked, 8 am to midnight. Every 2 h straight, 1 more.")
                else t("1 punto extra por cada 2 h seguidas sin desbloquear. Premia desconectarse de verdad, no juntar ratos.", "1 extra point for every 2 h straight without unlocking. It rewards really switching off, not adding up short breaks."))
            if (blocks) SessionEntry(s, sessionOpen, onDone)
        }
        Kind.STEPS -> {
            SourceHead(Sky, t("Pasos", "Steps"))
            Big("+${d.stepBonus}", t("${thousands(d.steps)} pasos bloqueado", "${thousands(d.steps)} steps locked"))
            val per = if (d.steps < STEPS_FULL_RATE) STEPS_PER_POINT else STEPS_PER_POINT * 2
            val into = if (d.steps < STEPS_FULL_RATE) d.steps % per else (d.steps - STEPS_FULL_RATE) % per
            Next(t("Te faltan ${thousands(per - into)} pasos", "${thousands(per - into)} steps to go"), into.toFloat() / per, Sky)
            RuleText(t("1 punto cada 1.000 pasos con el celu en el bolsillo, bloqueado. Pasados los 10.000, cada 2.000.", "1 point every 1,000 steps with your phone locked in your pocket. Past 10,000, every 2,000."))
        }
        Kind.EXERCISE -> {
            SourceHead(Lilac, t("Ejercicio", "Exercise"))
            Big("+${d.exerciseBonus}", t("${duration(d.exerciseMin)} de ejercicio", "${duration(d.exerciseMin)} of exercise"))
            val per = if (d.exerciseMin < EXERCISE_FULL_RATE_MIN) EXERCISE_MIN_PER_POINT else EXERCISE_MIN_PER_POINT * 2
            val into = if (d.exerciseMin < EXERCISE_FULL_RATE_MIN) d.exerciseMin % per else (d.exerciseMin - EXERCISE_FULL_RATE_MIN) % per
            Next(t("Próximo punto en ${per - into} min", "Next point in ${per - into} min"), into.toFloat() / per, Lilac)
            val rows = d.exerciseByType.entries.sortedByDescending { it.value }
            if (rows.isNotEmpty()) Spacer(Modifier.height(12.dp))
            rows.forEachIndexed { i, (type, min) -> Source(Lilac, exerciseName(type), "", duration(min), last = i == rows.lastIndex) }
            RuleText(t("Bici, gimnasio, natación: 1 punto cada 10 min la primera hora, después cada 20. Las sesiones cargadas a mano no cuentan.", "Cycling, gym, swimming: 1 point every 10 min for the first hour, then every 20. Workouts entered by hand don't count."))
        }
        Kind.SOCIAL -> {
            SourceHead(Gold, t("Redes", "Social media"))
            val avg = s.socialAvg
            val over = avg?.let { d.socialMin - it }
            Big(if (d.socialPenalty > 0) "−${d.socialPenalty}" else "0",
                if (over != null && over > 0) t("${duration(over)} de más", "${duration(over)} over") else t("${duration(d.socialMin)} hoy", "${duration(d.socialMin)} today"))
            when {
                avg == null -> Next(t("Armando tu promedio · día ${s.historyCount + 1} de $HISTORY_DAYS", "Building your average · day ${s.historyCount + 1} of $HISTORY_DAYS"), (s.historyCount + 1f) / HISTORY_DAYS, Gold)
                over!! < 0 -> {
                    val scale = maxOf(avg, d.socialMin, 1) * 1.25f
                    Next(t("Te quedan ${duration(-over)} hasta tu promedio", "${duration(-over)} left until your average"), d.socialMin / scale, Gold, avg / scale)
                }
                else -> Next(t("En ${SOCIAL_MIN_PER_POINT - over % SOCIAL_MIN_PER_POINT} min resta otro punto", "Another point off in ${SOCIAL_MIN_PER_POINT - over % SOCIAL_MIN_PER_POINT} min"),
                    (over % SOCIAL_MIN_PER_POINT) / SOCIAL_MIN_PER_POINT.toFloat(), Gold)
            }
            RuleText(t("Hasta tu promedio no pasa nada. Después resta 1 punto cada 5 min. Si estás en un grupo de IRL, esos puntos van a su pozo.", "Up to your average nothing happens. After that, 1 point off every 5 min. If you're in an IRL group, those points go into its pot."))
        }
        Kind.UNLOCKS -> {
            SourceHead(Rose, t("Desbloqueos", "Unlocks"))
            val proj = unlockPoints(d.unlocks, s.unlockHistory)
            Big("+$proj", t("si no desbloqueás más", "if you don't unlock again"), if (proj > 0) Sand else Faint)
            if (s.unlockHistory.size >= HISTORY_DAYS) {
                val avg = s.unlockHistory.average().toInt()
                val scale = maxOf(avg, d.unlocks, 1) * 1.25f
                Next(t("Vas ${d.unlocks} · tu promedio es $avg", "${d.unlocks} so far · your average is $avg"), d.unlocks / scale, Rose, avg / scale)
            } else Next(t("Armando tu promedio de desbloqueos", "Building your unlock average"), (s.unlockHistory.size + 1f) / HISTORY_DAYS, Rose)
            RuleText(t("1 punto cada 5 desbloqueos menos que tu promedio. Se suma al terminar el día.", "1 point for every 5 unlocks under your average. Added when the day ends."))
        }
    }
}

@Composable
private fun SourceHead(color: Color, label: String) =
    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Text(label, color = Muted, fontSize = 15.sp)
    }

@Composable
private fun Next(label: String, fraction: Float, color: Color, marker: Float? = null) {
    Label(label)
    ThinBar(fraction, color, marker)
}

@Composable
private fun RuleText(text: String) = Text(text, color = Body, fontSize = 15.sp, lineHeight = 22.sp, modifier = Modifier.padding(top = 20.dp))

/** Sesión: los próximos bloques valen doble si no se desbloquea hasta el final. */
@Composable
private fun SessionEntry(s: Snapshot, startOpen: Boolean, onDone: () -> Unit) {
    val ctx = LocalContext.current
    var open by remember { mutableStateOf(startOpen) }
    var picked by remember { mutableIntStateOf(60) }
    Spacer(Modifier.height(28.dp))
    val running = s.session
    when {
        running != null -> Text(t("Sesión en curso · termina ${clock(running.first + running.second * 60_000L)}", "Session in progress · ends ${clock(running.first + running.second * 60_000L)}"),
            color = Muted, fontSize = 15.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        !open -> OutlineButton(t("Doblar los próximos bloques", "Double the next blocks")) { open = true }
        else -> Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Bg).padding(20.dp)) {
            Text(t("Sesión: bloques ×2", "Session: blocks ×2"), color = Sand, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text(t("Bloqueá el celu ahora. Si no lo desbloqueás hasta el final, valen doble. Si lo desbloqueás antes, no pasa nada.", "Lock your phone now. If you don't unlock it until the end, they count double. Unlock early and nothing happens."),
                color = Muted, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.padding(top = 4.dp))
            Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SESSION_OPTIONS.forEach { min ->
                    Column(
                        Modifier.weight(1f).height(64.dp).clip(RoundedCornerShape(16.dp)).background(SheetBg)
                            .border(2.dp, if (min == picked) Sand else Line, RoundedCornerShape(16.dp))
                            .clickable(role = Role.RadioButton) { picked = min },
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                    ) {
                        Text(duration(min), color = Sand, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        Text("+${min / 30}", color = Muted, fontSize = 12.sp)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            PrimaryButton(t("Empezar · termina ${clock(System.currentTimeMillis() + picked * 60_000L)}", "Start · ends ${clock(System.currentTimeMillis() + picked * 60_000L)}")) {
                startSession(ctx, picked)
                onDone()
            }
        }
    }
}

// ---------- Meta ----------

@Composable
private fun GoalSheet(s: Snapshot, onSaved: () -> Unit) {
    val ctx = LocalContext.current
    val d = s.today
    var goal by remember { mutableIntStateOf(s.nextGoal) }
    val done = d.toGoal.coerceIn(0, d.goal)
    Kicker(t("Meta de hoy", "Today's goal"))
    Big("${d.toGoal.coerceAtLeast(0)}", if (d.met) t("de ${d.goal} · cumplida", "of ${d.goal} · met") else t("de ${d.goal} · faltan ${(d.goal - d.toGoal).coerceAtLeast(1)}", "of ${d.goal} · ${(d.goal - d.toGoal).coerceAtLeast(1)} to go"))
    Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(d.goal) { i -> Box(Modifier.weight(1f).height(20.dp).clip(RoundedCornerShape(6.dp)).background(if (i < done) Accent else Line)) }
    }
    Spacer(Modifier.height(24.dp))
    val prev = if (d.met) s.streak - 1 else s.streak
    Hairline()
    if (d.met) Item(t("Ganaste", "You earned"), t("+${d.goalBonus} y un día de racha", "+${d.goalBonus} and a streak day"), Accent, bold = true)
    else Item(t("Al cumplirla", "When you hit it"), t("+${goalBonus(d.goal, prev)} y un día de racha", "+${goalBonus(d.goal, prev)} and a streak day"), Accent, bold = true)
    Item(t("Tu racha lo multiplica", "Your streak multiplies it"), times(streakMultiplier(prev)), Sand, bold = true)
    Label(t("Meta diaria", "Daily goal"))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        RoundButton("−", t("Menos tiempo", "Less time")) { goal = (goal - 1).coerceIn(GOAL_MIN, GOAL_MAX) }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(hours(goal), color = Sand, fontSize = 40.sp, fontWeight = FontWeight.SemiBold, lineHeight = 44.sp, maxLines = 1)
            Text("bloqueado · $goal puntos por día", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
        }
        RoundButton("+", t("Más tiempo", "More time")) { goal = (goal + 1).coerceIn(GOAL_MIN, GOAL_MAX) }
    }
    Text(
        if (goal != d.goal) t("Hoy seguís con ${hours(d.goal)}. La nueva arranca mañana.", "Today stays at ${hours(d.goal)}. The new one starts tomorrow.") else t("Un cambio arranca al día siguiente.", "Changes start the next day."),
        color = if (goal != d.goal) ErrFg else Faint, fontSize = 13.sp, textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
    )
    Spacer(Modifier.height(16.dp))
    PrimaryButton(t("Guardar", "Save"), enabled = goal != s.nextGoal) {
        Store(ctx).setGoal(goal, d.date)
        onSaved()
    }
}

// ---------- Nivel ----------

@Composable
private fun LevelSheet(s: Snapshot) {
    Kicker(t("${thousands(s.totalPoints)} puntos", "${thousands(s.totalPoints)} points"))
    Text(t("Nivel ${s.level}", "Level ${s.level}"), color = Sand, fontSize = 56.sp, fontWeight = FontWeight.SemiBold, lineHeight = 60.sp, letterSpacing = (-2).sp)
    Text(if (s.level == LEVEL_MAX) t("el máximo", "the top") else t("faltan ${thousands(s.toNextLevel)} para el ${s.level + 1}", "${thousands(s.toNextLevel)} to level ${s.level + 1}"), color = Muted, fontSize = 13.sp)
    Spacer(Modifier.height(16.dp))
    val ronda = s.rondaLedger.sumOf { it.amount }
    Source(null, t("Por tus días", "From your days"), "", thousands(s.totalPoints - ronda), last = ronda == 0)
    if (ronda != 0) Source(null, t("Por IRL", "From IRL"), "", signed(ronda), last = true)
    Spacer(Modifier.height(24.dp))
    val bg = Bg
    (LEVEL_MAX downTo 1).forEach { n ->
        val current = n == s.level
        Hairline()
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(28.dp).clip(CircleShape).background(if (n < s.level) Sand else if (current) Accent else Line), contentAlignment = Alignment.Center) {
                Text("$n", color = if (n <= s.level) bg else Faint, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
            Text(t("Nivel $n", "Level $n"), color = if (n <= s.level) Sand else Faint, fontSize = 15.sp,
                fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.weight(1f))
            Text(thousands(levelStart(n)), color = Muted, fontSize = 13.sp)
        }
    }
    Hairline()
    Note(if (s.level == LEVEL_MAX) t("Llegaste arriba de todo. Los puntos siguen sumando para tu historial y tus grupos.", "You made it to the top. Points still count for your history and your groups.")
        else t("Cinco niveles. Los puntos solo suman: tu nivel nunca baja.", "Five levels. Points only go up: your level never drops."))
}

// ---------- Racha ----------

@Composable
private fun StreakSheet(s: Snapshot) {
    val today = s.today.date
    Kicker(t("Racha · mejor ${s.bestStreak}", "Streak · best ${s.bestStreak}"))
    Big("${s.streak}", if (s.streak == 1) t("día", "day") else t("días", "days"), Accent)
    val byDate = s.days.associateBy { it.date }
    val days = (27 downTo 0).map { today.minusDays(it.toLong()) }
    Column(Modifier.padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        days.chunked(7).forEach { week ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                week.forEach { date ->
                    val shape = RoundedCornerShape(10.dp)
                    Box(Modifier.weight(1f).aspectRatio(1f).clip(shape)
                        .background(when {
                            byDate[date]?.met == true -> Accent
                            date in s.frozen -> Shield
                            date == today -> SheetBg
                            else -> Track
                        })
                        .then(if (date == today) Modifier.border(2.dp, Sand, shape) else Modifier))
                }
            }
        }
    }
    Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        listOf(Accent to t("meta", "goal"), Shield to t("comodín", "free pass")).forEach { (c, t) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(c))
                Text(t, color = Muted, fontSize = 13.sp)
            }
        }
    }
    Note(t("Un comodín por semana salva el primer día sin meta. ", "One free pass a week saves the first day you miss your goal. ") + if (s.freezeLeft) t("Esta semana lo tenés.", "You have it this week.") else t("Esta semana ya lo usaste.", "You already used it this week."), top = 16)
}

// ---------- Día ----------

@Composable
private fun DaySheet(date: LocalDate, s: Snapshot) {
    val d = s.days.firstOrNull { it.date == date }
    val ronda = s.rondaLedger.filter { it.date == date }
    val today = date == s.today.date
    val state = when {
        d?.met == true -> t("meta cumplida", "goal met")
        date in s.frozen -> t("comodín", "free pass")
        today -> t("en curso", "in progress")
        else -> t("sin meta", "no goal")
    }
    Kicker((if (today) t("Hoy", "Today") else date.label("EEEE d")) + " · " + state)
    Big(signed((d?.total ?: 0) + ronda.sumOf { it.amount }), t("puntos", "points"))
    Spacer(Modifier.height(20.dp))
    if (d == null && ronda.isEmpty()) { Note(t("Sin datos de ese día.", "No data for that day."), top = 0); return }
    val rows = buildList<@Composable () -> Unit> {
        if (d != null) {
            add { Source(Moss, t("Bloqueado", "Locked"), duration(d.blocks * 30), "+${d.blocks}") }
            if (d.restBonus > 0) add { Source(MossLight, t("Descanso largo", "Long rest"), t("2 h seguidas", "2 h straight"), "+${d.restBonus}") }
            if (d.sessionBonus > 0) add { Source(Mint, t("Sesiones", "Sessions"), t("bloques ×2", "blocks ×2"), "+${d.sessionBonus}") }
            if (d.stepBonus > 0) add { Source(Sky, t("Pasos", "Steps"), thousands(d.steps), "+${d.stepBonus}") }
            if (d.exerciseBonus > 0) add { Source(Lilac, t("Ejercicio", "Exercise"), duration(d.exerciseMin), "+${d.exerciseBonus}") }
            if (d.unlockBonus > 0) add { Source(Rose, t("Desbloqueos", "Unlocks"), "${d.unlocks}", "+${d.unlockBonus}") }
            if (d.goalBonus > 0) add { Source(Accent, t("Meta", "Goal"), "bonus", "+${d.goalBonus}") }
            if (d.socialPenalty > 0) add { Source(Gold, t("Redes", "Social media"), duration(d.socialMin), "−${d.socialPenalty}") }
        }
        ronda.forEach { e -> add { Source(Irl, t("IRL · ${e.label}", "IRL · ${e.label}"), "", signed(e.amount)) } }
    }
    Hairline()
    rows.forEach { it() }
}

// ---------- Ajustes ----------

private enum class Page { MAIN, NAME, PERMS, RULES }

@Composable
private fun SettingsSheet(s: Snapshot, open: (Sheet?) -> Unit, onChanged: () -> Unit) {
    val ctx = LocalContext.current
    var page by remember { mutableStateOf(Page.MAIN) }
    // Se lee del sistema: getGrantedPermissions() de connect-client 1.1.0-alpha08 no lo incluía aunque estuviera dado.
    // Se recalcula en cada recarga (al volver de Health Connect la app se reanuda y recarga).
    val background = s.health == Health.ON && ctx.checkSelfPermission(HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED
    val ask = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) {}
    // Con pasos y ejercicio ya dados, algunos Android no muestran el diálogo para el permiso de segundo plano:
    // si no vuelve concedido, abrimos la pantalla de permisos de Health Connect de la app.
    val askBackground = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
        if (HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND !in granted) openHealthPermissions(ctx)
    }
    val notifs = NotificationManagerCompat.from(ctx).areNotificationsEnabled()
    // Acceso al uso siempre está: sin él no se llega acá.
    val total = if (s.health == Health.NONE) 2 else 3
    val granted = 1 + (if (notifs) 1 else 0) + (if (s.health == Health.ON) 1 else 0)

    if (page != Page.MAIN) Text(t("‹ Ajustes", "‹ Settings"), color = Muted, fontSize = 15.sp,
        modifier = Modifier.heightIn(min = 44.dp).clickable(role = Role.Button) { page = Page.MAIN }.padding(vertical = 12.dp))
    when (page) {
        Page.MAIN -> {
            SheetTitle(t("Ajustes", "Settings"))
            Hairline()
            Item(t("Nombre", "Name"), s.nickname ?: t("elegir", "choose")) { page = Page.NAME }
            Item(t("Meta diaria", "Daily goal"), hours(s.nextGoal)) { open(Sheet.Goal) }
            Item(t("Permisos", "Permissions"), t("$granted de $total", "$granted of $total"), if (granted < total) Accent else Muted, bold = granted < total) { page = Page.PERMS }
            Item(t("Cómo se suma", "How points work"), "") { page = Page.RULES }
            val version = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull().orEmpty() }
            Text(t("Todo queda en tu celu. off. $version", "Everything stays on your phone. off. $version"), color = Faint, fontSize = 13.sp, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 20.dp))
        }
        Page.NAME -> {
            var name by remember { mutableStateOf(s.nickname.orEmpty()) }
            SheetTitle(t("Tu nombre", "Your name"))
            val ink = Sand
            BasicTextField(name, { name = it.take(20) }, singleLine = true, cursorBrush = SolidColor(Accent),
                textStyle = TextStyle(color = Sand, fontSize = 28.sp, fontWeight = FontWeight.Medium),
                modifier = Modifier.fillMaxWidth().height(56.dp).padding(top = 8.dp).drawBehind {
                    // Solo la línea de abajo, como en el diseño.
                    val w = 2.dp.toPx()
                    drawRect(ink, androidx.compose.ui.geometry.Offset(0f, size.height - w), androidx.compose.ui.geometry.Size(size.width, w))
                })
            Note(t("Lo ven tus grupos en el próximo encuentro.", "Your groups see it at the next meetup."), top = 12)
            Spacer(Modifier.height(24.dp))
            PrimaryButton(t("Guardar", "Save"), enabled = cleanName(name).isNotEmpty()) {
                Store(ctx).nickname = cleanName(name)
                onChanged()
                page = Page.MAIN
            }
        }
        Page.PERMS -> {
            SheetTitle(t("Permisos", "Permissions"))
            Hairline()
            Item(t("Acceso al uso", "Usage access"), t("activo", "on")) { ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
            Item(t("Notificaciones", "Notifications"), if (notifs) t("activas", "on") else t("apagadas", "off"), last = s.health == Health.NONE) {
                ctx.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName))
            }
            when (s.health) {
                Health.NONE -> Unit
                Health.ON -> {
                    Item(t("Pasos y ejercicio", "Steps and exercise"), t("activo", "on")) { openHealthPermissions(ctx) }
                    if (background) Item(t("Leer en segundo plano", "Read in the background"), t("activo", "on"), last = true)
                    else Item(t("Leer en segundo plano", "Read in the background"), t("activar", "turn on"), Accent, bold = true, last = true) {
                        askBackground.launch(setOf(HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND))
                    }
                }
                else -> Item(t("Pasos y ejercicio", "Steps and exercise"), t("conectar", "connect"), Accent, bold = true, last = true) { connectHealth(ctx, s.health, ask) }
            }
            Hairline()
            if (!notifs) Note(t("No te podemos avisar cuando cumplís la meta ni cuando llega el resumen del domingo.", "We can't tell you when you hit your goal or when the Sunday summary is ready."), top = 16)
        }
        Page.RULES -> { SheetTitle(t("Cómo se suma", "How points work")); RulesList() }
    }
}

/** Las reglas en una línea cada una: Ajustes y su hoja. */
@Composable
private fun RulesList() {
    val rows = listOf(
        Triple(Moss, t("30 min bloqueado (8 a 24 h)", "30 min locked (8 am to midnight)"), "+1"),
        Triple(MossLight, t("2 h seguidas", "2 h straight"), "+1"),
        Triple(Sky, t("1.000 pasos bloqueado", "1,000 steps locked"), "+1"),
        Triple(Lilac, t("10 min de ejercicio", "10 min of exercise"), "+1"),
        Triple(Rose, t("5 desbloqueos menos", "5 fewer unlocks"), "+1"),
        Triple(Gold, t("5 min de redes de más", "5 min over on social media"), "−1"),
    )
    Hairline()
    rows.forEach { (c, t, v) -> Source(c, t, "", v) }
}

@Composable
internal fun NameField(name: String, label: String = t("¿Cómo te dicen?", "What should we call you?"), onChange: (String) -> Unit) =
    OutlinedTextField(name, { onChange(it.take(20)) }, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true,
        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Sand, unfocusedTextColor = Sand,
            focusedBorderColor = Moss, unfocusedBorderColor = Dim, focusedLabelColor = Moss, unfocusedLabelColor = Muted))

@Composable
internal fun Confirm(title: String, body: String, yes: String, onYes: () -> Unit, onNo: () -> Unit) = AlertDialog(
    onDismissRequest = onNo,
    containerColor = SheetBg,
    title = { Text(title, color = Sand) },
    text = { Text(body, color = Body) },
    confirmButton = { TextButton(onYes) { Text(yes, color = Sand, fontWeight = FontWeight.SemiBold) } },
    dismissButton = { TextButton(onNo) { Text(t("No", "No"), color = Muted) } },
)

// ---------- IRL ----------

@Composable
private fun RondaRulesSheet() {
    SheetTitle(t("Cómo funciona IRL", "How IRL works"))
    listOf(
        t("Si alguien se pasa de redes, esos puntos van al pozo de cada uno de sus grupos.", "Go over on social media and those points go into the pot of each of your groups."),
        t("El pozo se reparte solo en persona, entre los presentes, según lo que ganó cada uno.", "The pot is only split in person, among those present, by what each one earned."),
        t("En una juntada, cada 30 min con todos los celus bloqueados vale doble. El primero que desbloquea pone $UNLOCK_FINE.", "At a hangout, every 30 min with every phone locked counts double. First to unlock puts in $UNLOCK_FINE."),
        t("Sin servidor: todo viaja por QR, de celu a celu.", "No server: everything travels by QR, phone to phone."),
    ).forEachIndexed { i, t ->
        Hairline()
        Row(Modifier.fillMaxWidth().padding(vertical = 14.dp)) {
            Text("${i + 1}", color = Accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(24.dp))
            Text(t, color = Sand, fontSize = 15.sp, lineHeight = 21.sp, modifier = Modifier.weight(1f))
        }
    }
    Hairline()
}

@Composable
private fun MeetingSheet(f: Final, me: String) {
    val at = java.time.Instant.ofEpochMilli(f.to).atZone(java.time.ZoneId.systemDefault())
    Text(at.toLocalDate().label("EEEE d MMM") + " · " + at.toLocalTime().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")),
        color = Muted, fontSize = 15.sp, modifier = Modifier.padding(top = 8.dp))
    val rows = outcome(f).sortedByDescending { it.delta }
    val mine = rows.firstOrNull { it.id == me }
    if (mine != null) Big(signed(mine.delta), t("para vos", "for you"), if (mine.delta > 0) Moss else Sand)
    if (f.from > 0) Text(t("Juntada de ${duration(((f.to - f.from) / 60_000).toInt())}", "${duration(((f.to - f.from) / 60_000).toInt())} hangout"), color = Muted, fontSize = 14.sp,
        modifier = Modifier.padding(top = 8.dp))
    Label(t("${rows.size} presentes", "${rows.size} present"))
    rows.forEachIndexed { i, o -> Source(null, o.name, why(o), signed(o.delta), last = i == rows.lastIndex) }
}

/** Las reglas de puntos para el onboarding. */
@Composable
internal fun androidx.compose.foundation.layout.ColumnScope.HowItWorks() {
    Rule(Moss, t("1 punto cada 30 min", "1 point every 30 min"), t("con el celu bloqueado, entre las 8 y las 24 h. Dormir no cuenta.", "with your phone locked, between 8 am and midnight. Sleeping doesn't count."))
    Rule(MossLight, t("Descanso largo", "Long rest"), t("1 punto extra cada 2 h seguidas sin desbloquear.", "1 extra point every 2 h straight without unlocking."))
    Rule(Mint, t("Sesiones", "Sessions"), t("Elegí 30 min, 1 h o 2 h y bloqueá el celu. Si no lo desbloqueás hasta el final, los bloques valen doble.", "Pick 30 min, 1 h or 2 h and lock your phone. If you don't unlock until the end, the blocks count double."))
    Rule(Rose, t("Menos desbloqueos", "Fewer unlocks"), t("1 punto cada 5 desbloqueos menos que tu promedio. Se suma al cerrar el día.", "1 point for every 5 unlocks under your average. Added when the day closes."))
    Rule(Sky, t("Pasos desenchufados", "Unplugged steps"), t("1 punto cada 1.000 pasos con el celu bloqueado. Pasados los 10.000, cada 2.000.", "1 point every 1,000 steps with your phone locked. Past 10,000, every 2,000."))
    Rule(Lilac, t("Ejercicio", "Exercise"), t("Bici, gimnasio, natación: 1 punto cada 10 min la primera hora, después cada 20.", "Cycling, gym, swimming: 1 point every 10 min for the first hour, then every 20."))
    Rule(Gold, t("Redes de más restan", "Extra social media costs you"), t("Hasta tu promedio semanal no pasa nada. Después, 1 punto menos cada 5 min.", "Up to your weekly average nothing happens. After that, 1 point off every 5 min."))
    Rule(Ember, t("Meta y racha", "Goal and streak"), t("Al cumplir tu meta sumás un bonus y un día de racha. La racha multiplica el bonus, hasta ×2. ", "Hit your goal for a bonus and a streak day. The streak multiplies the bonus, up to ×2. ") +
        t("Un comodín por semana salva el primer día sin meta.", "One free pass a week saves the first day you miss your goal."))
}

/** La pantalla de permisos de Health Connect para esta app; si no existe, la de Health Connect en general. */
private fun openHealthPermissions(ctx: android.content.Context) {
    val app = Intent("android.health.connect.action.MANAGE_HEALTH_PERMISSIONS").putExtra(Intent.EXTRA_PACKAGE_NAME, ctx.packageName)
    runCatching { ctx.startActivity(app) }
        .recoverCatching { ctx.startActivity(Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS)) }
}
