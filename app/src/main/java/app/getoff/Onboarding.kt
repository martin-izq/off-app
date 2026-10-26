package app.getoff

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

// ---------- Onboarding ----------

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Onboarding(access: Boolean, onDone: (tab: Int) -> Unit) {
    val ctx = LocalContext.current
    val store = remember { Store(ctx) }
    // La última página, armar el grupo, aparece recién con el acceso al uso dado.
    val hasAccess by rememberUpdatedState(access)
    val pager = rememberPagerState { if (hasAccess) 4 else 3 }
    val scope = rememberCoroutineScope()
    var goal by remember { mutableIntStateOf(store.goalFor(LocalDate.now())) }
    var name by remember { mutableStateOf(store.nickname.orEmpty()) }
    var groupName by remember { mutableStateOf("") }
    var asked by remember { mutableStateOf(false) }
    var denied by remember { mutableStateOf(false) }
    // Si se instaló desde una invitación, la última página la muestra y al aceptar entra al grupo.
    var invited by remember { mutableStateOf(store.invite) }
    LaunchedEffect(Unit) { checkInvite(ctx) { invited = store.invite } }
    val toGroup: () -> Unit = { scope.launch { pager.animateScrollToPage(3) } }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { toGroup() }
    val askNotifications = { if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) else toGroup() }

    // Volvió de Ajustes sin dar el acceso: avisar.
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME && asked) denied = !hasUsageAccess(ctx)
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    // Al volver de Ajustes con el acceso dado: pedir notificaciones (opcional) y pasar al grupo.
    LaunchedEffect(access) {
        if (!access || pager.currentPage != 2) return@LaunchedEffect
        askNotifications()
    }
    /** Entra al grupo con los puntos de hoy ya guardados: lo ganado antes de entrar no va al reparto. */
    val enterGroup: (Group, invite: Boolean) -> Unit = { g, share ->
        scope.launch {
            withContext(Dispatchers.Default) { sync(ctx) }
            joinGroup(store, g)
            store.invite = null
            if (share) shareInvite(ctx, Invite(g, store.nickname.orEmpty()))
            onDone(1)
        }
    }
    val createGroup: () -> Unit = {
        if (store.nickname == null) cleanName(name).takeIf { it.isNotEmpty() }?.let { store.nickname = it }
        enterGroup(Group(newCode(), cleanName(groupName)), true)
    }
    val next: () -> Unit = {
        if (pager.currentPage == 1) cleanName(name).takeIf { it.isNotEmpty() }?.let { store.nickname = it }
        when {
            pager.currentPage < 2 -> scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
            pager.currentPage == 3 -> invited?.let { enterGroup(it.group, false) } ?: createGroup()
            access -> askNotifications()
            else -> {
                asked = true
                openUsageSettings(ctx)
            }
        }
    }
    val needsNick = store.nickname == null && cleanName(name).isEmpty()

    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp)) {
        Row(Modifier.fillMaxWidth().height(44.dp), horizontalArrangement = Arrangement.End) {
            val skip: (() -> Unit)? = when (pager.currentPage) {
                0 -> ({ scope.launch { pager.animateScrollToPage(1) } })
                3 -> if (invited == null) ({ onDone(0) }) else null
                else -> null
            }
            skip?.let {
                Text(t("Saltar", "Skip"), color = Muted, fontSize = 15.sp, modifier = Modifier.clickable(onClick = it).padding(12.dp))
            }
        }
        HorizontalPager(pager, Modifier.weight(1f)) { page ->
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp), verticalArrangement = Arrangement.Bottom) {
                when (page) {
                    0 -> {
                        Text(buildAnnotatedString {
                            append("off")
                            withStyle(SpanStyle(color = Accent)) { append(".") }
                        }, color = Sand, fontSize = 176.sp, lineHeight = 150.sp, fontWeight = FontWeight.Bold, letterSpacing = (-11).sp,
                            modifier = Modifier.padding(bottom = 44.dp).semantics { contentDescription = "off." })
                        Text(buildAnnotatedString {
                            append(t("La vida pasa off", "Life happens off"))
                            withStyle(SpanStyle(color = Accent)) { append(".") }
                        }, color = Sand, fontSize = 44.sp, lineHeight = 45.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-1.5).sp)
                        Spacer(Modifier.height(16.dp))
                        Para(t("Soltá el celu y sumá puntos. Con amigos es mejor.", "Put your phone down and earn points. Better with friends."))
                    }
                    1 -> {
                        Headline(t("Hola. ¿Cómo te llamás?", "Hi. What's your name?"))
                        Text(t("Tu nombre", "Your name"), color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 28.dp))
                        UnderlinedField(name, t("Tu nombre", "Your name")) { name = it }
                        Text(t("Meta diaria", "Daily goal"), color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 40.dp))
                        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            // Cada paso: 30 min bloqueado = 1 punto de meta.
                            StepButton("−", t("Menos tiempo", "Less time")) { goal = (goal - 1).coerceIn(GOAL_MIN, GOAL_MAX); store.setInitialGoal(goal) }
                            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(hours(goal), color = Sand, fontSize = 40.sp, lineHeight = 40.sp, fontWeight = FontWeight.SemiBold,
                                    letterSpacing = (-1.5).sp, maxLines = 1)
                                Text(t("bloqueado · $goal puntos por día", "locked · $goal points a day"), color = Muted, fontSize = 13.sp,
                                    modifier = Modifier.padding(top = 6.dp))
                            }
                            StepButton("+", t("Más tiempo", "More time")) { goal = (goal + 1).coerceIn(GOAL_MIN, GOAL_MAX); store.setInitialGoal(goal) }
                        }
                        Text(t("Cumplila y sumás bonus y racha.", "Hit it to earn a bonus and keep your streak."), color = Muted, fontSize = 14.sp,
                            lineHeight = 20.sp, modifier = Modifier.padding(top = 16.dp))
                    }
                    2 -> {
                        Headline(t("Lo justo y necesario.", "Only what's needed."))
                        Spacer(Modifier.height(16.dp))
                        Para(t("Solo el acceso al uso es obligatorio.", "Only usage access is required."))
                        if (denied && !access) Text(t("Todavía no está activado. Buscá «off.» en la lista de Android y prendé el permiso.",
                            "It's not on yet. Find “off.” in Android's list and turn the permission on."),
                            color = ErrFg, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.padding(top = 20.dp).fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp)).background(ErrBg).padding(horizontal = 14.dp, vertical = 12.dp))
                        Spacer(Modifier.height(28.dp))
                        PermissionRow(t("Acceso al uso", "Usage access"),
                            if (denied && !access) t("sin activar", "not on") else t("necesario", "required"), filled = true)
                        PermissionRow(t("Notificaciones", "Notifications"), t("opcional", "optional"))
                        PermissionRow(t("Pasos y ejercicio", "Steps and exercise"), t("después", "later"), last = true)
                    }
                    else -> invited?.let { inv ->
                        val g = inv.group.name
                        Headline(if (inv.host.isEmpty()) t("Te invitaron a «$g».", "You're invited to “$g”.")
                            else t("${inv.host} te invitó a «$g».", "${inv.host} invited you to “$g”."))
                        Spacer(Modifier.height(16.dp))
                        Para(t("Entrás al grupo y, cuando se junten, escaneás su código y empiezan a sumar juntos.",
                            "Join the group, and when you meet up, scan their code and start scoring together."))
                    } ?: run {
                        Headline(t("Armá tu grupo.", "Start your group."))
                        Spacer(Modifier.height(16.dp))
                        Para(t("Off se juega mejor con alguien. Si alguien se pasa de redes, esos puntos van al pozo, y el pozo se salda en persona.",
                            "Off is better with someone. Go over on social media and those points go into the pot. The pot is settled in person."))
                        Spacer(Modifier.height(20.dp))
                        if (store.nickname == null) {
                            Text(t("Tu nombre", "Your name"), color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                            UnderlinedField(name, t("Tu nombre", "Your name")) { name = it }
                        }
                        Text(t("Nombre del grupo", "Group name"), color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 20.dp))
                        UnderlinedField(groupName, t("Nombre del grupo", "Group name")) { groupName = it }
                        Text(t("Después elegís a quién mandarle la invitación: WhatsApp, lo que uses.",
                            "Next you pick who gets the invite: WhatsApp, whatever you use."),
                            color = Muted, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.padding(top = 16.dp))
                    }
                }
            }
        }
        Row(Modifier.padding(bottom = 20.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(pager.pageCount) { i ->
                val w by animateDpAsState(if (i == pager.currentPage) 24.dp else 6.dp, label = "dot")
                Box(Modifier.size(w, 6.dp).clip(CircleShape).background(if (i == pager.currentPage) Sand else Dim))
            }
        }
        PrimaryButton(when {
            pager.currentPage == 0 -> t("Empezar", "Start")
            pager.currentPage == 1 -> t("Siguiente", "Next")
            pager.currentPage == 3 && invited != null -> t("Entrar", "Let's go")
            pager.currentPage == 3 -> t("Crear e invitar", "Create and invite")
            denied && !access -> t("Abrir Ajustes de nuevo", "Open Settings again")
            else -> t("Dar acceso", "Grant access")
        }, enabled = pager.currentPage != 3 || invited != null || (cleanName(groupName).isNotEmpty() && !needsNick), onClick = next)
    }
}

