package app.getoff

import java.time.LocalDate
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// Reglas del juego. Fijas a propósito: no hay ajustes.
const val BLOCK_MS = 30 * 60_000L
const val LONG_REST_MS = 2 * 3_600_000L
const val STREAK_FULL = 7 // días de racha para llegar al multiplicador máximo
const val STREAK_MAX_MULT = 2.0
const val WAKE_HOUR = 8 // horario de sueño fijo 00–08 h
const val SOCIAL_MIN_PER_POINT = 5
const val SOCIAL_PENALTY_CAP = 30
const val UNLOCKS_PER_POINT = 5
const val HISTORY_DAYS = 7
const val GOAL_MIN = 2
const val GOAL_MAX = 32 // todos los bloques de 08 a 24 h
const val GOAL_DEFAULT = 10

// Mismos valores que UsageEvents.Event; propios para testear sin Android.
const val RESUMED = 1
const val PAUSED = 2
const val KEYGUARD_SHOWN = 17
const val KEYGUARD_HIDDEN = 18

val SOCIAL_APPS = setOf(
    "com.instagram.android",
    "com.instagram.barcelona", // Threads
    "com.zhiliaoapp.musically", // TikTok
    "com.ss.android.ugc.trill", // TikTok (algunas regiones)
    "com.twitter.android",
    "com.facebook.katana",
    "com.facebook.lite",
    "com.snapchat.android",
    "com.reddit.frontpage",
    "com.google.android.youtube",
)

data class Ev(val t: Long, val type: Int, val pkg: String = "")

data class Day(
    val date: LocalDate,
    val blocks: Int,
    val socialMin: Int,
    val socialPenalty: Int, // puntos que restan las redes por encima del promedio
    val goal: Int,
    val goalBonus: Int,
    val unlocks: Int,
    val closed: Boolean,
    val restBonus: Int = 0,
    val unlockBonus: Int = 0, // se acredita al cerrar el día
    val steps: Int = 0, // pasos con el celu bloqueado
    val exerciseMin: Int = 0, // ejercicio sin pasos (bici, gimnasio, natación…)
    val exerciseByType: Map<Int, Int> = emptyMap(), // minutos por tipo de Health Connect, solo para mostrar
    val sessionBonus: Int = 0, // bloques extra por sesiones cumplidas; queda firme al cumplirse
) {
    val stepBonus get() = stepPoints(steps)
    val exerciseBonus get() = exercisePoints(exerciseMin)
    /** Lo ganado sin redes ni meta. */
    val earned get() = blocks + restBonus + unlockBonus + stepBonus + exerciseBonus + sessionBonus
    /** Lo que cuenta para la meta: todo menos el propio bonus de meta. Puede ser negativo. */
    val toGoal get() = earned - socialPenalty
    /** Lo ganado antes de descontar redes. */
    val gross get() = earned + goalBonus
    val total get() = toGoal + goalBonus
    val met get() = goalBonus > 0
}

/**
 * Intervalos con el celu bloqueado (KEYGUARD_SHOWN → KEYGUARD_HIDDEN), recortados a [from, to).
 * Un bloqueo todavía abierto cuenta hasta `to`. Lo previo a un primer desbloqueo sin bloqueo
 * conocido se ignora: no sumamos tiempo que no vimos.
 */
fun lockedIntervals(evs: List<Ev>, from: Long, to: Long): List<LongRange> {
    val out = mutableListOf<LongRange>()
    var start: Long? = null
    for (e in evs.sortedBy { it.t }) {
        when (e.type) {
            KEYGUARD_SHOWN -> if (start == null) start = e.t
            KEYGUARD_HIDDEN -> {
                start?.let { out += it..e.t }
                start = null
            }
        }
    }
    start?.let { out += it..to }
    return out.mapNotNull { r ->
        val s = max(r.first, from)
        val e = min(r.last, to)
        if (e > s) s..e else null
    }
}

fun blockPoints(locked: List<LongRange>): Int = locked.sumOf { ((it.last - it.first) / BLOCK_MS).toInt() }

/** Minutos en primer plano de apps de redes dentro de [from, to). */
fun socialMinutes(evs: List<Ev>, from: Long, to: Long): Int {
    val open = mutableMapOf<String, Long>()
    var ms = 0L
    fun add(s: Long, e: Long) {
        ms += max(0L, min(e, to) - max(s, from))
    }
    for (e in evs.sortedBy { it.t }) {
        if (e.pkg !in SOCIAL_APPS) continue
        when (e.type) {
            RESUMED -> open.putIfAbsent(e.pkg, e.t)
            PAUSED -> open.remove(e.pkg)?.let { add(it, e.t) }
        }
    }
    open.values.forEach { add(it, to) }
    return (ms / 60_000).toInt()
}

