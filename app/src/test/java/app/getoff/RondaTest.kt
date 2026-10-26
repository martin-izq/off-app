package app.getoff

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RondaTest {
    private val min = 60_000L

    @Test fun potGoesToWhoEarnedInProportion() {
        val r = settle(listOf(Summary("a", 30, 0), Summary("b", 10, 20), Summary("c", 0, 0)))
        assertEquals(mapOf("a" to 15, "b" to 5, "c" to 0), r)
    }

    @Test fun roundingRestGoesToTopEarner() {
        val r = settle(listOf(Summary("a", 1, 0), Summary("b", 1, 0), Summary("c", 1, 10)))
        assertEquals(10, r.values.sum())
        assertEquals(mapOf("a" to 4, "b" to 3, "c" to 3), r)
    }

    @Test fun noEarnedOrNoDebtMeansNoSettlement() {
        assertEquals(emptyMap<String, Int>(), settle(listOf(Summary("a", 0, 10), Summary("b", 0, 0))))
        assertEquals(emptyMap<String, Int>(), settle(listOf(Summary("a", 10, 0))))
    }

    @Test fun togetherCountsOnlyWhenEveryoneIsLocked() {
        val a = listOf(0L..120 * min)
        val b = listOf(10 * min..50 * min, 60 * min..130 * min)
        // Juntos: 10–50 (40 min → 1 bloque) y 60–120 (60 min → 2 bloques).
        assertEquals(3, togetherBonus(listOf(a, b), 0, 200 * min))
    }

    @Test fun firstUnlockStartsCountingWhenTheLastOneLocks() {
        val locked = mapOf(
            "ana" to listOf(5 * min..40 * min), // bloqueó primero, desbloquea a los 40
            "beto" to listOf(10 * min..30 * min), // desbloquea a los 30: paga
        )
        assertEquals("beto", firstToUnlock(locked, 0, 100 * min))
        assertNull(firstToUnlock(locked, 0, 20 * min)) // nadie desbloqueó todavía
        assertNull(firstToUnlock(mapOf("ana" to listOf(0L..10 * min), "beto" to listOf(20 * min..30 * min)), 0, 100 * min))
    }

    @Test fun codesRoundTrip() {
        val from = 1_700_000_000_000L
        val f = Final("n1", "r1", from, from + 120 * min, listOf(
            Member("a", "Ana", 30, 0, listOf(from..from + 60 * min)),
            Member("b", "Beto", 10, 20, emptyList()),
        ))
        assertEquals(f, decode(encode(f)))
        val sala = Sala(true, "n1", "r1", 0, from, "Ana")
        assertEquals(sala, decode(encode(sala)))
        assertEquals(Mine("n1", Member("a", "Ana Pérez", 3, 1)), decode(encode(Mine("n1", Member("a", "Ana|Pérez", 3, 1)))))
        assertNull(decode("https://otra.cosa"))
    }

    @Test fun outcomeChargesFineIntoThePot() {
        val f = Final("n", "r", 1, 121 * min, listOf(
            Member("a", "Ana", 10, 0, listOf(1L..121 * min)),
            Member("b", "Beto", 10, 0, listOf(1L..61 * min)), // desbloquea primero
        ))
        val o = outcome(f).associateBy { it.id }
        assertEquals(2, o.getValue("a").bonus) // 60 min juntos bloqueados
        assertEquals(5, o.getValue("b").fine)
        assertEquals(5, o.values.sumOf { it.share }) // la multa se reparte
    }

    @Test fun salaCarriesGroupNameAndReadsOldCodes() {
        val sala = Sala(false, "n1", "g1", 0, 60_000, "Ana", "Los del jueves")
        assertEquals(sala, decode(encode(sala)))
        // Código de antes de los grupos: sin nombre.
        assertEquals("", (decode("D1|S|S|n1|g1|0|60000|Ana") as Sala).group)
    }

    @Test fun hostMemberMatchesFinalAfterNormalizing() {
        // Bloqueos con segundos, como salen de myMember: el código final los trunca al minuto.
        val raw = Member("a", "Ana", 3, 1, listOf(1_000_027_000L..1_000_500_500L))
        val me = normalized("n1", raw)
        val final = decode(encode(Final("n1", "g", 960_000_000L, 1_200_000_000L, listOf(me)))) as Final
        assertEquals(me, final.members.single())
    }

    @Test fun pendingRoundTrip() {
        val p = Pending(Mine("n1", Member("a", "Ana", 12, 3)), "Sofi", 1_700_000_000_000L)
        assertEquals(p, decodePending(encodePending(p)))
        assertEquals("So fi", decodePending(encodePending(p.copy(host = "So~fi")))?.host)
        assertNull(decodePending("roto"))
    }

    @Test fun segmentsFollowBlocksOfEachLock() {
        // 70 min bloqueado: dos bloques y un tercio del siguiente; 10 min: un tercio.
        assertEquals(listOf(1f, 1f, 1f / 3), lockSegments(listOf(0L..70 * min)))
        assertEquals(blockPoints(listOf(0L..70 * min)), lockSegments(listOf(0L..70 * min)).count { it >= 1f })
        assertEquals(emptyList<Float>(), lockSegments(emptyList()))
    }

    @Test fun rotationPutsFindersTopLeftTopRightBottomRight() {
        val bits = com.google.zxing.qrcode.QRCodeWriter().encode("D1|M|n1|a,Ana,1,0,", com.google.zxing.BarcodeFormat.QR_CODE, 0, 0,
            mapOf(com.google.zxing.EncodeHintType.MARGIN to 0))
        val m = rotateCw(List(bits.height) { y -> BooleanArray(bits.width) { x -> bits[x, y] } })
        val n = m.size
        // El anillo de un cuadrado de búsqueda: borde negro de 7×7 y blanco adentro.
        fun finder(r0: Int, c0: Int) = (0 until 7).all { m[r0][c0 + it] && m[r0 + 6][c0 + it] && m[r0 + it][c0] && m[r0 + it][c0 + 6] } &&
            !m[r0 + 1][c0 + 1]
        assertTrue(finder(0, 0) && finder(0, n - 7) && finder(n - 7, n - 7))
        assertTrue(!finder(n - 7, 0))
        // Girar cuatro veces vuelve al mismo.
        assertEquals(m.map { it.toList() }, rotateCw(rotateCw(rotateCw(rotateCw(m)))).map { it.toList() })
    }
}
