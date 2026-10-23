package app.getoff

import kotlin.math.max
import kotlin.math.min

// IRL: el pozo de cada grupo se salda en persona. Todo es determinista: cada celu calcula lo mismo con los mismos datos.

const val UNLOCK_FINE = 5 // el primero que desbloquea en una juntada paga esto al pozo

/** Lo que cada uno lleva a un encuentro: lo ganado y lo que debe al pozo desde que saldó por última vez. */
data class Summary(val id: String, val earned: Int, val debt: Int)

/**
 * Reparte las deudas de los presentes entre los presentes, en proporción a lo ganado.
 * Lo que debe cada uno ya se le descontó de su total al pasarse de redes: acá solo se cobra la parte.
 * Si nadie ganó nada, el pozo queda para el próximo encuentro (vacío = sin reparto).
 */
fun settle(present: List<Summary>): Map<String, Int> {
    val pot = present.sumOf { it.debt }
    val totalEarned = present.sumOf { max(0, it.earned) }
    if (pot == 0 || totalEarned == 0) return emptyMap()
    val sorted = present.sortedBy { it.id }
    val shares = sorted.associate { it.id to pot * max(0, it.earned) / totalEarned }.toMutableMap()
    // El resto del redondeo va al que más ganó; empate, al primer id.
    val top = sorted.maxBy { it.earned }.id
    shares[top] = shares.getValue(top) + pot - shares.values.sum()
    return shares
}

/** Intervalos en que todos tenían el celu bloqueado dentro de [from, to). */
fun allLocked(lockedByMember: List<List<LongRange>>, from: Long, to: Long): List<LongRange> =
    lockedByMember.fold(listOf(from..to)) { acc, member ->
        acc.flatMap { a ->
            member.mapNotNull { b ->
                val s = max(a.first, b.first)
                val e = min(a.last, b.last)
                if (e > s) s..e else null
            }
        }
    }

/** Bonus de juntada: los bloques de 30 min con todos bloqueados cuentan otra vez. */
fun togetherBonus(lockedByMember: List<List<LongRange>>, from: Long, to: Long): Int =
    blockPoints(allLocked(lockedByMember, from, to))

/**
 * Quién desbloqueó primero. La juntada arranca cuando el último bloquea (al arrancar todos tienen el celu
 * en la mano). Null si nunca estuvieron todos bloqueados o nadie desbloqueó antes de `to`.
 */
fun firstToUnlock(lockedByMember: Map<String, List<LongRange>>, from: Long, to: Long): String? {
    val start = allLocked(lockedByMember.toSortedMap().values.toList(), from, to).firstOrNull()?.first ?: return null
    return lockedByMember.toSortedMap().entries
        .mapNotNull { (id, locked) -> locked.firstOrNull { start in it }?.let { id to it.last } }
        .filter { it.second < to }
        .minByOrNull { it.second }
        ?.first
}

// ---------- Códigos QR ----------
// Texto plano y corto para que el QR se lea fácil de pantalla a pantalla. Versión "D1".
//
// Sala:  D1|S|<tipo>|<código>|<grupo>|<desde>|<hasta>|<anfitrión>|<nombre del grupo>   tipo I = empieza juntada, S = saldar (y cerrar juntada si hay)
// Mío:   D1|M|<código>|<miembro>
// Final: D1|F|<código>|<grupo>|<desde>|<hasta>|<miembro>~<miembro>…
// Miembro: id,apodo,ganado,debe,bloqueos   bloqueos = "ini-fin;ini-fin" en minutos desde <desde> (vacío sin juntada)

data class Member(val id: String, val name: String, val earned: Int, val debt: Int, val locked: List<LongRange> = emptyList())

sealed interface Code
/** `ronda`: id del grupo (el nombre del campo quedó de cuando había una sola ronda). */
data class Sala(val start: Boolean, val nonce: String, val ronda: String, val from: Long, val to: Long, val host: String = "", val group: String = "") : Code
data class Mine(val nonce: String, val member: Member) : Code
data class Final(val nonce: String, val ronda: String, val from: Long, val to: Long, val members: List<Member>) : Code

