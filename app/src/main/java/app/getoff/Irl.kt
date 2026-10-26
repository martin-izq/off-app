package app.getoff

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId

// ---------- IRL ----------

/** En qué paso del encuentro está este celu. */
internal sealed interface Step {
    data object Home : Step
    data object NewGroup : Step
    /** Antes de arrancar: ¿armo yo o me sumo? `start`: es para empezar una juntada. */
    data class Choose(val start: Boolean) : Step
    /** Anfitrión: muestra la sala y va escaneando a los demás. */
    data class Host(val sala: Sala, val me: Member, val others: List<Member> = emptyList()) : Step
    /** Anfitrión: muestra el código final. `me` null: es uno ya saldado que se muestra de nuevo. */
    data class HostFinal(val final: Final, val me: Member?) : Step
    /** Invitado: muestra su código y espera el final. */
    data class Guest(val nonce: String, val host: String, val me: Member) : Step
    /** Resultado de saldar: el código final si salió bien, o el error. */
    data class Done(val final: Final?, val error: String?) : Step
}

internal fun minuteNow() = System.currentTimeMillis() / 60_000 * 60_000
internal fun newCode() = java.util.UUID.randomUUID().toString().take(8)

private fun times(n: Int) = t(if (n == 1) "1 vez" else "$n veces", if (n == 1) "once" else "$n times")
private val OLD_CODE get() = t("Ese código es de otra versión de Off. Actualicen la app y vuelvan a escanear.", "That code is from another version of Off. Update the app and scan again.")
private val NOT_OFF get() = t("Ese código no es de off.", "That code isn't from off.")
private val OTHER get() = t("Ese código es de otro encuentro.", "That code is from another meetup.")
private val OWN get() = t("Ese es tu propio código.", "That's your own code.")
private fun notFinal(host: String) = t("Ese no es el código final. Esperá a que $host lo muestre.", "That's not the final code. Wait for $host to show it.")

private fun Long.date() = Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).toLocalDate()

