package app.getoff

import android.content.Context
import android.os.RemoteException
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.AggregateGroupByDurationRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.io.IOException
import java.time.Duration
import java.time.Instant

val HEALTH_PERMISSIONS = setOf(
    HealthPermission.getReadPermission(StepsRecord::class),
    HealthPermission.getReadPermission(ExerciseSessionRecord::class),
)

/** Estas ya suman por pasos: no suman además por minutos. */
private val STEP_EXERCISES = setOf(
    ExerciseSessionRecord.EXERCISE_TYPE_WALKING,
    ExerciseSessionRecord.EXERCISE_TYPE_RUNNING,
    ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL,
    ExerciseSessionRecord.EXERCISE_TYPE_HIKING,
    ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING,
    ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING_MACHINE,
)

enum class Health { NONE, INSTALL, ASK, ON }

private fun client(ctx: Context): HealthConnectClient? =
    if (HealthConnectClient.getSdkStatus(ctx) == HealthConnectClient.SDK_AVAILABLE) HealthConnectClient.getOrCreate(ctx) else null

suspend fun healthState(ctx: Context): Health = when (HealthConnectClient.getSdkStatus(ctx)) {
    HealthConnectClient.SDK_AVAILABLE ->
        if (client(ctx)!!.permissionController.getGrantedPermissions().containsAll(HEALTH_PERMISSIONS)) Health.ON else Health.ASK
    HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> Health.INSTALL
    else -> Health.NONE
}

/** Lo que se pide al conectar. Leer en segundo plano, si el sistema lo ofrece, para que el worker vea los pasos. */
fun healthRequest(ctx: Context): Set<String> {
    val bg = client(ctx)?.features?.getFeatureStatus(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND) ==
        HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
    return if (bg) HEALTH_PERMISSIONS + HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND else HEALTH_PERMISSIONS
}

/** Pasos (en tramos de 5 min) y sesiones de ejercicio sin pasos, cargadas a mano excluidas. */
data class Activity(val steps: List<Steps>, val exercise: List<Pair<Int, LongRange>>)

/**
 * Actividad de [from, to). Null sin Health Connect, sin permiso, o en segundo plano sin el permiso para eso:
 * quien llama se queda con lo último que leyó.
 */
suspend fun readActivity(ctx: Context, from: Long, to: Long): Activity? {
    val client = client(ctx) ?: return null
    val range = TimeRangeFilter.between(Instant.ofEpochMilli(from), Instant.ofEpochMilli(to))
    return try {
        // Agregado y no registros crudos: Health Connect saca los duplicados entre celu y reloj.
        val steps = client.aggregateGroupByDuration(
            AggregateGroupByDurationRequest(setOf(StepsRecord.COUNT_TOTAL), range, Duration.ofMinutes(5)),
        ).mapNotNull { g ->
            g.result[StepsRecord.COUNT_TOTAL]?.let { Steps(g.startTime.toEpochMilli()..g.endTime.toEpochMilli(), it) }
        }
        val exercise = client.readRecords(ReadRecordsRequest(ExerciseSessionRecord::class, range)).records
            .filter { it.metadata.recordingMethod != Metadata.RECORDING_METHOD_MANUAL_ENTRY && it.exerciseType !in STEP_EXERCISES }
            .map { it.exerciseType to it.startTime.toEpochMilli()..it.endTime.toEpochMilli() }
        Activity(steps, exercise)
    } catch (e: SecurityException) {
        null
    } catch (e: RemoteException) {
        null
    } catch (e: IOException) {
        null
    } catch (e: IllegalStateException) {
        null
    }
}

private val EXERCISE_NAMES get() = mapOf(
    ExerciseSessionRecord.EXERCISE_TYPE_BIKING to t("Bici", "Cycling"),
    ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY to t("Bici fija", "Stationary bike"),
    ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING to t("Pesas", "Weights"),
    ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING to t("Fuerza", "Strength"),
    ExerciseSessionRecord.EXERCISE_TYPE_CALISTHENICS to t("Calistenia", "Calisthenics"),
    ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING to "HIIT",
    ExerciseSessionRecord.EXERCISE_TYPE_BOOT_CAMP to t("Funcional", "Boot camp"),
    ExerciseSessionRecord.EXERCISE_TYPE_EXERCISE_CLASS to t("Clase", "Class"),
    ExerciseSessionRecord.EXERCISE_TYPE_ELLIPTICAL to t("Elíptica", "Elliptical"),
    ExerciseSessionRecord.EXERCISE_TYPE_ROWING to t("Remo", "Rowing"),
    ExerciseSessionRecord.EXERCISE_TYPE_ROWING_MACHINE to t("Remo (máquina)", "Rowing machine"),
    ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL to t("Natación", "Swimming"),
    ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER to t("Natación (aguas abiertas)", "Open water swimming"),
    ExerciseSessionRecord.EXERCISE_TYPE_YOGA to "Yoga",
    ExerciseSessionRecord.EXERCISE_TYPE_PILATES to "Pilates",
    ExerciseSessionRecord.EXERCISE_TYPE_STRETCHING to t("Elongación", "Stretching"),
    ExerciseSessionRecord.EXERCISE_TYPE_DANCING to t("Baile", "Dancing"),
    ExerciseSessionRecord.EXERCISE_TYPE_MARTIAL_ARTS to t("Artes marciales", "Martial arts"),
    ExerciseSessionRecord.EXERCISE_TYPE_BOXING to t("Boxeo", "Boxing"),
    ExerciseSessionRecord.EXERCISE_TYPE_ROCK_CLIMBING to t("Escalada", "Climbing"),
    ExerciseSessionRecord.EXERCISE_TYPE_SOCCER to t("Fútbol", "Soccer"),
    ExerciseSessionRecord.EXERCISE_TYPE_BASKETBALL to t("Básquet", "Basketball"),
    ExerciseSessionRecord.EXERCISE_TYPE_VOLLEYBALL to t("Vóley", "Volleyball"),
    ExerciseSessionRecord.EXERCISE_TYPE_HANDBALL to "Handball",
    ExerciseSessionRecord.EXERCISE_TYPE_RUGBY to "Rugby",
    ExerciseSessionRecord.EXERCISE_TYPE_TENNIS to t("Tenis", "Tennis"),
    ExerciseSessionRecord.EXERCISE_TYPE_TABLE_TENNIS to t("Ping pong", "Table tennis"),
    ExerciseSessionRecord.EXERCISE_TYPE_SQUASH to "Squash",
    ExerciseSessionRecord.EXERCISE_TYPE_BADMINTON to t("Bádminton", "Badminton"),
    ExerciseSessionRecord.EXERCISE_TYPE_SKATING to t("Patín", "Skating"),
    ExerciseSessionRecord.EXERCISE_TYPE_SURFING to "Surf",
    ExerciseSessionRecord.EXERCISE_TYPE_PADDLING to t("Kayak / remo", "Paddling"),
    ExerciseSessionRecord.EXERCISE_TYPE_GOLF to "Golf",
)

fun exerciseName(type: Int): String = EXERCISE_NAMES[type] ?: t("Otro", "Other")
