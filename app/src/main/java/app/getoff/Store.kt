package app.getoff

import android.content.Context
import java.time.LocalDate

// ponytail: SharedPreferences con una key por día; migrar a Room si aparecen consultas o más campos.
class Store(ctx: Context) {
    private val p = ctx.getSharedPreferences("off", Context.MODE_PRIVATE)

    var onboarded: Boolean
        get() = p.getBoolean("onboarded", false)
        set(v) = p.edit().putBoolean("onboarded", v).apply()

    /** Primer día que suma puntos. Los días anteriores solo alimentan el promedio de redes. */
    val installDate: LocalDate
        get() = p.getString("install", null)?.let(LocalDate::parse)
            ?: LocalDate.now().also { p.edit().putString("install", it.toString()).apply() }

    /** Modo oscuro elegido a mano. Null: sigue al sistema. */
    var darkMode: Boolean?
        get() = if (p.contains("dark")) p.getBoolean("dark", false) else null
        set(v) = p.edit().apply { if (v == null) remove("dark") else putBoolean("dark", v) }.apply()

    /** Total visto la última vez que se abrió la app, para mostrar lo ganado mientras tanto. -1 si nunca. */
    var seenTotal: Int
        get() = p.getInt("seen_total", -1)
        set(v) = p.edit().putInt("seen_total", v).apply()

    var notifiedOn: LocalDate?
        get() = p.getString("notified", null)?.let(LocalDate::parse)
        set(v) = p.edit().putString("notified", v?.toString()).apply()

    /** Meta vigente en `date`. Un cambio aplica desde el día siguiente al que se hizo. */
    fun goalFor(date: LocalDate): Int {
        val from = p.getString("goal_from", null)?.let(LocalDate::parse)
        return if (from == null || !date.isBefore(from)) p.getInt("goal", GOAL_DEFAULT)
        else p.getInt("goal_prev", GOAL_DEFAULT)
    }

    /** Meta que va a regir mañana (lo que muestra el selector). */
    fun nextGoal(today: LocalDate) = goalFor(today.plusDays(1))

    fun setGoal(goal: Int, today: LocalDate) = p.edit()
        .putInt("goal_prev", goalFor(today))
        .putInt("goal", goal.coerceIn(GOAL_MIN, GOAL_MAX))
        .putString("goal_from", today.plusDays(1).toString())
        .apply()

    /** Meta del onboarding: rige desde hoy. */
    fun setInitialGoal(goal: Int) = p.edit()
        .putInt("goal", goal.coerceIn(GOAL_MIN, GOAL_MAX))
        .remove("goal_from")
        .apply()

    // Saldo (sección Puntos, sacada): movimientos y apuesta. Texto plano, un registro por línea.

    fun ledger(): List<Entry> = lines("ledger").map {
        val (d, a, l) = it.split(";", limit = 3)
        Entry(LocalDate.parse(d), a.toInt(), l)
    }

    fun addEntry(e: Entry) = setLines("ledger", lines("ledger") + "${e.date};${e.amount};${clean(e.label)}")

    /** Días salvados con comodín: cuentan para la racha, no dan bonus de meta. */
    var frozen: Set<LocalDate>
        get() = p.getStringSet("frozen", emptySet())!!.map(LocalDate::parse).toSet()
        set(v) = p.edit().putStringSet("frozen", v.map(LocalDate::toString).toSet()).apply()

    var bet: Bet?
        get() = p.getString("bet", null)?.split(";")?.let { Bet(LocalDate.parse(it[0]), it[1].toInt(), it[2].toInt()) }
        set(v) = p.edit().putString("bet", v?.let { "${it.date};${it.stake};${it.target}" }).apply()

    /** Sesión en curso: cuándo empezó y cuántos minutos dura. */
    var session: Pair<Long, Int>?
        get() = p.getString("session", null)?.split(";")?.let { it[0].toLong() to it[1].toInt() }
        set(v) = p.edit().putString("session", v?.let { "${it.first};${it.second}" }).apply()

