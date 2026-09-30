package com.stickerforge.app.ui

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.stickerforge.app.core.Frames
import com.stickerforge.app.ui.screens.EditorScreen
import com.stickerforge.app.ui.screens.PacksScreen
import com.stickerforge.app.ui.screens.SearchScreen
import com.stickerforge.app.ui.screens.SettingsScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val ROUTE_SEARCH = "search"
private const val ROUTE_PACKS = "packs"
private const val ROUTE_SETTINGS = "settings"
private const val ROUTE_EDITOR = "editor"

@Composable
fun AppNav(main: MainViewModel, sharedUri: Uri?, onSharedConsumed: () -> Unit) {
    val navController = rememberNavController()
    val context = LocalContext.current

    LaunchedEffect(sharedUri) {
        val uri = sharedUri ?: return@LaunchedEffect
        onSharedConsumed()
        try {
            val source = withContext(Dispatchers.IO) { Frames.fromUri(context, uri) }
            main.pendingSource = source
            main.preferAnimated = source.animated
            navController.navigate(ROUTE_EDITOR)
        } catch (_: Exception) {
            // unreadable share: stay put
        }
    }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = currentRoute != ROUTE_EDITOR

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    NavigationBarItem(
                        selected = currentRoute == ROUTE_SEARCH,
                        onClick = { navController.navigate(ROUTE_SEARCH) { launchSingleTop = true } },
                        icon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        label = { Text("Search") },
                    )
                    NavigationBarItem(
                        selected = currentRoute == ROUTE_PACKS,
                        onClick = { navController.navigate(ROUTE_PACKS) { launchSingleTop = true } },
                        icon = { Icon(Icons.Filled.Collections, contentDescription = null) },
                        label = { Text("Packs") },
                    )
                    NavigationBarItem(
                        selected = currentRoute == ROUTE_SETTINGS,
                        onClick = { navController.navigate(ROUTE_SETTINGS) { launchSingleTop = true } },
                        icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                        label = { Text("Settings") },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(bottom = padding.calculateBottomPadding())) {
            NavHost(
                navController = navController,
                startDestination = ROUTE_SEARCH,
            ) {
                composable(ROUTE_SEARCH) {
                    SearchScreen(
                        main = main,
                        onOpenSettings = { navController.navigate(ROUTE_SETTINGS) { launchSingleTop = true } },
                        onEdit = { navController.navigate(ROUTE_EDITOR) },
                    )
                }
                composable(ROUTE_PACKS) {
                    PacksScreen(
                        main = main,
                        onOpenEditor = { navController.navigate(ROUTE_EDITOR) },
                        onGoSearch = { navController.navigate(ROUTE_SEARCH) { launchSingleTop = true } },
                    )
                }
                composable(ROUTE_SETTINGS) {
                    SettingsScreen(main)
                }
                composable(ROUTE_EDITOR) {
                    EditorScreen(main) {
                        main.pendingSource = null
                        navController.popBackStack(ROUTE_PACKS, inclusive = false)
                    }
                }
            }
        }
    }
}