@Composable
internal fun Ronda(s: Snapshot, open: (Sheet) -> Unit, onChanged: () -> Unit) {
    val ctx = LocalContext.current
    val store = remember { Store(ctx) }
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf<Step>(Step.Home) }
    // Grupo abierto. Null: la lista de grupos.
    var selected by remember { mutableStateOf<String?>(null) }
    val group = s.groups.firstOrNull { it.id == selected }
    // Por qué falló el último escaneo. Se borra al cambiar de paso.
    var notice by remember { mutableStateOf<String?>(null) }
    fun go(next: Step) { step = next; notice = null }
    fun member(gid: String, from: Long, to: Long, then: (Member) -> Unit) = scope.launch {
        then(withContext(Dispatchers.Default) { myMember(ctx, store, gid, from, to) })
    }
    fun finish(f: Final, me: Member) {
        selected = f.ronda
        val error = applyFinal(store, f, me)
        go(Step.Done(f.takeIf { error == null }, error))
        onChanged()
    }
    /** Un código de quien arma: si el grupo es nuevo para este celu, entra a él. */
    fun enter(sala: Sala) {
        joinGroup(store, Group(sala.ronda, sala.group.ifEmpty { t("Grupo de ${sala.host.ifEmpty { "amigos" }}", "${sala.host.ifEmpty { "Friends" }}'s group") }))
        selected = sala.ronda
    }
    fun onScanned(raw: String) {
        val code = decode(raw)
        if (code == null && otherVersion(raw)) {
            notice = OLD_CODE
            return
        }
        notice = when (val st = step) {
            is Step.Host -> when {
                code == null -> NOT_OFF
                code is Mine && code.nonce != st.sala.nonce -> OTHER
                code is Mine && code.member.id == st.me.id -> OWN
                code is Mine -> { step = st.copy(others = st.others.filter { it.id != code.member.id } + code.member); null }
                code is Sala && code.nonce == st.sala.nonce -> OWN
                else -> OTHER
            }
            is Step.Guest -> when {
                code is Final && code.nonce == st.nonce -> { finish(code, st.me); null }
                code == null -> NOT_OFF
                code is Final -> OTHER
                else -> notFinal(st.host)
            }
            else -> when (code) {
                null -> NOT_OFF
                is Sala -> {
                    enter(code)
                    if (code.start) {
                        store.group(code.ronda).juntada = code.from to code.nonce
                        go(Step.Home)
                    } else member(code.ronda, code.from, code.to) {
                        val me = normalized(code.nonce, it)
                        val host = code.host.ifEmpty { t("quien arma", "the host") }
                        // Queda guardado por si se cierra la app antes del código final.
                        store.group(code.ronda).pending = Pending(Mine(code.nonce, me), host, System.currentTimeMillis())
                        go(Step.Guest(code.nonce, host, me))
                        onChanged()
                    }
                    onChanged()
                    null
                }
                is Final -> s.groups.firstNotNullOfOrNull { g -> g.pending?.takeIf { it.mine.nonce == code.nonce } }
                    ?.let { finish(code, it.mine.member); null } ?: OTHER
                else -> t("Ese código es de otro paso. Primero escaneá el código de quien arma.", "That code is for another step. First scan the host's code.")
            }
        }
    }
    val zxing = rememberLauncherForActivityResult(ScanContract()) { r -> r.contents?.let(::onScanned) }
    // El lector de Google (sin permiso de cámara). Sin Play Services o sin el módulo descargado falla y se usa zxing.
    fun launchScan() {
        val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).enableAutoZoom().build()
        GmsBarcodeScanning.getClient(ctx, options).startScan()
            .addOnSuccessListener { b -> b.rawValue?.let(::onScanned) }
            .addOnFailureListener {
                zxing.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt("").setBeepEnabled(false).setOrientationLocked(false))
            }
    }
    var cancelJuntada by remember { mutableStateOf(false) }
    var leave by remember { mutableStateOf(false) }
    val me = s.nickname.orEmpty()
    fun create(nick: String, name: String) {
        if (s.nickname == null) store.nickname = cleanName(nick)
        val g = Group(newCode(), cleanName(name))
        joinGroup(store, g)
        selected = g.id
        go(Step.Home)
        onChanged()
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
        when (val st = step) {
            Step.Home -> when {
                group == null && s.groups.isEmpty() -> IrlEmpty(s, open, notice, onJoin = ::launchScan, onCreate = ::create)
                group == null -> IrlGroups(s, open, notice, onOpen = { selected = it }, onNew = { go(Step.NewGroup) }, onJoin = ::launchScan)
                group.juntadaFrom != null -> Juntada(group, group.juntadaFrom,
                    onBack = { selected = null }, onCancel = { cancelJuntada = true }, onFinish = { go(Step.Choose(start = false)) })
                else -> RondaHome(s, group, open, notice,
                    onBack = { selected = null },
                    onMeet = { go(Step.Choose(start = false)) },
                    onStart = { go(Step.Choose(start = true)) },
                    onShowLast = { f -> go(Step.HostFinal(f, null)) },
                    onScanPending = { p -> go(Step.Guest(p.mine.nonce, p.host, p.mine.member)); launchScan() },
                    onInvite = { shareInvite(ctx, Invite(Group(group.id, group.name), me)) },
                    onLeave = { leave = true })
            }
            Step.NewGroup -> NewGroup(onBack = { go(Step.Home) }) { create(me, it) }
            is Step.Choose -> RondaChoose(st.start, group?.name ?: "IRL", notice, onBack = { go(Step.Home) }, onJoin = ::launchScan) {
                val g = group ?: return@RondaChoose go(Step.Home)
                if (st.start) {
                    val sala = Sala(true, newCode(), g.id, minuteNow(), minuteNow(), me, g.name)
                    store.group(g.id).juntada = sala.from to sala.nonce
                    go(Step.Host(sala, Member(store.myId, me, 0, 0)))
                    onChanged()
                } else {
                    // Arranca otro encuentro: el que quedó sin terminar ya no se va a saldar.
                    store.group(g.id).pending = null
                    val from = g.juntadaFrom ?: 0L
                    val now = minuteNow()
                    member(g.id, from, now) {
                        val sala = Sala(false, newCode(), g.id, from, now, me, g.name)
                        // Como lo ven los demás al leer el código final: si no, applyFinal no reconoce mis datos.
                        go(Step.Host(sala, normalized(sala.nonce, it)))
                    }
                    onChanged()
                }
            }
            is Step.Host -> if (st.sala.start) QrStep(
                null, t("Que todos escaneen tu código", "Have everyone scan your code"),
                t("Así la juntada arranca a la misma hora para todos. Después, celus bloqueados.", "That way it starts at the same time for everyone. Then, phones locked."),
                encode(st.sala), notice, onCancel = { go(Step.Home) },
            ) {
                PrimaryButton(t("Ya lo escanearon todos", "Everyone scanned it")) { go(Step.Home) }
            } else QrStep(
                if (st.others.isEmpty()) t("Paso 1 de 3", "Step 1 of 3") else t("Paso 2 de 3", "Step 2 of 3"),
                if (st.others.isEmpty()) t("Que todos escaneen tu código", "Have everyone scan your code") else t("Escaneá el código de cada uno", "Scan everyone's code"),
                null, encode(st.sala), notice, onCancel = { go(Step.Home) },
            ) {
                Text(if (st.others.isEmpty()) t("Todavía no escaneaste a nadie", "You haven't scanned anyone yet") else t("Ya escaneaste", "Scanned"), color = Muted, fontSize = 13.sp)
                Row(Modifier.padding(top = 8.dp, bottom = 16.dp).heightIn(min = 34.dp).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    st.others.forEach {
                        Text(it.name, color = Sand, fontSize = 14.sp, modifier = Modifier.clip(RoundedCornerShape(18.dp)).background(Card)
                            .padding(horizontal = 14.dp, vertical = 8.dp))
                    }
                }
                PrimaryButton(if (st.others.isEmpty()) t("Escanear el código de alguien", "Scan someone's code") else t("Escanear a otro", "Scan another")) { launchScan() }
                if (st.others.isEmpty()) Text(t("Escaneá al menos a una persona para saldar.", "Scan at least one person to settle up."), color = Faint, fontSize = 15.sp,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp))
                else TextAction(t("Ya están todos: mostrar el código final", "That's everyone: show the final code")) {
                    go(Step.HostFinal(Final(st.sala.nonce, st.sala.ronda, st.sala.from, st.sala.to, listOf(st.me) + st.others), st.me))
                }
            }
            is Step.HostFinal -> QrStep(if (st.me != null) t("Paso 3 de 3", "Step 3 of 3") else null, t("Que todos escaneen el código final", "Have everyone scan the final code"),
                if (st.me != null) t("Con este código cada celu calcula el reparto y llega al mismo resultado.", "With this code every phone works out the split and gets the same result.")
                else t("Es el del último encuentro. Quien se fue antes lo escanea y le queda saldado igual.", "It's from the last meetup. Whoever left early scans it and is settled too."),
                encode(st.final), null, onCancel = { go(Step.Home) }) {
                if (st.me != null) PrimaryButton(t("Todos lo escanearon", "Everyone scanned it")) { finish(st.final, st.me) }
                else PrimaryButton(t("Listo", "Done")) { go(Step.Home) }
            }
            is Step.Guest -> QrStep(t("Paso 2 de 3", "Step 2 of 3"), t("Mostrale tu código a ${st.host}", "Show your code to ${st.host}"), group?.let { t("Entraste a «${it.name}».", "You joined “${it.name}”.") },
                encode(Mine(st.nonce, st.me)), notice, onCancel = { go(Step.Home) }) {
                PrimaryButton(t("Escanear el código final", "Scan the final code")) { launchScan() }
            }
            is Step.Done -> RondaDone(st, s.myId, group?.name ?: "IRL",
                onOk = { go(Step.Home) }, onRetry = { go(Step.Choose(start = false)) })
        }
    }
    if (cancelJuntada) IrlConfirm(t("¿Cancelar la juntada?", "Cancel the hangout?"),
        t("Se cancela en tu celu. Si otro la sigue y después reparte, cuenta igual para todos los que escaneen.",
            "It's cancelled on your phone. If someone else keeps it going and settles later, it still counts for everyone who scans."),
        t("Cancelar juntada", "Cancel hangout"), t("Seguir", "Keep going"),
        onYes = { group?.let { store.group(it.id).juntada = null }; cancelJuntada = false; onChanged() }, onNo = { cancelJuntada = false })
    if (leave) IrlConfirm(t("¿Salir de «${group?.name ?: "este grupo"}»?", "Leave “${group?.name ?: "this group"}”?"),
        t("Se borran el pozo y los encuentros del grupo en este celu. Tus puntos y tu nombre quedan.",
            "The group's pot and meetups are deleted from this phone. Your points and name stay."),
        t("Salir", "Leave"), t("Cancelar", "Cancel"),
        onYes = { group?.let { store.leaveGroup(it.id) }; selected = null; leave = false; onChanged() }, onNo = { leave = false })
}