    /** Último domingo con resumen semanal enviado. */
    var weeklyOn: LocalDate?
        get() = p.getString("weekly", null)?.let(LocalDate::parse)
        set(v) = p.edit().putString("weekly", v?.toString()).apply()

    // ---------- IRL ----------

    /** Id de esta instalación en IRL. Sin cuenta: un número al azar. */
    val myId: String
        get() = p.getString("my_id", null)
            ?: java.util.UUID.randomUUID().toString().take(8).also { p.edit().putString("my_id", it).apply() }

    /** Ya se miró si la instalación vino de una invitación (se mira una sola vez). */
    var inviteChecked: Boolean
        get() = p.getBoolean("invite_checked", false)
        set(v) = p.edit().putBoolean("invite_checked", v).apply()

    /**
     * Invitación con la que se instaló Off, todavía sin aceptar. Se entra al grupo al final del onboarding,
     * con los puntos de hoy ya calculados: así lo ganado antes de entrar no se reparte.
     */
    var invite: Invite?
        get() = p.getString("invite", null)?.split(";", limit = 3)?.let { Invite(Group(it[0], it[2]), it[1]) }
        set(v) = p.edit().putString("invite", v?.let { "${it.group.id};${it.host};${it.group.name}" }).apply()

    /** Último año con aviso de «Tu año off». */
    var wrappedYear: Int
        get() = p.getInt("wrapped_year", 0)
        set(v) = p.edit().putInt("wrapped_year", v).apply()

    /** Cómo te dicen: el saludo de Hoy y tu nombre en los grupos. */
    var nickname: String?
        get() = p.getString("nickname", null)
        set(v) = p.edit().putString("nickname", v).apply()

    /**
     * Grupos de IRL, en el orden en que se crearon. La primera vez migra la ronda única de antes
     * (keys sin prefijo) a un grupo llamado «Mi grupo».
     */
    val groups: List<Group>
        get() {
            if (!p.contains("groups")) migrateRonda()
            return lines("groups").map { val (id, name) = it.split(";", limit = 2); Group(id, name) }
        }

    fun addGroup(g: Group) = setLines("groups", lines("groups").filter { !it.startsWith("${g.id};") } + "${g.id};${clean(g.name)}")

    /** Salir de un grupo: se olvidan su pozo y sus encuentros. Los puntos ganados y el nombre quedan. */
    fun leaveGroup(id: String) {
        setLines("groups", lines("groups").filter { !it.startsWith("$id;") })
        p.edit().apply { p.all.keys.filter { it.startsWith("g:$id:") }.forEach(::remove) }.apply()
    }

    fun group(id: String) = GroupStore(id)

    /** Lo de cada grupo, con sus keys bajo «g:<id>:». */
    inner class GroupStore(val id: String) {
        private fun k(s: String) = "g:$id:$s"

        /** Ganado y debido acumulados hasta el último saldo en este grupo: lo que viaja es lo de después. */
        var settledEarned: Int
            get() = p.getInt(k("earned"), 0)
            set(v) = p.edit().putInt(k("earned"), v).apply()
        var settledDebt: Int
            get() = p.getInt(k("debt"), 0)
            set(v) = p.edit().putInt(k("debt"), v).apply()

        /** Juntada en curso: desde cuándo y con qué código. */
        var juntada: Pair<Long, String>?
            get() = p.getString(k("juntada"), null)?.split(";")?.let { it[0].toLong() to it[1] }
            set(v) = p.edit().putString(k("juntada"), v?.let { "${it.first};${it.second}" }).apply()

        /** Último código final aplicado: para no cobrar dos veces el mismo encuentro. */
        var lastFinal: String?
            get() = p.getString(k("last_final"), null)
            set(v) = p.edit().putString(k("last_final"), v).apply()

        /** Encuentro al que le falta escanear el código final. Se borra al saldar o al empezar otro. */
        var pending: Pending?
            get() = p.getString(k("pending"), null)?.let(::decodePending)
            set(v) = p.edit().putString(k("pending"), v?.let(::encodePending)).apply()

        /** Encuentros saldados (códigos finales), del más viejo al más nuevo. Guarda los últimos 30. */
        fun meetings(): List<String> = lines(k("meetings"))

        fun addMeeting(code: String) = setLines(k("meetings"), (meetings() + code).takeLast(30))
    }

