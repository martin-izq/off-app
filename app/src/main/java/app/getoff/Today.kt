package app.getoff

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.health.connect.client.PermissionController
import java.time.LocalTime

private val CONNECT_BANNER get() = t("Conectá Health Connect para sumar tus pasos y tu ejercicio.", "Connect Health Connect to earn points for steps and exercise.")

@Composable
internal fun Today(s: Snapshot, since: Int?, open: (Sheet) -> Unit, onChanged: () -> Unit) {
    val ctx = LocalContext.current
    // Al volver del diálogo de Health Connect la app se reanuda y recarga sola.
    val ask = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) {}
    val connect: () -> Unit = {
        if (s.health == Health.ASK) ask.launch(healthRequest(ctx))
        else runCatching {
            ctx.startActivity(Intent(Intent.ACTION_VIEW,
                Uri.parse("market://details?id=com.google.android.apps.healthdata&url=healthconnect%3A%2F%2Fonboarding")))
        }
    }
    val d = s.today
    val healthOff = s.health != Health.ON && s.health != Health.NONE
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Wordmark(Modifier.weight(1f))
            Box(
                Modifier.size(44.dp).clip(CircleShape).clickable(onClickLabel = t("Ajustes", "Settings")) { open(Sheet.Settings) },
                contentAlignment = Alignment.Center,
            ) { LineIcon(LineIcons.MENU, Sand, t("Ajustes", "Settings")) }
        }
        Total(s, since) { open(Sheet.Level) }
        // Un solo aviso: el primero que aplica.
        val banner = when {
            LocalTime.now().hour < WAKE_HOUR -> t("De 0 a 8 h no suma: es para dormir. Los pasos de una salida temprano sí cuentan.",
                "Midnight to 8 am doesn't count: that's for sleeping. Steps from an early outing do.")
            s.historyCount < HISTORY_DAYS -> t(
                "Armando tu promedio de redes y desbloqueos: día ${s.historyCount + 1} de $HISTORY_DAYS. Hasta entonces no restan ni suman.",
                "Building your social media and unlock averages: day ${s.historyCount + 1} of $HISTORY_DAYS. Until then they don't add or subtract.")
            d.total < 0 && d.socialPenalty > 0 -> t("Pasaste tu promedio de redes. Cada 5 min de más resta 1 punto.",
                "You're over your social media average. Every extra 5 min costs 1 point.")
            healthOff -> CONNECT_BANNER
            else -> null
        }
        banner?.let {
            Text(it, color = Body, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.padding(top = 24.dp).fillMaxWidth()
                .clip(RoundedCornerShape(14.dp)).background(Card)
                .then(if (it == CONNECT_BANNER) Modifier.clickable(role = Role.Button, onClick = connect) else Modifier)
                .padding(horizontal = 14.dp, vertical = 12.dp))
        }
        s.session?.let { (start, minutes) ->
            ActiveSession(start, minutes) { cancelSession(ctx); onChanged() }
        }
        Week(s) { open(Sheet.Streak) }
        TodayLine(d) { open(Sheet.Goal) }
        Spacer(Modifier.height(16.dp))
        Source(Moss, t("Bloqueado", "Locked"), duration(d.blocks * 30), "+${d.blocks}", chevron = true) { open(Sheet.Source(Kind.BLOCKS)) }
        if (d.restBonus > 0) Source(MossLight, t("Descanso largo", "Long rest"), t("2 h seguidas", "2 h straight"), "+${d.restBonus}", chevron = true) { open(Sheet.Source(Kind.REST)) }
        if (d.sessionBonus > 0) Source(Mint, t("Sesiones", "Sessions"), t("bloques ×2", "blocks ×2"), "+${d.sessionBonus}")
        if (s.health == Health.ON) {
            Source(Sky, t("Pasos", "Steps"), t("${thousands(d.steps)} bloqueado", "${thousands(d.steps)} locked"), "+${d.stepBonus}", chevron = true) { open(Sheet.Source(Kind.STEPS)) }
            Source(Lilac, t("Ejercicio", "Exercise"), if (d.exerciseMin > 0) duration(d.exerciseMin) else t("sin ejercicio hoy", "no exercise today"), "+${d.exerciseBonus}",
                chevron = true) { open(Sheet.Source(Kind.EXERCISE)) }
        } else if (healthOff) ConnectRow(if (s.health == Health.INSTALL) t("instalar ›", "install ›") else t("conectar ›", "connect ›"), connect)
        val left = s.socialAvg?.let { it - d.socialMin }
        Source(Gold, t("Redes", "Social media"),
            when {
                left == null -> t("sin promedio aún", "no average yet")
                left > 0 -> t("quedan ${duration(left)}", "${duration(left)} left")
                else -> t("sobre tu promedio", "over your average")
            },
            if (d.socialPenalty > 0) "−${d.socialPenalty}" else "0", chevron = true) { open(Sheet.Source(Kind.SOCIAL)) }
        // Se acredita a la noche: mientras tanto, apagado.
        Source(null, t("Desbloqueos", "Unlocks"),
            if (s.unlockHistory.size < HISTORY_DAYS) t("sin promedio · día ${s.historyCount + 1} de $HISTORY_DAYS", "no average · day ${s.historyCount + 1} of $HISTORY_DAYS")
            else t("${d.unlocks} · a la noche", "${d.unlocks} · tonight"),
            "+${unlockPoints(d.unlocks, s.unlockHistory)}", faint = true, chevron = true) { open(Sheet.Source(Kind.UNLOCKS)) }
        if (d.met) Source(Shield, t("Bonus de meta", "Goal bonus"), t("meta cumplida", "goal met"), "+${d.goalBonus}", chevron = true) { open(Sheet.Goal) }
        Spacer(Modifier.height(28.dp))
    }
}

