package com.secrm.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.secrm.SecureRemoteApp
import com.secrm.core.ssh.SshState
import com.secrm.design.Motion
import com.secrm.design.SecrmIcons
import com.secrm.design.StatusDot
import com.secrm.design.ConnectionVisual
import com.secrm.ui.audit.AuditScreen
import com.secrm.ui.dashboard.DashboardScreen
import com.secrm.ui.keys.KeyManagementScreen
import com.secrm.ui.server.ServerControlScreen
import com.secrm.ui.sftp.SftpScreen
import com.secrm.ui.terminal.TerminalScreen

object Routes {
    const val Hosts = "hosts"
    const val Terminal = "terminal"
    const val Files = "files"
    const val Keys = "keys"
    const val Settings = "settings"
    const val Audit = "audit"
}

private data class Tab(val route: String, val label: String, val icon: ImageVector, val needsConnection: Boolean = false)

private val Tabs = listOf(
    Tab(Routes.Hosts, "Hosts", SecrmIcons.Dns),
    Tab(Routes.Terminal, "Terminal", SecrmIcons.Terminal, needsConnection = true),
    Tab(Routes.Files, "Files", SecrmIcons.Folder, needsConnection = true),
    Tab(Routes.Keys, "Keys", SecrmIcons.Key),
    Tab(Routes.Settings, "Settings", Icons.Rounded.Settings),
)

/** Switches tabs the Material way: one copy of each, with its state saved and restored. */
private fun NavHostController.goTab(route: String) = navigate(route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}

@Composable
fun SecrmNavHost(app: SecureRemoteApp) {
    val nav = rememberNavController()
    val connected = app.sessions.connected.collectAsState().value.isNotEmpty()
    val current = nav.currentBackStackEntryAsState().value?.destination?.route

    Scaffold(
        // Screens inside lay themselves out against the system bars; this scaffold only adds the bar.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            NavigationBar {
                Tabs.forEach { tab ->
                    NavigationBarItem(
                        selected = current == tab.route,
                        onClick = { if (current != tab.route) nav.goTab(tab.route) },
                        icon = {
                            // A dot marks the tabs that are working on the live connection.
                            if (tab.needsConnection && connected) BadgedBox(badge = { Badge(containerColor = com.secrm.design.LocalStatusColors.current.success) }) { Icon(tab.icon, null) }
                            else Icon(tab.icon, null)
                        },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { padding ->
        val bottom = PaddingValues(bottom = padding.calculateBottomPadding())
        NavHost(
            navController = nav,
            startDestination = Routes.Hosts,
            modifier = Modifier.padding(bottom).consumeWindowInsets(bottom),
            enterTransition = { fadeIn(tween(Motion.SweepMs, easing = Motion.EmphasizedDecel)) },
            exitTransition = { fadeOut(tween(Motion.SnapMs)) },
            popEnterTransition = { fadeIn(tween(Motion.SweepMs)) },
            popExitTransition = { fadeOut(tween(Motion.SnapMs)) },
        ) {
            composable(Routes.Hosts) {
                DashboardScreen(app = app, onOpenShell = { nav.goTab(Routes.Terminal) }, onOpenFiles = { nav.goTab(Routes.Files) })
            }
            composable(Routes.Terminal) {
                TerminalScreen(app, onOpenHosts = { nav.goTab(Routes.Hosts) }, onOpenFiles = { nav.goTab(Routes.Files) })
            }
            composable(Routes.Files) {
                SftpScreen(
                    app,
                    onOpenHosts = { nav.goTab(Routes.Hosts) },
                    onOpenTerminal = { dir ->
                        app.sessions.current.value?.terminal()?.send("cd '" + dir.replace("'", "'\\''") + "'\r")
                        nav.goTab(Routes.Terminal)
                    },
                )
            }
            composable(Routes.Keys) { KeyManagementScreen(app) }
            composable(Routes.Settings) { ServerControlScreen(app, onOpenAudit = { nav.navigate(Routes.Audit) }) }
            composable(Routes.Audit) { AuditScreen(app, onBack = { nav.popBackStack() }) }
        }
    }
}
