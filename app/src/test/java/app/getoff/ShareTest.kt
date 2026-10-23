package app.getoff

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.URLDecoder
import java.time.LocalDate

class ShareTest {
    @Test
    fun inviteRoundTrip() {
        val i = Invite(Group("a1b2c3d4", "Los del & asado = 1"), "Juli")
        // Play le pasa a la app el referrer ya decodificado una vez.
        val referrer = URLDecoder.decode(inviteLink("app.getoff", i).substringAfter("referrer="), "UTF-8")
        assertEquals(i, parseInvite(referrer))
    }

    @Test
    fun commonInstallIsNotAnInvite() {
        assertNull(parseInvite("utm_source=google-play&utm_medium=organic"))
        assertNull(parseInvite("g=../../x&n=a"))
    }

    @Test
    fun wrappedOnlyAroundNewYear() {
        assertEquals(2026, wrappedYear(LocalDate.of(2026, 12, 1)))
        assertEquals(2026, wrappedYear(LocalDate.of(2027, 1, 15)))
        assertNull(wrappedYear(LocalDate.of(2027, 1, 16)))
        assertNull(wrappedYear(LocalDate.of(2026, 11, 30)))
    }
}
