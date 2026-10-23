package app.getoff

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.util.concurrent.TimeUnit

/** Cada 15 min (mínimo de WorkManager): guarda puntajes y avisa una vez por día al cumplir la meta. */
class ScoreWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        if (!hasUsageAccess(ctx)) return Result.success()
        finishSession(ctx)
        val today = sync(ctx)
        val store = Store(ctx)
        if (today.met && store.notifiedOn != today.date && !today.date.isBefore(store.installDate)) {
            notify(ctx, 1, t("Meta cumplida: +${today.goalBonus} puntos", "Goal met: +${today.goalBonus} points"), t("Llegaste a ${today.goal}. Tiempo bien ganado.", "You reached ${today.goal}. Time well spent."))
            store.notifiedOn = today.date
        }
        // Resumen semanal: uno solo, el domingo después de las 20 h.
        if (today.date.dayOfWeek == DayOfWeek.SUNDAY && LocalTime.now().hour >= 20 && store.weeklyOn != today.date) {
            weeklySummary(store, today.date)?.let { (title, text) -> notify(ctx, 3, title, text, tab = 2) }
            store.weeklyOn = today.date
        }
        // «Tu año off»: un aviso el 28 de diciembre (o después, si ese día no corrió) para compartir el año.
        if (today.date.monthValue == 12 && today.date.dayOfMonth >= 28 && store.wrappedYear != today.date.year) {
            notify(ctx, 4, t("Tu ${today.date.year} off está listo", "Your ${today.date.year} off is ready"),
                t("Mirá cuánto tiempo le ganaste a la pantalla y compartilo.", "See how much time you won back from the screen, and share it."), tab = 2)
            store.wrappedYear = today.date.year
        }
        return Result.success()
    }

    companion object {
        fun schedule(ctx: Context) = WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
            "score",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<ScoreWorker>(15, TimeUnit.MINUTES).build(),
        )
    }
}

/** Título y texto del resumen de la semana que termina en `sunday`. Null si no hay días para resumir. */
private fun weeklySummary(store: Store, sunday: LocalDate): Pair<String, String>? {
    // Días seguidos hacia atrás, con dos semanas para tener el promedio de redes de cada día.
    val days = generateSequence(sunday) { it.minusDays(1) }.take(7 + HISTORY_DAYS)
        .map(store::load).takeWhile { it != null }.filterNotNull().toList().reversed()
    val week = days.takeLast(7).filter { !it.date.isBefore(store.installDate) }
    if (week.isEmpty()) return null
    val recovered = recoveredMinutes(days.map { it.socialMin })
    val points = week.sumOf { it.total }
    val met = week.count { it.met }
    val metText = t("$met ${if (met == 1) "día" else "días"} con la meta cumplida.", "$met ${if (met == 1) "day" else "days"} with your goal met.")
    return if (recovered > 0) t("Tu semana: recuperaste ${duration(recovered)}", "Your week: you took back ${duration(recovered)}") to t("$points puntos y $metText", "$points points and $metText")
    else t("Tu semana: $points puntos", "Your week: $points points") to metText.replaceFirstChar { it.uppercase() }
}

/** Un aviso de la app. Tocarlo abre la pestaña `tab` (0 Hoy, 2 Historial). */
@SuppressLint("MissingPermission") // chequeado abajo
internal fun notify(ctx: Context, id: Int, title: String, text: String, tab: Int = 0) {
    if (Build.VERSION.SDK_INT >= 33 &&
        ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    ) return
    // Un solo canal: como mucho son tres avisos (meta, sesión y resumen del domingo).
    ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(
        NotificationChannel("meta", t("Avisos", "Alerts"), NotificationManager.IMPORTANCE_DEFAULT),
    )
    val open = PendingIntent.getActivity(
        ctx, id, Intent(ctx, MainActivity::class.java).putExtra("tab", tab),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    val n = NotificationCompat.Builder(ctx, "meta")
        .setSmallIcon(android.R.drawable.star_on)
        .setContentTitle(title)
        .setContentText(text)
        .setContentIntent(open)
        .setAutoCancel(true)
        .build()
    NotificationManagerCompat.from(ctx).notify(id, n)
}
