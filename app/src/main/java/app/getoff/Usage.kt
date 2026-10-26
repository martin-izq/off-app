package app.getoff

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

fun hasUsageAccess(ctx: Context): Boolean {
    val ops = ctx.getSystemService(AppOpsManager::class.java)
    // unsafeCheckOpNoThrow es de Android 10; en Android 9 la vieja hace lo mismo.
    val mode = if (Build.VERSION.SDK_INT >= 29) ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
    else @Suppress("DEPRECATION") ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
    return mode == AppOpsManager.MODE_ALLOWED
}

internal fun readEvents(ctx: Context, from: Long, to: Long): List<Ev> {
    val it = ctx.getSystemService(UsageStatsManager::class.java).queryEvents(from, to)
    val e = UsageEvents.Event()
    val out = ArrayList<Ev>()
    while (it.hasNextEvent()) {
        it.getNextEvent(e)
        when (e.eventType) {
            KEYGUARD_SHOWN, KEYGUARD_HIDDEN -> out += Ev(e.timeStamp, e.eventType)
            RESUMED, PAUSED -> if (e.packageName in SOCIAL_APPS) out += Ev(e.timeStamp, e.eventType, e.packageName)
        }
    }
    return out
}

/** Cuánto se lee antes de un rango para saber si arrancó con el celu bloqueado. */
private const val LOOKBACK_MS = 12 * 3_600_000L

/** Bloqueos dentro de [from, to). */
fun lockedBetween(ctx: Context, from: Long, to: Long) = lockedIntervals(readEvents(ctx, from - LOOKBACK_MS, to), from, to)

/**
 * Serializa las escrituras de días: sync (worker y UI) y el bonus de sesión leen un Day, esperan a
 * Health Connect y lo guardan entero; sin esto uno pisa al otro.
 */
internal val dayLock = Mutex()