/**
 * Usar redes hasta el promedio semanal no suma ni resta. Pasado el promedio, resta 1 punto
 * cada 5 min, hasta 30 por día. Sin una semana de historia no hay promedio y no resta.
 */
fun socialPenalty(todayMin: Int, history: List<Int>): Int {
    if (history.size < HISTORY_DAYS) return 0
    return ((todayMin - history.average()) / SOCIAL_MIN_PER_POINT).toInt().coerceIn(0, SOCIAL_PENALTY_CAP)
}

/**
 * Desbloquear menos que el promedio semanal suma 1 cada 5 de menos. Solo para días cerrados:
 * a la mañana cualquier día está por debajo del promedio. Sin una semana de historia no suma.
 */
fun unlockPoints(unlocks: Int, history: List<Int>): Int {
    if (history.size < HISTORY_DAYS) return 0
    return ((history.average() - unlocks) / UNLOCKS_PER_POINT).toInt().coerceAtLeast(0)
}

/** +1 extra por cada 2 h seguidas sin desbloquear: premia desconectarse de verdad, no juntar ratos. */
fun longRestPoints(locked: List<LongRange>): Int = locked.sumOf { ((it.last - it.first) / LONG_REST_MS).toInt() }

/**
 * 20 % de la meta, mínimo 1: poner una meta baja no paga. La racha previa lo multiplica:
 * ×(1 + racha/7), hasta ×2 con una semana entera.
 */
fun goalBonus(goal: Int, streak: Int = 0): Int {
    val base = max(1, (goal * 0.2).roundToInt())
    return (base * streakMultiplier(streak)).roundToInt()
}

fun streakMultiplier(streak: Int): Double = min(STREAK_MAX_MULT, 1 + streak.toDouble() / STREAK_FULL)

/** Cinco niveles, del 1 al 5: los puntos donde arranca cada uno. El 5 es el máximo. */
val LEVEL_STARTS = listOf(0, 150, 500, 1200, 2500)
val LEVEL_MAX = LEVEL_STARTS.size

fun levelStart(n: Int): Int = LEVEL_STARTS[(n - 1).coerceIn(0, LEVEL_MAX - 1)]

/** Con puntos negativos (IRL) se queda en el 1. */
fun level(points: Int): Int = LEVEL_STARTS.count { it <= points }.coerceAtLeast(1)

/** Días seguidos con meta cumplida, del más nuevo al más viejo. Hoy sin cumplir no corta la racha. */
fun streak(metNewestFirst: List<Boolean>): Int {
    val days = if (metNewestFirst.firstOrNull() == false) metNewestFirst.drop(1) else metNewestFirst
    return days.takeWhile { it }.size
}

/** Racha más larga de días con meta cumplida, en orden cronológico. */
fun bestStreak(met: List<Boolean>): Int {
    var best = 0
    var run = 0
    for (m in met) {
        run = if (m) run + 1 else 0
        best = max(best, run)
    }
    return best
}

// ---------- Saldo ----------
// El total ganado nunca baja por gastar (marca el nivel). El saldo es total + movimientos.

const val BET_PAYOUT = 2 // ganar devuelve el doble de lo apostado

/** Movimiento del saldo: negativo al gastar o apostar, positivo al cobrar o devolver. */
data class Entry(val date: LocalDate, val amount: Int, val label: String)

/** Apuesta a que `date` llega a `target` puntos (sin contar el bonus de meta). */
data class Bet(val date: LocalDate, val stake: Int, val target: Int)

/** Lo que vuelve al saldo al cerrar el día apostado. Sin datos de ese día, se devuelve lo apostado. */
fun betReturn(bet: Bet, day: Day?): Int = when {
    day == null -> bet.stake
    day.toGoal >= bet.target -> bet.stake * BET_PAYOUT
    else -> 0
}

// ---------- Actividad (Health Connect) ----------
// Pasos: solo los dados con el celu bloqueado. Ejercicio: solo tipos sin pasos, sin cargas manuales.

const val STEPS_PER_POINT = 1_000
const val STEPS_FULL_RATE = 10_000 // después, la mitad: 1 cada 2.000
const val EXERCISE_MIN_PER_POINT = 10
const val EXERCISE_FULL_RATE_MIN = 60 // después, la mitad: 1 cada 20 min

/** Registro de pasos de Health Connect: `count` pasos repartidos en [range]. */
data class Steps(val range: LongRange, val count: Long)

/**
 * Pasos dentro de los bloqueos. Un registro que cruza el borde de un bloqueo aporta en proporción
 * al tiempo superpuesto (asume ritmo parejo dentro del registro).
 */
