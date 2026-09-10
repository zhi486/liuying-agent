package com.healthyagent.running.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.healthyagent.running.ui.history.HistoryScreen
import com.healthyagent.running.ui.history.HistoryViewModel
import com.healthyagent.running.ui.running.RunningScreen
import com.healthyagent.running.ui.running.RunningViewModel
import com.healthyagent.running.ui.settings.SettingsScreen
import com.healthyagent.running.ui.settings.SettingsViewModel
import kotlinx.serialization.Serializable
import org.koin.androidx.compose.koinViewModel

@Serializable
private data object RunningRoute

@Serializable
private data object HistoryRoute

@Serializable
private data object SettingsRoute

/**
 * 应用唯一导航入口。
 *
 * 路由负责获取各自的 ViewModel，页面本身只接收状态持有者和导航回调。
 */
@Composable
fun AppNavHost() {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = RunningRoute,
    ) {
        composable<RunningRoute> {
            RunningScreen(
                viewModel = koinViewModel<RunningViewModel>(),
                onOpenHistory = { navController.navigate(HistoryRoute) },
                onOpenSettings = { navController.navigate(SettingsRoute) },
            )
        }
        composable<HistoryRoute> {
            HistoryScreen(
                viewModel = koinViewModel<HistoryViewModel>(),
                onBack = { navController.popBackStack() },
            )
        }
        composable<SettingsRoute> {
            SettingsScreen(
                viewModel = koinViewModel<SettingsViewModel>(),
                onBack = { navController.popBackStack() },
            )
        }
    }
}
