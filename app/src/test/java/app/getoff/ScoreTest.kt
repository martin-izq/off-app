package app.getoff

import org.junit.Assert.assertEquals
import org.junit.Test

class ScoreTest {
    private val min = 60_000L
    private fun lock(t: Long) = Ev(t * min, KEYGUARD_SHOWN)
    private fun unlock(t: Long) = Ev(t * min, KEYGUARD_HIDDEN)

    @Test fun blocksCountWholeHalfHours() {
        val evs = listOf(lock(0), unlock(29), lock(30), unlock(60), lock(100), unlock(159), lock(200), unlock(260))
        // 29 → 0, 30 → 1, 59 → 1, 60 → 2
        assertEquals(4, blockPoints(lockedIntervals(evs, 0, 1000 * min)))
    }

    @Test fun lockedIsClippedToWindow() {
        // Bloqueado de 06:00 a 09:10: solo cuenta desde las 08:00 → 70 min → 2 bloques.
        val evs = listOf(lock(360), unlock(550))
        assertEquals(2, blockPoints(lockedIntervals(evs, 480 * min, 1440 * min)))
    }

    @Test fun openLockCountsUntilNowAndLeadingUnlockIsIgnored() {
        val evs = listOf(unlock(10), lock(20), unlock(25), lock(100))
        assertEquals(listOf(20 * min..25 * min, 100 * min..170 * min), lockedIntervals(evs, 0, 170 * min))
    }

    @Test fun socialMinutesOnlyCountsSocialApps() {
        val evs = listOf(
            Ev(0, RESUMED, "com.instagram.android"), Ev(10 * min, PAUSED, "com.instagram.android"),
            Ev(10 * min, RESUMED, "com.whatsapp"), Ev(50 * min, PAUSED, "com.whatsapp"),
            Ev(60 * min, RESUMED, "com.zhiliaoapp.musically"), // sigue abierta
        )
        assertEquals(10 + 5, socialMinutes(evs, 0, 65 * min))
    }

    @Test fun socialPenaltyOnlyAboveAverageAndCapped() {
        val week = List(7) { 60 }
        assertEquals(0, socialPenalty(500, List(6) { 60 })) // sin semana no hay promedio
        assertEquals(0, socialPenalty(0, week)) // usar poco no suma
        assertEquals(0, socialPenalty(64, week))
        assertEquals(4, socialPenalty(80, week)) // 20 min de más → −4
        assertEquals(SOCIAL_PENALTY_CAP, socialPenalty(1000, week))
    }

    @Test fun goalBonusIsTwentyPercentAtLeastOne() {
        assertEquals(1, goalBonus(2))
        assertEquals(2, goalBonus(10))
        assertEquals(3, goalBonus(13))
    }

    @Test fun goalBonusGrowsWithStreakUpToDouble() {
        assertEquals(2, goalBonus(10, 0))
        assertEquals(3, goalBonus(10, 3)) // 2 × 1,43
        assertEquals(4, goalBonus(10, 7))
        assertEquals(4, goalBonus(10, 30))
    }

    @Test fun longRestPaysPerFullTwoHours() {
        val h = 3_600_000L
        assertEquals(0, longRestPoints(listOf(0L..h + h / 2)))
        assertEquals(1, longRestPoints(listOf(0L..2 * h)))
        assertEquals(2, longRestPoints(listOf(0L..4 * h + 1, 5 * h..6 * h)))
    }

    @Test fun betPaysDoubleRefundsWithoutData() {
        val bet = Bet(java.time.LocalDate.of(2026, 1, 1), 20, 15)
        fun day(blocks: Int, penalty: Int) = Day(bet.date, blocks, 0, penalty, 10, 2, 0, true)
        assertEquals(40, betReturn(bet, day(15, 0)))
        assertEquals(0, betReturn(bet, day(16, 2))) // las redes también cuentan
        assertEquals(20, betReturn(bet, null))
    }

    @Test fun fiveLevels() {
        assertEquals(listOf(0, 150, 500, 1200, 2500), (1..5).map(::levelStart))
        assertEquals(1, level(-20))
        assertEquals(1, level(149))
        assertEquals(2, level(150))
        assertEquals(4, level(2499))
        assertEquals(5, level(2500))
        assertEquals(5, level(99_999))
    }

    @Test fun streakIgnoresUnfinishedToday() {
        assertEquals(3, streak(listOf(false, true, true, true, false, true)))
        assertEquals(2, streak(listOf(true, true, false)))
        assertEquals(0, streak(listOf(false, false, true)))
    }

    @Test fun keptByDateBreaksOnMissingDays() {
        val d0 = java.time.LocalDate.of(2026, 10, 1)
        fun met(i: Long) = Day(d0.plusDays(i), 0, 0, 0, 10, 2, 0, true)
        // Días 0, 1 y 3 cumplidos; el 2 no se guardó, el 4 es hoy sin cumplir.
        val kept = keptByDate(listOf(met(0), met(1), met(3)), emptySet(), d0.plusDays(4))
        assertEquals(listOf(true, true, false, true, false), kept)
        assertEquals(1, streak(kept.reversed()))
        // Un comodín en el día faltante lo salva.
        assertEquals(4, streak(keptByDate(listOf(met(0), met(1), met(3)), setOf(d0.plusDays(2)), d0.plusDays(4)).reversed()))
    }

