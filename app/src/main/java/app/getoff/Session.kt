package app.getoff

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.sync.withLock

/** Empieza una sesión ahora. Un worker la cierra un minuto después del final. */
fun startSession(ctx: Context, minutes: Int) {
    Store(ctx).session = System.currentTimeMillis() to minutes
    WorkManager.getInstance(ctx).enqueueUniqueWork(
        "session",
        ExistingWorkPolicy.REPLACE,
        OneTimeWorkRequestBuilder<SessionWorker>().setInitialDelay(minutes + 1L, TimeUnit.MINUTES).build(),
    )
}

fun cancelSession(ctx: Context) {
    Store(ctx).session = null
    WorkManager.getInstance(ctx).cancelUniqueWork("session")
}

/**
 * Si la sesión ya terminó: acredita el bonus al día en que empezó y avisa. La llaman el worker de la sesión,
 * el de 15 min y la app; la primera que llega la cierra.
 */
suspend fun finishSession(ctx: Context, now: Long = System.currentTimeMillis()) {
    val store = Store(ctx)
    val (start, minutes) = store.session ?: return
    val end = start + minutes * 60_000L
    if (now < end) return
    val bonus = sessionPoints(lockedBetween(ctx, start, end), start, minutes)
    // ponytail: una sesión que cruza la medianoche suma todo al día en que empezó.
    val date = Instant.ofEpochMilli(start).atZone(ZoneId.systemDefault()).toLocalDate()
    if (bonus > 0 && store.load(date) == null) sync(ctx) // el día de la sesión todavía no se guardó
    dayLock.withLock {
        // Otro (worker o app) ya la cerró mientras tanto.
        if (store.session?.first != start) return
        store.session = null
        if (bonus == 0) return
        // Con eventos para dar bonus, sync siempre guarda el día: esto no debería pasar.
        val day = store.load(date) ?: return
        store.save(day.copy(sessionBonus = day.sessionBonus + bonus))
    }
    sync(ctx) // por si el bonus completa la meta
    notify(ctx, 2, t("Sesión cumplida: +$bonus puntos", "Session done: +$bonus points"), t("${duration(minutes)} sin desbloquear. Los bloques valieron doble.", "${duration(minutes)} without unlocking. The blocks counted double."))
}

/** Desbloquear pasada la gracia termina la sesión, sin castigo. */
fun dropBrokenSession(ctx: Context, store: Store, now: Long = System.currentTimeMillis()) {
    val (start, _) = store.session ?: return
    if (now < start + SESSION_GRACE_MS) return
    if (!sessionKept(lockedBetween(ctx, start, now), start, now)) cancelSession(ctx)
}

class SessionWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        if (hasUsageAccess(applicationContext)) finishSession(applicationContext)
        return Result.success()
    }
}