// ---------- Piezas ----------

/** Título fuerte arriba a la izquierda, con algo chico a la derecha. */
@Composable
private fun TopBar(title: String, right: @Composable () -> Unit = {}) =
    Row(Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = Sand, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        right()
    }

@Composable
private fun Back(text: String, onClick: () -> Unit) = Text(text, color = Muted, fontSize = 15.sp,
    modifier = Modifier.heightIn(min = 44.dp).clickable(role = Role.Button, onClick = onClick).padding(vertical = 12.dp))

/** Aviso o error: caja suave con texto en el color de error. */
@Composable
private fun Notice(text: String, top: Dp = 16.dp, content: @Composable ColumnScope.() -> Unit = {}) = Column(
    Modifier.padding(top = top).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(ErrBg).padding(horizontal = 14.dp, vertical = 12.dp),
) {
    Text(text, color = ErrFg, fontSize = 14.sp, lineHeight = 20.sp)
    content()
}

@Composable
private fun InfoBox(text: String) = Text(text, color = Body, fontSize = 14.sp, lineHeight = 20.sp,
    modifier = Modifier.padding(top = 16.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Card).padding(horizontal = 14.dp, vertical = 12.dp))

/** Número grande con su unidad al lado. */
@Composable
private fun BigNumber(n: String, unit: String, color: Color = Sand, size: Int = 96) =
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(n, color = color, fontSize = size.sp, lineHeight = size.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-size / 20).sp)
        Text(unit, color = Muted, fontSize = 17.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(bottom = (size / 7).dp))
    }

