package app.getoff

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.key
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseInOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val resumes = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            androidx.activity.SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            androidx.activity.SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        ScoreWorker.schedule(this)
        val rationale = intent?.action in setOf(
            "androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE", "android.intent.action.VIEW_PERMISSION_USAGE",
        )
        // Los avisos abren una pestaña: el resumen del domingo, Historial.
        val tab = intent?.getIntExtra("tab", 0) ?: 0
        setContent {
            Themed(window) {
                androidx.compose.material3.ProvideTextStyle(androidx.compose.ui.text.TextStyle(fontFamily = Geist)) {
                    if (rationale) HealthRationale() else App(resumes.intValue, tab)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumes.intValue++
    }
}

/**
 * Claro u oscuro: sigue al sistema salvo que se haya elegido a mano (tocando el logo).
 * Al cambiar, un círculo del fondo nuevo crece desde el punto del logo, se cambia el tema abajo y el círculo se desvanece.
 */
@Composable
private fun Themed(window: android.view.Window, content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val view = LocalView.current
    val store = remember { Store(ctx) }
    var chosen by remember { mutableStateOf(store.darkMode) }
    val dark = chosen ?: isSystemInDarkTheme()
    // Íconos de las barras oscuros sobre claro y claros sobre oscuro.
    SideEffect {
        WindowCompat.getInsetsController(window, view).run {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }
    val scope = rememberCoroutineScope()
    val grow = remember { Animatable(0f) }
    val alpha = remember { Animatable(0f) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    var fill by remember { mutableStateOf(Color.Transparent) }
    var job by remember { mutableStateOf<Job?>(null) }
    val toggle: (Offset) -> Unit = toggle@{ at ->
        if (job?.isActive == true) return@toggle
        val next = !dark
        origin = at
        fill = (if (next) DarkPalette else LightPalette).bg
        job = scope.launch {
            grow.snapTo(0f)
            alpha.snapTo(1f)
            grow.animateTo(1f, tween(500, easing = EaseInOut))
            store.darkMode = next
            chosen = next
            Widget.update(ctx) // el widget sigue el modo de la app
            alpha.animateTo(0f, tween(300))
        }
    }
    CompositionLocalProvider(LocalPalette provides if (dark) DarkPalette else LightPalette, LocalToggleDark provides toggle) {
        Box(Modifier.fillMaxSize()) {
            content()
            // Se lee todo al dibujar: la animación no recompone la app.
            Canvas(Modifier.fillMaxSize()) {
                if (alpha.value == 0f) return@Canvas
                val far = Offset(maxOf(origin.x, size.width - origin.x), maxOf(origin.y, size.height - origin.y)).getDistance()
                drawCircle(fill, far * grow.value, origin, alpha = alpha.value)
            }
        }
    }
}

internal fun openUsageSettings(ctx: android.content.Context) =
    ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))

@Composable
internal fun App(resumes: Int, startTab: Int = 0) {
    val ctx = LocalContext.current
    val store = remember { Store(ctx) }
    var onboarded by remember { mutableStateOf(store.onboarded) }
    var access by remember { mutableStateOf(hasUsageAccess(ctx)) }
    var snap by remember { mutableStateOf<Snapshot?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var since by remember { mutableStateOf<Int?>(null) }
    var sinceFor by remember { mutableIntStateOf(-1) }
    // El onboarding puede terminar en IRL, con el grupo recién armado.
    var tab by remember { mutableIntStateOf(startTab) }

    LaunchedEffect(resumes) { access = hasUsageAccess(ctx) }
    LaunchedEffect(resumes, access, onboarded, reload) {
        // Solo mientras la app está abierta: refresco cada minuto.
        while (access && onboarded) {
            val s = withContext(Dispatchers.Default) { snapshot(ctx) }
            // Lo ganado desde la última visita se calcula una vez por apertura; después se sigue guardando.
            if (sinceFor != resumes) {
                since = store.seenTotal.takeIf { it >= 0 }?.let { s.totalPoints - it }
                sinceFor = resumes
            }
            store.seenTotal = s.totalPoints
            snap = s
            delay(60_000)
        }
    }

    val screen = when {
        !onboarded -> 0
        !access -> 1
        else -> 2
    }
    Box(Modifier.fillMaxSize().background(Bg)) {
        AnimatedContent(
            targetState = screen,
            transitionSpec = { (fadeIn(tween(300)) + scaleIn(tween(300), 0.96f)) togetherWith fadeOut(tween(300)) },
            label = "screen",
        ) { s ->
            when (s) {
                0 -> Onboarding(access) {
                    tab = it
                    store.onboarded = true
                    onboarded = true
                }
                1 -> Revoked { openUsageSettings(ctx) }
                else -> snap?.let { Home(it, since, tab) { reload++ } } ?: Box(Modifier.fillMaxSize())
            }
        }
    }
}


// ---------- Home ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun Home(s: Snapshot, since: Int?, startTab: Int, onChanged: () -> Unit) {
    var tab by remember { mutableIntStateOf(startTab) }
    var sheet by remember { mutableStateOf<Sheet?>(null) }
    val open: (Sheet?) -> Unit = { sheet = it }

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Crossfade(tab, Modifier.weight(1f), animationSpec = tween(200), label = "tab") { t ->
            when (t) {
                0 -> Today(s, since, open, onChanged)
                1 -> Ronda(s, open, onChanged)
                else -> History(s, open)
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
        Row(Modifier.fillMaxWidth().height(64.dp)) {
            listOf(LineIcons.TODAY to t("Hoy", "Today"), LineIcons.RONDA to "IRL", LineIcons.HISTORY to t("Historial", "History")).forEachIndexed { i, (icon, label) ->
                Box(
                    Modifier.weight(1f).fillMaxHeight().clickable(role = Role.Tab) { tab = i },
                    contentAlignment = Alignment.Center,
                ) { LineIcon(icon, if (tab == i) Sand else Faint, label) }
            }
        }
    }

    sheet?.let { current ->
        // Una hoja nueva reemplaza a la anterior (Ajustes → Meta): se reabre desde cero.
        key(current) {
            ModalBottomSheet(
                onDismissRequest = { sheet = null },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                containerColor = SheetBg,
            ) { SheetContent(current, s, open, onChanged) }
        }
    }
}
