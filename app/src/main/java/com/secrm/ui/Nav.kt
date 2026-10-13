package com.secrm.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.secrm.SecureRemoteApp
import com.secrm.design.Motion
import com.secrm.ui.dashboard.DashboardScreen
import com.secrm.ui.server.ServerControlScreen
import com.secrm.ui.terminal.TerminalScreen

object Routes {
    const val Dashboard = "dashboard"
    const val Settings = "settings"
    const val Shell = "shell"
}

@Composable
fun SecrmNavHost(app: SecureRemoteApp) {
    val nav = rememberNavController()
    NavHost(
        navController = nav,
        startDestination = Routes.Dashboard,
        enterTransition = {
            fadeIn(tween(Motion.SweepMs, easing = Motion.EmphasizedDecel)) +
                slideInVertically(
                    initialOffsetY = { it / 12 },
                    animationSpec = tween(Motion.SweepMs, easing = Motion.EmphasizedDecel)
                )
        },
        exitTransition = { fadeOut(tween(Motion.SnapMs)) },
        popEnterTransition = { fadeIn(tween(Motion.SweepMs)) },
        popExitTransition = { fadeOut(tween(Motion.SnapMs)) }
    ) {
        composable(Routes.Dashboard) {
            DashboardScreen(
                app = app,
                onOpenSettings = { nav.navigate(Routes.Settings) },
                onOpenShell = { nav.navigate(Routes.Shell) }
            )
        }
        composable(Routes.Settings) {
            ServerControlScreen(app, onBack = { nav.popBackStack() })
        }
        composable(Routes.Shell) {
            TerminalScreen(app, onBack = { nav.popBackStack() })
        }
    }
}