@Composable
private fun Headline(text: String, size: Int = 36) =
    Text(text, color = Sand, fontSize = size.sp, lineHeight = (size * 1.05).sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-1).sp)

/** Campo de texto con una raya abajo, sin caja. */
@Composable
private fun UnderlinedField(value: String, label: String, onChange: (String) -> Unit) {
    val ink = Sand
    BasicTextField(
        value, { onChange(it.take(20)) },
        Modifier.padding(top = 6.dp).fillMaxWidth().semantics { contentDescription = label }
            .drawBehind { drawLine(ink, Offset(0f, size.height), Offset(size.width, size.height), 2.dp.toPx()) },
        textStyle = LocalTextStyle.current.merge(TextStyle(color = ink, fontSize = 28.sp, fontWeight = FontWeight.Medium)),
        singleLine = true,
        cursorBrush = SolidColor(Accent),
        decorationBox = { inner -> Box(Modifier.heightIn(min = 56.dp), contentAlignment = Alignment.CenterStart) { inner() } },
    )
}

@Composable
private fun StepButton(symbol: String, label: String, onClick: () -> Unit) = Box(
    Modifier.size(52.dp).clip(CircleShape).background(Card).border(1.dp, Line, CircleShape)
        .clickable(onClickLabel = label, onClick = onClick),
    contentAlignment = Alignment.Center,
) { Text(symbol, color = Sand, fontSize = 24.sp) }