private fun LocalDate.millis(hour: Int = 0) =
    atTime(hour, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

/** Bloqueos de `date` dentro del horario despierto, hasta `now`. */
fun lockedOn(ctx: Context, date: LocalDate, now: Long = System.currentTimeMillis()): List<LongRange> {
    val to = minOf(date.plusDays(1).millis(), now)
    return lockedIntervals(readEvents(ctx, date.millis() - LOOKBACK_MS, to), date.millis(WAKE_HOUR), to)
}

/**
 * Calcula un día desde los eventos del sistema y Health Connect. Null si Android ya no tiene eventos de ese día
 * (ni de las 12 h previas: un día entero bloqueado se calcula con el bloqueo de la noche anterior).
 */
private suspend fun computeDay(ctx: Context, store: Store, date: LocalDate, now: Long): Day? {
    val start = date.millis()
    val end = date.plusDays(1).millis()
    val wake = date.millis(WAKE_HOUR)
    val to = minOf(end, now)
    // ponytail: un bloqueo de más de 12 h antes de la medianoche no se ve y ese día queda sin calcular;
    // buscar el último evento del keyguard más atrás si aparecen días así.
    val evs = readEvents(ctx, start - LOOKBACK_MS, to)
    if (evs.isEmpty()) return null

    val locked = lockedIntervals(evs, wake, to)
    val blocks = blockPoints(locked)
    val rest = longRestPoints(locked)
    val social = socialMinutes(evs, start, to)
    val history = (1..HISTORY_DAYS).mapNotNull { store.load(date.minusDays(it.toLong()))?.socialMin }
    val penalty = socialPenalty(social, history)
    val goal = store.goalFor(date)
    val unlocks = evs.count { it.type == KEYGUARD_HIDDEN && it.t >= start }
    val unlockHistory = (1..HISTORY_DAYS).mapNotNull { store.load(date.minusDays(it.toLong()))?.unlocks }
    val closed = now >= end
    val prev = store.load(date)
    // Sin Health Connect (o sin poder leerlo en segundo plano) queda lo último leído.
    val act = readActivity(ctx, start, to)
    val day = Day(
        date, blocks, social, penalty, goal, goalBonus = 0, unlocks, closed, rest,
        unlockBonus = if (closed) unlockPoints(unlocks, unlockHistory) else 0,
        // Pasos de todo el día, no solo despierto: salir a correr a las 6 también cuenta.
        steps = act?.let { lockedSteps(it.steps, lockedIntervals(evs, start, to)).toInt() } ?: prev?.steps ?: 0,
        exerciseMin = act?.let { exerciseMinutes(it.exercise.map { e -> e.second }, start, to) } ?: prev?.exerciseMin ?: 0,
        exerciseByType = act?.let { exerciseByType(it.exercise, start, to) } ?: prev?.exerciseByType.orEmpty(),
        sessionBonus = prev?.sessionBonus ?: 0,
    )
    // El bonus de meta queda firme una vez ganado, aunque después baje el bonus de redes.
    val met = day.toGoal >= goal || prev?.met == true
    return day.copy(goalBonus = if (met) goalBonus(goal, streakBefore(store, date)) else 0)
}

/** Racha que trae `date` de los días anteriores (sin contarlo). Antes de instalar no hay racha. */
fun streakBefore(store: Store, date: LocalDate): Int =
    generateSequence(date.minusDays(1)) { it.minusDays(1) }
        .takeWhile { !it.isBefore(store.installDate) }
        .takeWhile { store.load(it)?.met == true || it in store.frozen }
        .count()

/**
 * Cierra los días pasados que falten (hasta 7 atrás, lo que Android suele guardar) y recalcula hoy.
 * Ayer se recalcula siempre: el reloj puede sincronizar pasos y ejercicio a la mañana siguiente.
 * Lo usan el worker y la UI.
 */
suspend fun sync(ctx: Context, now: Long = System.currentTimeMillis()): Day = dayLock.withLock {
    val store = Store(ctx)
    store.installDate // fija la fecha de instalación en la primera corrida
    val today = LocalDate.now()
    for (i in HISTORY_DAYS downTo 1) {
        val d = today.minusDays(i.toLong())
        if (i == 1 || store.load(d)?.closed != true) computeDay(ctx, store, d, now)?.let(store::save)
    }
    // Comodín semanal: antes de calcular hoy, que hereda la racha.
    store.frozen = autoFreeze(store.scoredDays(), store.frozen, today.minusDays(HISTORY_DAYS.toLong()))
    val day = computeDay(ctx, store, today, now)
        ?: Day(today, 0, 0, 0, store.goalFor(today), 0, 0, false)
    store.save(day)
    // ponytail: la sección Puntos se sacó (2026-10-14). Esto solo cierra una apuesta que haya quedado abierta;
    // borrar junto con ledger/bet de Store cuando se decida qué hacer con los puntos.
    // La apuesta se cobra cuando cierra el día apostado.
    store.bet?.takeIf { it.date.isBefore(today) }?.let { bet ->
        val back = betReturn(bet, store.load(bet.date)?.takeIf { it.closed })
        if (back > 0) store.addEntry(Entry(today, back, if (back > bet.stake) t("Apuesta ganada", "Bet won") else t("Apuesta devuelta", "Bet refunded")))
        else store.addEntry(Entry(today, 0, t("Apuesta perdida", "Bet lost")))
        store.bet = null
    }
    Widget.update(ctx)
    day
}

/** Todo lo que muestra la UI. */
data class Snapshot(
    val today: Day,
    val days: List<Day>, // desde la instalación, del más viejo al más nuevo
    val lastRestMin: Int?, // último bloqueo de hoy, en minutos
    val socialAvg: Int?, // null mientras se arma el promedio
    val unlockHistory: List<Int>, // desbloqueos de los últimos 7 días
    val health: Health,
    val rondaLedger: List<Entry>, // del más viejo al más nuevo
    val nickname: String?,
    val myId: String,
    val groups: List<GroupView>,
    val historyCount: Int,
    val nextGoal: Int,
    val frozen: Set<LocalDate>,
    val session: Pair<Long, Int>?, // sesión en curso: inicio y minutos
) {
    /** Comodín de esta semana sin usar. */
    val freezeLeft get() = frozen.none { weekOf(it) == weekOf(today.date) }
    /** Cumplida o salvada con comodín: lo que cuenta para la racha. */
    fun kept(d: Day) = d.met || d.date in frozen
    /** Lo ganado en días más lo de IRL: las dos cosas cuentan para el nivel. */
    val totalPoints get() = days.sumOf { it.total } + rondaLedger.sumOf { it.amount }
    val level get() = level(totalPoints)
    val toNextLevel get() = if (level == LEVEL_MAX) 0 else levelStart(level + 1) - totalPoints
    val levelProgress get() = levelProgress(totalPoints)
    // Un día sin datos corta la racha, igual que en streakBefore (meta y widget).
    private val keptByDate get() = keptByDate(days, frozen, today.date)
    val streak get() = streak(keptByDate.reversed())
    val bestStreak get() = bestStreak(keptByDate)
}

suspend fun snapshot(ctx: Context): Snapshot {
    finishSession(ctx)
    val today = sync(ctx)
    val store = Store(ctx)
    dropBrokenSession(ctx, store)
    // Con la app abierta, la meta cumplida ya se ve en pantalla: no hace falta notificar.
    if (today.met) store.notifiedOn = today.date
    val history = (1..HISTORY_DAYS).mapNotNull { store.load(today.date.minusDays(it.toLong()))?.socialMin }
    val days = store.scoredDays()
    val cumulative = days.sumOf { it.earned } to days.sumOf { it.socialPenalty }
    return Snapshot(
        today = today,
        days = days,
        lastRestMin = lockedOn(ctx, today.date).lastOrNull()?.let { ((it.last - it.first) / 60_000).toInt() },
        socialAvg = if (history.size >= HISTORY_DAYS) history.average().toInt() else null,
        unlockHistory = (1..HISTORY_DAYS).mapNotNull { store.load(today.date.minusDays(it.toLong()))?.unlocks },
        health = healthState(ctx),
        rondaLedger = store.rondaLedger(),
        nickname = store.nickname,
        myId = store.myId,
        groups = store.groups.map { g ->
            val gs = store.group(g.id)
            GroupView(g.id, g.name, sinceSettle(store, g.id, cumulative), gs.juntada?.first, gs.meetings().mapNotNull { decode(it) as? Final }.reversed(), gs.pending)
        },
        historyCount = history.size,
        nextGoal = store.nextGoal(today.date),
        frozen = store.frozen,
        session = store.session,
    )
}

/** Un grupo de IRL como lo muestra la UI. */
data class GroupView(
    val id: String,
    val name: String,
    val sinceSettle: Pair<Int, Int>, // ganado y debido desde el último saldo en este grupo
    val juntadaFrom: Long?,
    val meetings: List<Final>, // del más nuevo al más viejo
    val pending: Pending? = null, // encuentro al que me falta escanear el código final
)

// ---------- IRL ----------
// Cada grupo es un juego aparte: lleva su propia cuenta desde el último saldo. Las redes de más
// van al pozo de cada grupo (ya se restaron una vez del total; el pozo solo reparte).

/** Ganado y debido (redes de más) acumulados desde la instalación. */
private fun cumulative(store: Store): Pair<Int, Int> {
    val days = store.scoredDays()
    return days.sumOf { it.earned } to days.sumOf { it.socialPenalty }
}

fun sinceSettle(store: Store, group: String, cumulative: Pair<Int, Int> = cumulative(store)): Pair<Int, Int> {
    val (e, d) = cumulative
    val g = store.group(group)
    return e - g.settledEarned to d - g.settledDebt
}

/** Un grupo nuevo, o uno que se conoce en un encuentro: arranca de cero, lo ganado antes no se reparte. */
fun joinGroup(store: Store, g: Group) {
    if (store.groups.any { it.id == g.id }) return
    val (e, d) = cumulative(store)
    store.group(g.id).apply { settledEarned = e; settledDebt = d }
    store.addGroup(g)
}

/** Lo que llevo a un encuentro del grupo. Con juntada, también mis bloqueos de [from, to). */
fun myMember(ctx: Context, store: Store, group: String, from: Long, to: Long): Member {
    val (e, d) = sinceSettle(store, group)
    val locked = if (from > 0) lockedBetween(ctx, from, to) else emptyList()
    return Member(store.myId, store.nickname ?: "?", e, d, locked)
}

/** Como viaja por QR: así se compara con lo que vuelve en el código final. */
fun normalized(nonce: String, m: Member) = (decode(encode(Mine(nonce, m))) as Mine).member

/**
 * Aplica un código final al grupo que dice el código: verifica que mis datos vengan tal cual los mandé
 * y anota lo que me toca. Devuelve el error a mostrar, o null si salió bien.
 */
fun applyFinal(store: Store, f: Final, mine: Member): String? {
    val group = store.groups.firstOrNull { it.id == f.ronda } ?: return t("No estás en este grupo.", "You're not in this group.")
    val g = store.group(group.id)
    if (g.lastFinal == f.nonce) return t("Este encuentro ya está saldado.", "This meetup is already settled.")
    val me = f.members.firstOrNull { it.id == store.myId } ?: return t("No estás en este código.", "You're not in this code.")
    if (me != normalized(f.nonce, mine)) return t("Tus datos no coinciden con los que mandaste.", "Your data doesn't match what you sent.")
    val o = outcome(f).first { it.id == store.myId }
    val today = LocalDate.now()
    if (o.bonus > 0) store.addRondaEntry(Entry(today, o.bonus, t("Juntada · ${group.name}", "Hangout · ${group.name}")))
    if (o.fine > 0) store.addRondaEntry(Entry(today, -o.fine, t("Desbloqueaste primero · ${group.name}", "You unlocked first · ${group.name}")))
    if (o.share > 0) store.addRondaEntry(Entry(today, o.share, t("Pozo · ${group.name}", "Pot · ${group.name}")))
    g.settledEarned += me.earned
    g.settledDebt += me.debt
    g.juntada = null
    g.pending = null
    g.lastFinal = f.nonce
    g.addMeeting(encode(f))
    return null
}