/** Fila de dos líneas con valor a la derecha. */
@Composable
private fun Row2(
    title: String, sub: String, subColor: Color, value: String, valueColor: Color = Sand,
    bold: Boolean = false, onClick: (() -> Unit)? = null,
) = Column {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Sand, fontSize = 15.sp, fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal)
            if (sub.isNotEmpty()) Text(sub, color = subColor, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
        }
        if (value.isNotEmpty()) Text(value, color = valueColor, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        if (onClick != null) Text("›", color = Faint, fontSize = 16.sp)
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
}

@Composable
private fun IrlConfirm(title: String, body: String, yes: String, no: String, onYes: () -> Unit, onNo: () -> Unit) = AlertDialog(
    onDismissRequest = onNo,
    containerColor = SheetBg,
    title = { Text(title, color = Sand, fontWeight = FontWeight.SemiBold) },
    text = { Text(body, color = Body) },
    confirmButton = { TextButton(onYes) { Text(yes, color = Sand, fontWeight = FontWeight.SemiBold) } },
    dismissButton = { TextButton(onNo) { Text(no, color = Muted) } },
)

/** Lo que me toca en un encuentro. */
private fun Final.mine(me: String) = outcome(this).firstOrNull { it.id == me }

// ---------- Pantallas ----------

/** Sin grupos: crear el primero o entrar al de otro. */
@Composable
private fun IrlEmpty(s: Snapshot, open: (Sheet) -> Unit, notice: String?, onJoin: () -> Unit, onCreate: (nick: String, name: String) -> Unit) {
    var nick by remember { mutableStateOf(s.nickname.orEmpty()) }
    var name by remember { mutableStateOf("") }
    TopBar("IRL") { TextLink(t("Cómo funciona", "How it works")) { open(Sheet.RondaRules) } }
    Row(Modifier.padding(top = 24.dp)) {
        Text(t("Juntá\na tu gente", "Get your\npeople together"), color = Sand, fontSize = 44.sp, lineHeight = 46.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-1.5).sp)
        Text(".", color = Accent, fontSize = 44.sp, lineHeight = 46.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.align(Alignment.Bottom))
    }
    Text(t("Si alguien se pasa de redes, esos puntos van al pozo del grupo. Se reparte solo cuando se juntan en persona.",
        "Go over on social media and those points go into the group pot. It's only split when you meet in person."),
        color = Body, fontSize = 15.sp, lineHeight = 22.sp, modifier = Modifier.padding(top = 12.dp, bottom = 28.dp))
    // Con nombre ya elegido no se pregunta de nuevo: se cambia en Ajustes.
    if (s.nickname == null) {
        NameField(nick) { nick = it }
        Spacer(Modifier.height(12.dp))
    }
    NameField(name, t("Nombre del grupo", "Group name")) { name = it }
    Spacer(Modifier.height(32.dp))
    PrimaryButton(t("Crear grupo", "Create group"), enabled = cleanName(name).isNotEmpty() && cleanName(nick).isNotEmpty()) { onCreate(nick, name) }
    TextAction(t("Me invitaron: escanear un código", "I was invited: scan a code"), onJoin)
    notice?.let { Notice(it, top = 4.dp) }
    Spacer(Modifier.height(12.dp))
}