    private fun migrateRonda() {
        val inRonda = p.getBoolean("in_ronda", p.contains("settled_earned"))
        if (!inRonda) return setLines("groups", emptyList())
        val g = Group(p.getString("ronda", null) ?: java.util.UUID.randomUUID().toString().take(8), "Mi grupo")
        val old = lines("meetings").ifEmpty { listOfNotNull(p.getString("last_meeting", null)) }
        p.edit()
            .putString("groups", "${g.id};${g.name}")
            .putInt("g:${g.id}:earned", p.getInt("settled_earned", 0))
            .putInt("g:${g.id}:debt", p.getInt("settled_debt", 0))
            .putString("g:${g.id}:juntada", p.getString("juntada", null))
            .putString("g:${g.id}:last_final", p.getString("last_final", null))
            .putString("g:${g.id}:meetings", old.joinToString("\n"))
            .apply()
    }

    /** Lo ganado o perdido en IRL, de todos los grupos. Suma al total y al nivel (a diferencia del viejo "ledger"). */
    fun rondaLedger(): List<Entry> = lines("ronda_ledger").map {
        val (d, a, l) = it.split(";", limit = 3)
        // Los movimientos de antes de IRL decían «ronda».
        Entry(LocalDate.parse(d), a.toInt(), l.replace("Pozo de la ronda", "Pozo"))
    }

    fun addRondaEntry(e: Entry) = setLines("ronda_ledger", lines("ronda_ledger") + "${e.date};${e.amount};${clean(e.label)}")

    private fun lines(key: String) = p.getString(key, "")!!.lines().filter { it.isNotBlank() }
    private fun setLines(key: String, v: List<String>) = p.edit().putString(key, v.joinToString("\n")).apply()
    private fun clean(s: String) = s.replace("\n", " ").trim()

    fun save(d: Day) = p.edit().putString(
        // Minutos por tipo de ejercicio en su propia key: "tipo:min;tipo:min".
        "x:${d.date}", d.exerciseByType.entries.joinToString(";") { "${it.key}:${it.value}" },
    ).putString(
        "d:${d.date}",
        listOf(
            d.blocks, d.socialMin, d.socialPenalty, d.goal, d.goalBonus, d.unlocks, if (d.closed) 1 else 0,
            d.restBonus, d.unlockBonus, d.steps, d.exerciseMin, d.sessionBonus,
        )
            .joinToString(","),
    ).apply()

    fun load(date: LocalDate): Day? = p.getString("d:$date", null)?.let { parse(date, it) }

    /** Todos los días guardados, del más viejo al más nuevo. */
    fun days(): List<Day> = p.all.keys.filter { it.startsWith("d:") }
        .map { LocalDate.parse(it.removePrefix("d:")) }
        .sorted()
        .mapNotNull(::load)

    private fun parse(date: LocalDate, s: String): Day {
        val v = s.split(",").map(String::toInt)
        // Los campos del final llegaron después: los días viejos no los tienen.
        fun at(i: Int) = v.getOrElse(i) { 0 }
        val byType = p.getString("x:$date", "")!!.split(";").filter { it.isNotBlank() }
            .associate { val (t, m) = it.split(":"); t.toInt() to m.toInt() }
        return Day(date, v[0], v[1], v[2], v[3], v[4], v[5], v[6] == 1, at(7), at(8), at(9), at(10), byType, at(11))
    }
}

/** Un grupo de IRL: su id viaja en los QR y el nombre lo pone quien lo crea. */
data class Group(val id: String, val name: String)