/** Pasos y ejercicio sin Health Connect: fila apagada con la acción en acento. */
@Composable
private fun ConnectRow(action: String, onClick: () -> Unit) = Column {
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable(role = Role.Button, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(Track))
        Text(t("Pasos y ejercicio", "Steps and exercise"), color = Faint, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Text(action, color = Accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
}

/** Sesión en curso, en tinta. Con la app abierta solo se ve durante la gracia: abrirla después es desbloquear. */
@Composable
private fun ActiveSession(start: Long, minutes: Int, onCancel: () -> Unit) {
    val end = start + minutes * 60_000L
    Column(Modifier.padding(top = 24.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Sand).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(t("Sesión en curso · bloques ×2", "Session in progress · blocks ×2"), color = Bg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(duration(minutes), color = Accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        val f = ((System.currentTimeMillis() - start).toFloat() / (end - start)).coerceIn(0f, 1f)
        Box(Modifier.padding(top = 12.dp).fillMaxWidth().height(4.dp).clip(CircleShape).background(Body)) {
            Box(Modifier.fillMaxWidth(f).fillMaxHeight().clip(CircleShape).background(Accent))
        }
        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(t("Bloqueá el celu. Termina a las ${clock(end)}.", "Lock your phone. Ends at ${clock(end)}."), color = Bg, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text(t("Cancelar", "Cancel"), color = Bg, fontSize = 13.sp, modifier = Modifier.heightIn(min = 44.dp)
                .clickable(role = Role.Button, onClick = onCancel).padding(horizontal = 4.dp, vertical = 12.dp))
        }
    }
}

/** El total, protagonista, y el nivel con su avance. Tocarlo abre el nivel. */
@Composable
private fun Total(s: Snapshot, since: Int?, onClick: () -> Unit) {
    val shown by animateIntAsState(s.totalPoints, tween(600), label = "total")
    val progress by animateFloatAsState(s.levelProgress, tween(600), label = "level")
    Column(Modifier.padding(top = 24.dp).fillMaxWidth().clickable(onClickLabel = t("Ver nivel", "See level"), onClick = onClick)) {
        Text(s.nickname?.let { t("Hola, $it, tenés", "Hi $it, you have") } ?: t("Tenés", "You have"), color = Muted, fontSize = 17.sp)
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(thousands(shown), color = Sand, fontSize = if (shown < 1000) 132.sp else 104.sp, fontWeight = FontWeight.SemiBold,
                lineHeight = 132.sp, letterSpacing = (-7).sp)
            Column(Modifier.padding(bottom = 24.dp)) {
                Text(if (since != null && since > 0) "+$since" else "", color = Accent, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text(t("puntos", "points"), color = Muted, fontSize = 17.sp, fontWeight = FontWeight.Medium)
            }
        }
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(t("Nivel ${s.level}", "Level ${s.level}"), color = Sand, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(if (s.level == LEVEL_MAX) t("el máximo", "the top") else t("faltan ${thousands(s.toNextLevel)} para el ${s.level + 1}", "${thousands(s.toNextLevel)} to level ${s.level + 1}"), color = Muted, fontSize = 13.sp)
        }
        Box(Modifier.padding(top = 10.dp).fillMaxWidth().height(6.dp).clip(CircleShape).background(Track)) {
            Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).fillMaxHeight().clip(CircleShape).background(Sand))
        }
    }
}

/** La semana de lunes a domingo: cumplida, salvada con comodín, perdida o por venir. Hoy se llena hacia la meta. */
@Composable
private fun Week(s: Snapshot, onClick: () -> Unit) {
    val today = s.today.date
    val monday = weekOf(today)
    val install = s.days.firstOrNull()?.date ?: today
    val byDate = s.days.associateBy { it.date }
    val fill by animateFloatAsState((s.today.toGoal.toFloat() / s.today.goal).coerceIn(0f, 1f), tween(600), label = "hoy")
    Column(Modifier.padding(top = 32.dp).fillMaxWidth().clickable(onClickLabel = t("Ver racha", "See streak"), onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(t("Esta semana", "This week"), color = Sand, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(buildAnnotatedString {
                append(t("Racha ", "Streak "))
                withStyle(SpanStyle(color = Accent, fontWeight = FontWeight.SemiBold)) { append("${s.streak}") }
                append(if (s.freezeLeft) t(" · comodín disponible", " · free pass available") else t(" · comodín usado", " · free pass used"))
            }, color = Muted, fontSize = 13.sp)
        }
        Row(Modifier.padding(top = 10.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            t("LMMJVSD", "MTWTFSS").forEachIndexed { i, letter ->
                val date = monday.plusDays(i.toLong())
                val isToday = date == today
                val bg: Color = when {
                    date.isBefore(install) -> Line
                    !date.isBefore(today) -> Track
                    byDate[date]?.met == true -> Accent
                    date in s.frozen -> Shield
                    else -> Track
                }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(10.dp)).background(bg), contentAlignment = Alignment.BottomCenter) {
                        if (isToday) Box(Modifier.fillMaxWidth().fillMaxHeight(fill).background(Accent))
                    }
                    Text("$letter", color = if (isToday) Accent else Faint, fontSize = 12.sp,
                        fontWeight = if (isToday) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.padding(top = 6.dp))
                    Box(Modifier.padding(top = 2.dp).size(4.dp).clip(CircleShape).background(if (isToday) Accent else Color.Transparent))
                }
            }
        }
    }
}

/** Lo de hoy en una línea: cuánto falta, la meta cumplida o lo que se comieron las redes. */
@Composable
private fun TodayLine(d: Day, onClick: () -> Unit) {
    val (num, color, rest) = when {
        d.met -> Triple(t("Meta cumplida", "Goal met"), Accent, t(" · +${d.goalBonus} de bonus ›", " · +${d.goalBonus} bonus ›"))
        d.toGoal < 0 -> Triple("−${-d.toGoal}", Sand, t(" hoy · las redes se comieron lo ganado ›", " today · social media ate what you earned ›"))
        else -> Triple(signed(d.toGoal), Accent, t(" de ${d.goal} hoy · faltan ${d.goal - d.toGoal} para la meta ›", " of ${d.goal} today · ${d.goal - d.toGoal} to go ›"))
    }
    Text(buildAnnotatedString {
        withStyle(SpanStyle(color = color, fontWeight = FontWeight.SemiBold)) { append(num) }
        append(rest)
    }, color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp).fillMaxWidth().heightIn(min = 44.dp)
        .clickable(onClickLabel = t("Ver o cambiar la meta", "See or change the goal"), onClick = onClick).wrapContentHeight(Alignment.CenterVertically))
}