/** La pestaña IRL sin grupo abierto: tus grupos, crear uno o sumarte a un encuentro. */
@Composable
private fun IrlGroups(s: Snapshot, open: (Sheet) -> Unit, notice: String?, onOpen: (String) -> Unit, onNew: () -> Unit, onJoin: () -> Unit) {
    TopBar("IRL") { TextLink(t("Cómo funciona", "How it works")) { open(Sheet.RondaRules) } }
    Spacer(Modifier.height(24.dp))
    BigNumber("${s.rondaLedger.sumOf { it.amount }}", t("ganados en encuentros", "won at meetups"))
    Spacer(Modifier.height(20.dp))
    Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
    s.groups.forEach { g ->
        val last = g.meetings.firstOrNull()
        val (sub, subColor) = when {
            g.juntadaFrom != null -> t("juntada en curso", "hangout in progress") to Accent
            g.pending != null -> t("falta escanear el código final", "final code not scanned yet") to ErrFg
            last != null -> t("${last.members.size} personas · último encuentro ${ago(last.to)}", "${last.members.size} people · last meetup ${ago(last.to)}") to Muted
            else -> t("todavía sin encuentros", "no meetups yet") to Muted
        }
        val debt = g.sinceSettle.second
        val delta = last?.mine(s.myId)?.delta
        Row2(g.name, sub, subColor,
            if (debt > 0) t("debés $debt", "you owe $debt") else delta?.let(::signed).orEmpty(),
            if (debt <= 0 && (delta ?: 0) > 0) Accent else Sand, bold = true) { onOpen(g.id) }
    }
    Spacer(Modifier.height(32.dp))
    PrimaryButton(t("Nuevo grupo", "New group"), onClick = onNew)
    // Para entrar al grupo de otro: escanear su código en el encuentro.
    TextAction(t("Me sumo a un encuentro", "Join a meetup"), onJoin)
    notice?.let { Notice(it, top = 4.dp) }
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun NewGroup(onBack: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    val ok = cleanName(name).isNotEmpty()
    Back("‹ IRL", onBack)
    Text(t("Nuevo grupo", "New group"), color = Sand, fontSize = 36.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-1).sp,
        modifier = Modifier.padding(top = 24.dp, bottom = 28.dp))
    NameField(name, t("Nombre del grupo", "Group name")) { name = it }
    Text("${name.length}/20", color = Faint, fontSize = 13.sp, textAlign = TextAlign.End, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
    Text(t("Invitalos con un link o que escaneen tu código en el primer encuentro. Nadie necesita cuenta.",
        "Invite them with a link, or they scan your code at the first meetup. Nobody needs an account."),
        color = Muted, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.padding(top = 12.dp))
    Spacer(Modifier.height(40.dp))
    PrimaryButton(t("Crear grupo", "Create group"), enabled = ok) { onCreate(name) }
    Text(if (ok) "" else t("Ponele un nombre al grupo.", "Give the group a name."), color = Faint, fontSize = 13.sp, textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = 18.dp))
}

