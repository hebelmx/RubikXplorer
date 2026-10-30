package dev.rubikxplorer.app.ui.shell

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import dev.rubikxplorer.app.R
import dev.rubikxplorer.app.content.CatalogState
import dev.rubikxplorer.app.content.LessonCatalog
import dev.rubikxplorer.app.session.SessionHost
import dev.rubikxplorer.app.ui.freecube.FreeCubeScreen
import dev.rubikxplorer.app.ui.freecube.FreeCubeViewModel
import dev.rubikxplorer.app.ui.lesson.LessonScreen
import dev.rubikxplorer.app.ui.theme.RubikTheme
import dev.rubikxplorer.domain.content.LessonId

/** The top-level destinations of the navigation shell. */
enum class Tab(
    @param:DrawableRes val icon: Int,
    @param:StringRes val label: Int,
) {
    LEARN(R.drawable.ic_learn, R.string.nav_learn),
    SOLVE(R.drawable.ic_solve, R.string.nav_solve),
}

/**
 * The navigation shell (UX-DR7): Learn and Solve (the free cube) behind a `NavigationBar` when
 * compact and a `NavigationRail` from medium width. An open Lesson replaces the shell as a
 * full-screen task flow, with no bar. The tab and the open Lesson survive configuration changes; the
 * Lesson's progress lives in [host]'s session.
 */
@Composable
fun AppShell(
    host: SessionHost,
    catalog: LessonCatalog,
    freeCube: FreeCubeViewModel,
    showDebugToggles: Boolean,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(catalog) { catalog.load() }
    val content by catalog.state.collectAsState()
    var tab by rememberSaveable { mutableStateOf(Tab.LEARN) }
    var openLesson by rememberSaveable { mutableStateOf<String?>(null) }

    val ready = content as? CatalogState.Ready
    val entry = openLesson?.let { ready?.find(LessonId(it)) }
    if (ready != null && entry != null) {
        LessonScreen(host = host, entry = entry, strings = ready.strings, onClose = { openLesson = null }, modifier = modifier)
        return
    }

    val destination: @Composable (Modifier) -> Unit = { destinationModifier ->
        when (tab) {
            Tab.LEARN -> {
                LearnScreen(
                    catalog = content,
                    onOpen = { openLesson = it.value },
                    // The inset area takes surfaceBase too, not the window background.
                    modifier = destinationModifier.background(RubikTheme.tokens.surfaceBase).safeDrawingPadding(),
                )
            }

            Tab.SOLVE -> {
                FreeCubeScreen(viewModel = freeCube, showDebugToggles = showDebugToggles, modifier = destinationModifier)
            }
        }
    }
    BoxWithConstraints(modifier.fillMaxSize().testTag("shell")) {
        if (WindowWidthClass.of(maxWidth.value) == WindowWidthClass.COMPACT) {
            Scaffold(
                containerColor = RubikTheme.tokens.surfaceBase,
                bottomBar = {
                    NavigationBar(containerColor = RubikTheme.tokens.surfaceRaised, modifier = Modifier.testTag("nav:bar")) {
                        Tab.entries.forEach { item ->
                            NavigationBarItem(
                                selected = tab == item,
                                onClick = { tab = item },
                                icon = { Icon(painterResource(item.icon), contentDescription = null) },
                                label = { Text(stringResource(item.label)) },
                                modifier = Modifier.testTag("nav:${item.name.lowercase()}"),
                            )
                        }
                    }
                },
            ) { padding ->
                Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) { destination(Modifier.fillMaxSize()) }
            }
        } else {
            Row(Modifier.fillMaxSize()) {
                NavigationRail(containerColor = RubikTheme.tokens.surfaceRaised, modifier = Modifier.fillMaxHeight().testTag("nav:rail")) {
                    Tab.entries.forEach { item ->
                        NavigationRailItem(
                            selected = tab == item,
                            onClick = { tab = item },
                            icon = { Icon(painterResource(item.icon), contentDescription = null) },
                            label = { Text(stringResource(item.label)) },
                            modifier = Modifier.testTag("nav:${item.name.lowercase()}"),
                        )
                    }
                }
                Box(Modifier.weight(1f).fillMaxHeight()) { destination(Modifier.fillMaxSize()) }
            }
        }
    }
}