fun lockedSteps(records: List<Steps>, locked: List<LongRange>): Long = records.sumOf { r ->
    val len = r.range.last - r.range.first
    if (len <= 0) return@sumOf if (locked.any { r.range.first in it }) r.count else 0L
    val overlap = locked.sumOf { max(0L, min(it.last, r.range.last) - max(it.first, r.range.first)) }
    r.count * min(overlap, len) / len
}

/** Rinde completo hasta un punto y a la mitad después: más actividad siempre suma, sin tope. */
private fun diminishing(amount: Int, perPoint: Int, fullRate: Int): Int {
    val full = min(amount, fullRate)
    return full / perPoint + (amount - full) / (perPoint * 2)
}

/** +1 cada 1.000 pasos hasta 10.000, después +1 cada 2.000. */
fun stepPoints(steps: Int): Int = diminishing(steps, STEPS_PER_POINT, STEPS_FULL_RATE)

/** +1 cada 10 min la primera hora, después +1 cada 20 min. */
fun exercisePoints(minutes: Int): Int = diminishing(minutes, EXERCISE_MIN_PER_POINT, EXERCISE_FULL_RATE_MIN)

/**
 * Minutos cubiertos por las sesiones dentro de [from, to). Las superpuestas cuentan una vez:
 * el reloj y Strava pueden registrar la misma salida.
 */
fun exerciseMinutes(sessions: List<LongRange>, from: Long, to: Long): Int {
    var ms = 0L
    var end = from
    for (s in sessions.sortedBy { it.first }) {
        val a = max(s.first, end)
        val b = min(s.last, to)
        if (b > a) ms += b - a
        end = max(end, b)
    }
    return (ms / 60_000).toInt()
}

/** Minutos por tipo de ejercicio. Solo informativo: los puntos salen del total, sin superposiciones. */
fun exerciseByType(sessions: List<Pair<Int, LongRange>>, from: Long, to: Long): Map<Int, Int> =
    sessions.groupBy({ it.first }, { it.second })
        .mapValues { exerciseMinutes(it.value, from, to) }
        .filterValues { it > 0 }

// ---------- Sesión y resumen semanal ----------

val SESSION_OPTIONS = listOf(30, 60, 120) // minutos
const val SESSION_GRACE_MS = 2 * 60_000L // para bloquear después de tocar «Empezar»

/**
 * Sesión cumplida: un solo bloqueo cubre desde el fin de la gracia hasta el final. Entonces los bloques
 * de la sesión valen doble: suma otra vez un punto cada 30 min. Si no, 0: desbloquear antes no castiga.
 */
fun sessionPoints(locked: List<LongRange>, start: Long, minutes: Int): Int {
    val end = start + minutes * 60_000L
    val kept = locked.any { it.first <= start + SESSION_GRACE_MS && it.last >= end }
    return if (kept) (minutes * 60_000L / BLOCK_MS).toInt() else 0
}

/**
 * Tiempo recuperado de la semana: por cada día, los minutos de redes por debajo del promedio de los 7 días
 * previos. Los días sin una semana de historia no suman. `days`: del más viejo al más nuevo, sin huecos.
 */
fun recoveredMinutes(socialMin: List<Int>, week: Int = 7): Int =
    (maxOf(socialMin.size - week, HISTORY_DAYS) until socialMin.size).sumOf { i ->
        val avg = socialMin.subList(i - HISTORY_DAYS, i).average()
        maxOf(0, (avg - socialMin[i]).toInt())
    }

// ---------- Comodín ----------

fun weekOf(date: LocalDate): LocalDate = date.with(java.time.DayOfWeek.MONDAY)

/**
 * Comodín semanal: uno por semana (lun–dom), se aplica solo al primer día cerrado sin meta que cortaría
 * una racha en curso. No se acumula. Si un día salvado termina con la meta cumplida (pasos que llegan
 * tarde), el comodín vuelve. Solo agrega días desde `from`: lo anterior queda como estaba.
 * `days`: del más viejo al más nuevo.
 */
fun autoFreeze(days: List<Day>, frozen: Set<LocalDate>, from: LocalDate): Set<LocalDate> {
    val out = frozen.toMutableSet()
    var run = 0
    var prev: LocalDate? = null
    for (d in days) {
        if (prev != null && prev.plusDays(1) != d.date) run = 0 // día sin datos: corta
        prev = d.date
        if (d.met) out -= d.date
        else if (d.closed && !d.date.isBefore(from) && d.date !in out && run > 0 &&
            out.none { weekOf(it) == weekOf(d.date) }
        ) out += d.date
        run = if (d.met || d.date in out) run + 1 else 0
    }
    return out
}