@Composable
private fun RondaHome(
    s: Snapshot, g: GroupView, open: (Sheet) -> Unit, notice: String?,
    onBack: () -> Unit, onMeet: () -> Unit, onStart: () -> Unit, onShowLast: (Final) -> Unit,
    onScanPending: (Pending) -> Unit, onInvite: () -> Unit, onLeave: () -> Unit,
) {
    val (earned, debt) = g.sinceSettle
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) { Back("‹ IRL", onBack) }
        TextLink(t("Invitar", "Invite"), onInvite)
        TextLink(t("Cómo funciona", "How it works")) { open(Sheet.RondaRules) }
    }
    Text(g.name, color = Sand, fontSize = 28.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.8).sp, modifier = Modifier.padding(top = 8.dp))
    g.pending?.let { p ->
        Notice(t("Te quedó sin terminar el encuentro del ${p.at.date().label("EEEE").lowercase()}: falta que escanees el código final. " +
            "Pedíselo a ${p.host}: lo puede mostrar de nuevo desde el grupo.",
            "Your ${p.at.date().label("EEEE")} meetup isn't finished: you still need to scan the final code. " +
            "Ask ${p.host}: they can show it again from the group.")) {
            Text(t("Escanear el código final", "Scan the final code"), color = Bg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 10.dp).heightIn(min = 40.dp).clip(CircleShape).background(Sand)
                    .clickable(role = Role.Button) { onScanPending(p) }.padding(horizontal = 16.dp, vertical = 11.dp))
        }
    }
    notice?.let { Notice(it) }
    Text(t("Desde el último encuentro", "Since the last meetup"), color = Muted, fontSize = 17.sp, modifier = Modifier.padding(top = 20.dp))
    BigNumber("$earned", t("ganados", "earned"))
    Text(if (debt > 0) t("debés $debt al pozo", "you owe $debt to the pot") else t("no debés nada", "you owe nothing"), color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
    // El último encuentro: lo único del grupo que este celu sabe de los demás.
    g.meetings.firstOrNull()?.let { last ->
        Spacer(Modifier.height(24.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
        outcome(last).sortedByDescending { it.delta }.forEachIndexed { i, o ->
            val mine = o.id == s.myId
            Column {
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}", color = Faint, fontSize = 13.sp, modifier = Modifier.width(20.dp))
                    Text(if (mine) t("Vos", "You") else o.name, color = Sand, fontSize = 15.sp,
                        fontWeight = if (mine) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.weight(1f))
                    Text(why(o), color = Muted, fontSize = 13.sp)
                    Text(signed(o.delta), color = if (o.delta > 0) Accent else Muted, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.End, modifier = Modifier.width(48.dp))
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
            }
        }
    }
    if (g.meetings.isNotEmpty()) {
        Text(t("Encuentros", "Meetups"), color = Faint, fontSize = 13.sp, modifier = Modifier.padding(top = 20.dp, bottom = 4.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
        g.meetings.take(5).forEach { f ->
            val mine = f.mine(s.myId)
            val detail = t("${f.members.size} personas", "${f.members.size} people") + when {
                (mine?.fine ?: 0) > 0 -> t(" · desbloqueaste ${times(mine!!.fine / UNLOCK_FINE)}", " · you unlocked ${times(mine!!.fine / UNLOCK_FINE)}")
                f.from > 0 -> t(" · juntada de ${duration(((f.to - f.from) / 60_000).toInt())}", " · ${duration(((f.to - f.from) / 60_000).toInt())} hangout")
                else -> ""
            }
            Row2(f.to.date().label("EEEE d"), detail, Muted, mine?.let { signed(it.delta) }.orEmpty(),
                if ((mine?.delta ?: 0) > 0) Accent else Sand) { open(Sheet.Meeting(f)) }
        }
        TextLink(t("Mostrar el código final del último ›", "Show the last final code ›")) { onShowLast(g.meetings.first()) }
    }
    Spacer(Modifier.height(32.dp))
    PrimaryButton(t("Nos juntamos", "We're meeting up"), onClick = onMeet)
    Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Box(Modifier.weight(1f)) { TextAction(t("Empezar juntada", "Start hangout"), onStart) }
        Box(Modifier.weight(1f)) { TextAction(t("Salir del grupo", "Leave group"), onLeave) }
    }
    Spacer(Modifier.height(12.dp))
}

/** Juntada en curso: el tiempo juntos y mis bloqueos. Lo de los demás se sabe recién al saldar. */
@Composable
private fun Juntada(g: GroupView, from: Long, onBack: () -> Unit, onCancel: () -> Unit, onFinish: () -> Unit) {
    val ctx = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var locks by remember { mutableStateOf<List<LongRange>>(emptyList()) }
    LaunchedEffect(from) {
        while (true) {
            now = System.currentTimeMillis()
            locks = withContext(Dispatchers.Default) { lockedBetween(ctx, from, now) }
            delay(30_000)
        }
    }
    val min = ((now - from) / 60_000).toInt().coerceAtLeast(0)
    val segs = lockSegments(locks).ifEmpty { listOf(0f) }
    Row(Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(t("‹ ${g.name} · juntada", "‹ ${g.name} · hangout"), color = Sand, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f).clickable(role = Role.Button, onClick = onBack).padding(vertical = 12.dp))
        TextLink(t("Cancelar", "Cancel"), onCancel)
    }
    Text(t("Llevan juntos", "Together for"), color = Muted, fontSize = 17.sp, modifier = Modifier.padding(top = 24.dp))
    Text("${min / 60}:${"%02d".format(min % 60)}", color = Sand, fontSize = 96.sp, lineHeight = 96.sp, fontWeight = FontWeight.SemiBold,
        letterSpacing = (-4).sp)
    Text(t("Tus bloques de 30 min", "Your 30 min blocks"), color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
    segs.chunked(4).forEach { row ->
        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            (0 until 4).forEach { i ->
                Box(Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(10.dp)).background(if (i < row.size) Line else Color.Transparent)) {
                    row.getOrNull(i)?.takeIf { it > 0f }?.let {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(it).background(if (it >= 1f) Accent else MossLight))
                    }
                }
            }
        }
    }
    val full = segs.count { it >= 1f }
    Text(t("$full ${if (full == 1) "completo" else "completos"} · si todos aguantaron, valen doble", "$full complete · if everyone held out, they count double"), color = Muted, fontSize = 13.sp,
        modifier = Modifier.padding(top = 2.dp))
    // Si estoy mirando esto, desbloqueé: si antes había bloqueado durante la juntada, ese desbloqueo paga.
    if (locks.isNotEmpty()) Notice(t("Abriste el celu: cada desbloqueo pone $UNLOCK_FINE en el pozo. Se cobra al saldar.", "You opened your phone: every unlock puts $UNLOCK_FINE into the pot. It's charged when you settle."))
    InfoBox(t("Al repartir se cruzan los bloqueos de todos: ahí se ve si aguantaron y cuántas veces desbloqueó cada uno.", "When you settle, everyone's locked time is compared: that shows who held out and how many times each one unlocked."))
    Spacer(Modifier.height(32.dp))
    PrimaryButton(t("Terminar y repartir", "Finish and settle"), onClick = onFinish)
    Spacer(Modifier.height(12.dp))
}

