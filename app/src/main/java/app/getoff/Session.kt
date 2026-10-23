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
    store.session = null
    val locked = lockedIntervals(readEvents(ctx, start - 12 * 3_600_000L, end), start, end)
    val bonus = sessionPoints(locked, start, minutes)
    if (bonus == 0) return
    // ponytail: una sesión que cruza la medianoche suma todo al día en que empezó.
    val date = Instant.ofEpochMilli(start).atZone(ZoneId.systemDefault()).toLocalDate()
    val day = store.load(date) ?: sync(ctx).takeIf { it.date == date } ?: return
    store.save(day.copy(sessionBonus = day.sessionBonus + bonus))
    sync(ctx) // por si el bonus completa la meta
    notify(ctx, 2, t("Sesión cumplida: +${bonus * 2} puntos", "Session done: +${bonus * 2} points"), t("${duration(minutes)} sin desbloquear. Los bloques valieron doble.", "${duration(minutes)} without unlocking. The blocks counted double."))
}

/** Desbloquear pasada la gracia termina la sesión, sin castigo. */
fun dropBrokenSession(ctx: Context, store: Store, now: Long = System.currentTimeMillis()) {
    val (start, _) = store.session ?: return
    if (now < start + SESSION_GRACE_MS) return
    val locked = lockedIntervals(readEvents(ctx, start - 12 * 3_600_000L, now), start, now)
    if (locked.none { it.first <= start + SESSION_GRACE_MS && it.last >= now }) cancelSession(ctx)
}

class SessionWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        if (hasUsageAccess(applicationContext)) finishSession(applicationContext)
        return Result.success()
    }
}