@Composable
private fun PermissionRow(label: String, state: String, filled: Boolean = false, last: Boolean = false) = Column {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).then(if (filled) Modifier.background(Accent) else Modifier.border(1.5.dp, Faint, CircleShape)))
        Text(label, color = Sand, fontSize = 15.sp, modifier = Modifier.weight(1f).padding(start = 12.dp))
        Text(state, color = Muted, fontSize = 13.sp)
    }
    if (last) Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
}

@Composable
internal fun Revoked(onFix: () -> Unit) {
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp)) {
        Spacer(Modifier.weight(1f))
        Headline(t("Perdimos el acceso al uso.", "We lost usage access."), 40)
        Spacer(Modifier.height(16.dp))
        Para(t("Sin ese permiso no sumamos puntos. Lo que ya ganaste queda guardado.",
            "Without it we can't count points. What you already earned is safe."))
        Spacer(Modifier.height(40.dp))
        PrimaryButton(t("Volver a dar acceso", "Grant access again"), onClick = onFix)
    }
}

/** Lo que Health Connect muestra al preguntar para qué queremos los datos. */
@Composable
internal fun HealthRationale() = Column(Modifier.fillMaxSize().background(Bg).safeDrawingPadding().padding(24.dp)) {
    Title(t("Para qué leemos tu actividad", "Why we read your activity"), 36)
    Para(t("Leemos tus pasos y tus sesiones de ejercicio para sumarte puntos: los pasos solo cuentan si diste con " +
        "el celu bloqueado, y el ejercicio cargado a mano no cuenta.",
        "We read your steps and workouts to give you points: steps only count while your phone is locked, " +
        "and workouts entered by hand don't count."))
    Spacer(Modifier.height(16.dp))
    Para(t("Todo queda en tu celu. Sin cuenta, sin servidor y sin publicidad. No escribimos nada en Health Connect.",
        "Everything stays on your phone. No account, no server, no ads. We never write to Health Connect."))
}