/** Antes de cualquier encuentro: uno arma y los demás se suman. */
@Composable
private fun RondaChoose(start: Boolean, groupName: String, notice: String?, onBack: () -> Unit, onJoin: () -> Unit, onHost: () -> Unit) {
    Back("‹ $groupName", onBack)
    Text(if (start) t("¿Quién arranca la juntada?", "Who starts the hangout?") else t("¿Quién arma el encuentro?", "Who hosts the meetup?"), color = Sand, fontSize = 36.sp, lineHeight = 38.sp,
        fontWeight = FontWeight.SemiBold, letterSpacing = (-1).sp, modifier = Modifier.padding(top = 24.dp))
    Text(if (start) t("Uno la arranca y los demás escanean su código. Así empieza a la misma hora para todos.", "One person starts it and the rest scan their code, so it starts at the same time for everyone.")
        else t("Son tres escaneos y lleva un minuto.", "It's three scans and takes a minute."),
        color = Body, fontSize = 15.sp, lineHeight = 22.sp, modifier = Modifier.padding(top = 12.dp))
    if (!start) {
        Spacer(Modifier.height(20.dp))
        listOf(t("Todos escanean el código de quien arma", "Everyone scans the host's code"), t("Quien arma escanea el de cada uno", "The host scans each person's code"),
            t("Todos escanean el código final y listo", "Everyone scans the final code. Done"))
            .forEachIndexed { i, text ->
                Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(22.dp).clip(CircleShape).background(if (i == 2) Accent else Sand), contentAlignment = Alignment.Center) {
                        Text("${i + 1}", color = Card, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Text(text, color = Sand, fontSize = 15.sp, modifier = Modifier.padding(start = 12.dp))
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
            }
        Text(t("Si alguien se va antes del paso 3, puede escanear el código final más tarde.", "If someone leaves before step 3, they can scan the final code later."), color = Muted, fontSize = 13.sp,
            lineHeight = 19.sp, modifier = Modifier.padding(top = 12.dp))
    }
    Spacer(Modifier.height(32.dp))
    Choice(if (start) t("La arranco yo", "I'll start it") else t("Armo yo", "I'll host"),
        if (start) t("Muestro mi código.", "I show my code.") else t("Muestro mi código y escaneo a cada uno.", "I show my code and scan everyone."), true, onHost)
    Spacer(Modifier.height(10.dp))
    Choice(t("Me sumo", "I'm joining"), if (start) t("Escaneo el código de quien la arranca.", "I scan the starter's code.") else t("Escaneo el código de quien arma.", "I scan the host's code."), false, onJoin)
    notice?.let { Notice(it) }
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun Choice(title: String, sub: String, main: Boolean, onClick: () -> Unit) = Column(
    Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Card)
        .border(if (main) 2.dp else 1.dp, if (main) Sand else Line, RoundedCornerShape(20.dp))
        .clickable(role = Role.Button, onClick = onClick).padding(20.dp),
) {
    Text(title, color = Sand, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    Text(sub, color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun RondaDone(st: Step.Done, me: String, groupName: String, onOk: () -> Unit, onRetry: () -> Unit) {
    val f = st.final
    val all = f?.let(::outcome).orEmpty()
    val mine = all.firstOrNull { it.id == me }
    TopBar(if (f != null) t("Cuenta saldada · $groupName", "Settled up · $groupName") else t("No se pudo saldar", "Couldn't settle"))
    val pot = f?.members?.sumOf { it.debt }?.plus(all.sumOf { it.fine }) ?: 0
    Text(if (f == null) t("Sin cambios", "No changes") else if (pot == 0 && (mine?.delta ?: 0) == 0) t("Esta vez", "This time") else t("Te llevás", "You get"),
        color = Muted, fontSize = 17.sp, modifier = Modifier.padding(top = 24.dp))
    val delta = mine?.delta ?: 0
    BigNumber(signed(delta), t("puntos", "points"), if (delta > 0) Accent else Sand, size = 120)
    if (f == null) Notice(t("No se perdió nada: tus puntos, el pozo y la juntada siguen guardados en cada celu. " +
        "Pasa si alguien escaneó un código viejo. Vuelvan a hacer los tres escaneos y sale el mismo reparto.",
        "Nothing was lost: your points, the pot and the hangout are still saved on every phone. " +
        "This happens when someone scans an old code. Do the three scans again and you'll get the same split."))
    else if (pot == 0) Text(t("Nadie se pasó de redes desde la última vez: no había pozo para repartir.", "Nobody went over on social media since last time: there was no pot to split."), color = Body, fontSize = 14.sp,
        lineHeight = 20.sp, modifier = Modifier.padding(top = 16.dp))
    if (mine != null) {
        Spacer(Modifier.height(20.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
        if (mine.bonus > 0) Source(null, t("Juntada · ${mine.bonus} bloques dobles", "Hangout · ${mine.bonus} double blocks"), "", "+${mine.bonus}")
        Source(null, t("Tu parte del pozo", "Your share of the pot"), "", if (mine.share > 0) "+${mine.share}" else "0")
        all.filter { it.fine > 0 }.forEach { Source(null, t("Desbloqueos: ${if (it.id == me) "vos" else it.name} · ${times(it.fine / UNLOCK_FINE)}", "Unlocks: ${if (it.id == me) "you" else it.name} · ${times(it.fine / UNLOCK_FINE)}"), "", "−${it.fine}") }
    }
    Spacer(Modifier.height(40.dp))
    if (f == null) PrimaryButton(t("Volver a escanear", "Scan again"), onClick = onRetry) else {
        PrimaryButton(t("Listo", "Done"), onClick = onOk)
        val ctx = LocalContext.current
        TextAction(t("Compartir la juntada", "Share the meetup")) { shareCard(ctx, potCard(f, groupName, me)) }
    }
    Spacer(Modifier.height(12.dp))
}

internal fun why(o: Outcome) = buildList {
    if (o.share > 0) add(t("pozo +${o.share}", "pot +${o.share}"))
    if (o.bonus > 0) add(t("juntada +${o.bonus}", "hangout +${o.bonus}"))
    if (o.fine > 0) add(t("desbloqueos −${o.fine}", "unlocks −${o.fine}"))
}.joinToString(" · ").ifEmpty { t("sin cambios", "no change") }

/** Un paso del encuentro: en qué paso va, qué hacer, el QR y lo que se puede tocar. */
@Composable
private fun QrStep(
    label: String?, title: String, sub: String?, code: String, notice: String?,
    onCancel: () -> Unit, actions: @Composable ColumnScope.() -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) { Back(t("Cancelar", "Cancel"), onCancel) }
        label?.let { Text(it, color = Muted, fontSize = 13.sp) }
    }
    Text(title, color = Sand, fontSize = 28.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.8).sp,
        modifier = Modifier.padding(top = 12.dp))
    sub?.let { Text(it, color = Muted, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.padding(top = 4.dp)) }
    Box(Modifier.fillMaxWidth().padding(top = 20.dp), contentAlignment = Alignment.Center) { OffQr(code) }
    notice?.let { Notice(it) }
    Spacer(Modifier.height(16.dp))
    Column(content = actions)
    Spacer(Modifier.height(20.dp))
}

// ---------- QR ----------

private val QrInk = Color(0xFF111111)

/** Gira la matriz 90° a la derecha: los tres cuadrados quedan arriba a la izquierda, arriba a la derecha y abajo a la derecha. */
internal fun rotateCw(m: List<BooleanArray>): List<BooleanArray> = List(m[0].size) { r -> BooleanArray(m.size) { c -> m[m.size - 1 - c][r] } }

/** El QR de off: siempre claro, módulos redondeados y el cuadrado de abajo a la derecha en naranja, como el punto de «off.». */
@Composable
private fun OffQr(text: String) {
    val m = remember(text) {
        val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.H, EncodeHintType.MARGIN to 0)
        val bits = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
        rotateCw(List(bits.height) { y -> BooleanArray(bits.width) { x -> bits[x, y] } })
    }
    val shape = RoundedCornerShape(24.dp)
    Box(
        Modifier.size(272.dp).clip(shape).background(Color.White).border(1.dp, Color(0x1F000000), shape).padding(20.dp)
            .semantics { contentDescription = t("Código QR del encuentro", "Meetup QR code") },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val n = m.size
            val cell = size.minDimension / n
            val gap = cell * 0.1f
            for (r in 0 until n) for (c in 0 until n) if (m[r][c]) {
                val color = if (r >= n - 7 && c >= n - 7) Accent else QrInk
                // Los tres cuadrados van macizos: con huecos entre módulos el lector no los encuentra y no escanea nada.
                val finder = (r < 7 || r >= n - 7) && (c < 7 || c >= n - 7) && !(r >= n - 7 && c < 7)
                if (finder) drawRect(color, Offset(c * cell, r * cell), Size(cell + 0.5f, cell + 0.5f))
                else drawRoundRect(color, Offset(c * cell + gap / 2, r * cell + gap / 2), Size(cell - gap, cell - gap), CornerRadius(cell * 0.3f))
            }
        }
        Row(Modifier.clip(RoundedCornerShape(10.dp)).background(Color.White).padding(horizontal = 9.dp, vertical = 4.dp)) {
            Text("off", color = QrInk, fontSize = 17.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp)
            Text(".", color = Accent, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        }
    }
}
