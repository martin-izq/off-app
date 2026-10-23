package app.getoff

import android.content.Context
import android.content.Intent
import com.android.installreferrer.api.InstallReferrerClient
import com.android.installreferrer.api.InstallReferrerStateListener
import java.net.URLDecoder
import java.net.URLEncoder

// Invitar a quien todavía no tiene Off: un link de Play Store que lleva el grupo en el «referrer».
// Si instala desde ese link, Play le pasa el referrer a la app y entra sola al grupo. Sin servidor:
// el pozo se sigue saldando en persona.

data class Invite(val group: Group, val host: String)

private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

fun inviteLink(pkg: String, i: Invite) = "https://play.google.com/store/apps/details?id=$pkg&referrer=" +
    enc("g=${enc(i.group.id)}&n=${enc(i.group.name)}&h=${enc(i.host)}")

/** Null si el referrer no es una invitación (una instalación común trae «utm_source=…»). Viene de afuera: se limpia todo. */
fun parseInvite(referrer: String): Invite? {
    val kv = referrer.split("&").mapNotNull { pair ->
        pair.split("=", limit = 2).takeIf { it.size == 2 }?.let { (k, v) -> k to runCatching { URLDecoder.decode(v, "UTF-8") }.getOrNull() }
    }.toMap()
    val id = kv["g"]?.takeIf { it.matches(Regex("[0-9a-f]{8}")) } ?: return null
    val host = cleanName(kv["h"].orEmpty())
    val name = cleanName(kv["n"].orEmpty()).ifEmpty { t("Grupo de ${host.ifEmpty { "amigos" }}", "${host.ifEmpty { "Friends" }}'s group") }
    return Invite(Group(id, name), host)
}

/** Abre el menú de compartir (WhatsApp y los demás) con el link de invitación al grupo. */
fun shareInvite(ctx: Context, i: Invite) {
    val text = t(
        "Juguemos a Off en «${i.group.name}»: el que desbloquea primero, paga. Bajala y cuando nos juntemos escaneás mi código: ",
        "Let's play Off in “${i.group.name}”: first to unlock pays. Get it, and when we meet you scan my code: ",
    ) + inviteLink(ctx.packageName, i)
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    ctx.startActivity(Intent.createChooser(send, t("Invitar a «${i.group.name}»", "Invite to “${i.group.name}”")))
}

/** Una sola vez, en la primera apertura: si Off se instaló desde una invitación, la guarda para el final del onboarding. */
fun checkInvite(ctx: Context, onFound: () -> Unit) {
    val store = Store(ctx)
    if (store.inviteChecked) return
    val client = InstallReferrerClient.newBuilder(ctx).build()
    runCatching {
        client.startConnection(object : InstallReferrerStateListener {
            override fun onInstallReferrerSetupFinished(code: Int) {
                // Sin servicio se reintenta en la próxima apertura; cualquier otra respuesta es definitiva.
                if (code != InstallReferrerClient.InstallReferrerResponse.SERVICE_UNAVAILABLE) store.inviteChecked = true
                if (code == InstallReferrerClient.InstallReferrerResponse.OK) {
                    runCatching { client.installReferrer.installReferrer }.getOrNull()?.let(::parseInvite)?.let {
                        store.invite = it
                        onFound()
                    }
                }
                client.endConnection()
            }

            override fun onInstallReferrerServiceDisconnected() {}
        })
    }
}