private const val V = "D1"

/** Lo que el apodo no puede tener para no romper el formato. */
fun cleanName(s: String) = s.replace(Regex("[|~,;\\-\\n]"), " ").trim().take(20)

private fun Member.encode(from: Long): String {
    val locks = locked.joinToString(";") { "${(it.first - from) / 60_000}-${(it.last - from) / 60_000}" }
    return listOf(id, cleanName(name), earned, debt, locks).joinToString(",")
}

private fun member(s: String, from: Long): Member {
    val p = s.split(",")
    val locks = p[4].split(";").filter { it.isNotBlank() }.map {
        val (a, b) = it.split("-").map(String::toLong)
        from + a * 60_000..from + b * 60_000
    }
    return Member(p[0], p[1], p[2].toInt(), p[3].toInt(), locks)
}

fun encode(c: Code): String = when (c) {
    is Sala -> listOf(V, "S", if (c.start) "I" else "S", c.nonce, c.ronda, c.from, c.to, cleanName(c.host), cleanName(c.group)).joinToString("|")
    is Mine -> listOf(V, "M", c.nonce, c.member.encode(0)).joinToString("|")
    is Final -> listOf(V, "F", c.nonce, c.ronda, c.from, c.to, c.members.joinToString("~") { it.encode(c.from) }).joinToString("|")
}

/** Null si no es un código de Off o está roto. */
fun decode(s: String): Code? = runCatching {
    val p = s.split("|")
    require(p[0] == V)
    when (p[1]) {
        "S" -> Sala(p[2] == "I", p[3], p[4], p[5].toLong(), p[6].toLong(), p.getOrElse(7) { "" }, p.getOrElse(8) { "" })
        "M" -> Mine(p[2], member(p[3], 0))
        "F" -> {
            val from = p[4].toLong()
            Final(p[2], p[3], from, p[5].toLong(), p[6].split("~").map { member(it, from) })
        }
        else -> null
    }
}.getOrNull()

/** Lo que le toca a cada uno al saldar: bonus de juntada, multa y su parte del pozo. */
data class Outcome(val id: String, val name: String, val bonus: Int, val fine: Int, val share: Int) {
    val delta get() = bonus - fine + share
}

/** Lo calcula cada celu con el mismo código final: todos llegan al mismo resultado. */
fun outcome(f: Final): List<Outcome> {
    val juntada = f.from > 0
    val locks = f.members.associate { it.id to it.locked }
    val bonus = if (juntada) togetherBonus(locks.toSortedMap().values.toList(), f.from, f.to) else 0
    val payer = if (juntada) firstToUnlock(locks, f.from, f.to) else null
    val shares = settle(f.members.map { Summary(it.id, it.earned, it.debt + if (it.id == payer) UNLOCK_FINE else 0) })
    return f.members.sortedBy { it.id }.map {
        Outcome(it.id, it.name, bonus, if (it.id == payer) UNLOCK_FINE else 0, shares[it.id] ?: 0)
    }
}

// ---------- Encuentro sin terminar ----------

/** Encuentro en que mostré mi código pero no escaneé el final: lo que mandé, a quién y cuándo. */
data class Pending(val mine: Mine, val host: String, val at: Long)

fun encodePending(p: Pending) = listOf(p.at, cleanName(p.host), encode(p.mine)).joinToString("~")

/** Null si está roto. El apodo no puede tener «~» (lo saca cleanName) y el código Mío tampoco. */
fun decodePending(s: String): Pending? = runCatching {
    val (at, host, code) = s.split("~", limit = 3)
    Pending(decode(code) as Mine, host, at.toLong())
}.getOrNull()

/** Mis bloqueos de la juntada en bloques de 30 min: los completos valen 1 y el que se cortó, la parte que llegó. */
fun lockSegments(locked: List<LongRange>): List<Float> = locked.flatMap { r ->
    val len = r.last - r.first
    List((len / BLOCK_MS).toInt()) { 1f } + listOfNotNull((len % BLOCK_MS).toFloat().div(BLOCK_MS).takeIf { it > 0f })
}