    @Test fun bestStreakIsLongestRun() {
        assertEquals(3, bestStreak(listOf(true, true, false, true, true, true, false)))
        assertEquals(0, bestStreak(listOf(false, false)))
        assertEquals(0, bestStreak(emptyList()))
    }

    @Test fun stepPointsHalveAfterTenThousand() {
        assertEquals(0, stepPoints(999))
        assertEquals(10, stepPoints(10_000))
        assertEquals(11, stepPoints(12_000))
        assertEquals(25, stepPoints(40_000))
    }

    @Test fun exercisePointsHalveAfterOneHour() {
        assertEquals(6, exercisePoints(60))
        assertEquals(9, exercisePoints(120))
    }

    @Test fun lockedStepsOnlyCountOverlap() {
        val locked = listOf(0L..30 * min)
        val records = listOf(
            Steps(0L..10 * min, 1_000), // adentro
            Steps(20 * min..40 * min, 2_000), // mitad adentro
            Steps(50 * min..60 * min, 5_000), // afuera
        )
        assertEquals(2_000, lockedSteps(records, locked))
    }

    @Test fun unlockPointsOnlyBelowWeeklyAverage() {
        val week = List(7) { 60 }
        assertEquals(0, unlockPoints(0, List(6) { 60 })) // sin semana no hay promedio
        assertEquals(0, unlockPoints(80, week))
        assertEquals(2, unlockPoints(49, week))
    }

    @Test fun overlappingSessionsCountOnce() {
        val sessions = listOf(10 * min..70 * min, 40 * min..100 * min, 200 * min..230 * min)
        assertEquals(90 + 20, exerciseMinutes(sessions, 0, 220 * min))
    }

    @Test fun exerciseSplitsByType() {
        val sessions = listOf(1 to 0L..40 * min, 2 to 60 * min..110 * min, 1 to 30 * min..50 * min)
        assertEquals(mapOf(1 to 50, 2 to 50), exerciseByType(sessions, 0, 1000 * min))
    }

    @Test fun sessionPaysOnlyIfLockedThroughout() {
        val start = 1000 * min
        // Bloqueó al minuto y aguantó la hora: +2.
        assertEquals(2, sessionPoints(listOf(1001 * min..1060 * min), start, 60))
        // Bloqueó tarde (pasada la gracia): nada.
        assertEquals(0, sessionPoints(listOf(1003 * min..1060 * min), start, 60))
        // Desbloqueó a la mitad: nada, aunque después volviera a bloquear.
        assertEquals(0, sessionPoints(listOf(1001 * min..1030 * min, 1031 * min..1060 * min), start, 60))
        assertEquals(4, sessionPoints(listOf(990 * min..1200 * min), start, 120))
    }

    @Test fun recoveredCountsMinutesUnderEachDaysAverage() {
        val history = List(7) { 60 }
        // Semana: 30 de menos el primer día, 0 el resto (iguales o arriba del promedio).
        assertEquals(30, recoveredMinutes(history + listOf(30, 60, 90)))
        // Sin semana previa no hay promedio: nada.
        assertEquals(0, recoveredMinutes(listOf(10, 20, 30)))
    }

    private fun day(date: String, met: Boolean, closed: Boolean = true) =
        Day(java.time.LocalDate.parse(date), 0, 0, 0, 10, if (met) 2 else 0, 0, closed)

    @Test fun freezeSavesOneMissPerWeekOnlyWithStreak() {
        fun d(s: String) = java.time.LocalDate.parse(s)
        val from = d("2026-01-01")
        // Lun 5 a dom 11 de enero. Racha lun–mar, falla mié (salvado), cumple jue, falla vie (sin comodín).
        val week = listOf(
            day("2026-01-05", true), day("2026-01-06", true), day("2026-01-07", false),
            day("2026-01-08", true), day("2026-01-09", false),
        )
        assertEquals(setOf(d("2026-01-07")), autoFreeze(week, emptySet(), from))
        // Sin racha no se gasta.
        assertEquals(emptySet<java.time.LocalDate>(), autoFreeze(listOf(day("2026-01-05", false)), emptySet(), from))
        // Hoy (sin cerrar) no se salva todavía.
        assertEquals(emptySet<java.time.LocalDate>(),
            autoFreeze(listOf(day("2026-01-05", true), day("2026-01-06", false, closed = false)), emptySet(), from))
        // Semana nueva, comodín nuevo: lun 12 sin meta tras dom 11 cumplido.
        assertEquals(setOf(d("2026-01-12")),
            autoFreeze(listOf(day("2026-01-11", true), day("2026-01-12", false)), setOf(d("2026-01-07")), from) - d("2026-01-07"))
        // Salvado que después se cumple: vuelve el comodín.
        assertEquals(emptySet<java.time.LocalDate>(),
            autoFreeze(listOf(day("2026-01-05", true), day("2026-01-06", true)), setOf(d("2026-01-06")), from))
    }
}
