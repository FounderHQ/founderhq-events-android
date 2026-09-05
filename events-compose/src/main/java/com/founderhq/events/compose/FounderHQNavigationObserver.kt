package com.founderhq.events.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.founderhq.events.FounderHQEvents

@Composable
fun FounderHQNavigationObserver(
    navController: NavHostController,
    events: FounderHQEvents,
) {
    val entry = navController.currentBackStackEntryAsState().value
    val route = entry?.destination?.route
    LaunchedEffect(route) {
        if (!route.isNullOrBlank()) events.screen(route)
    }
}
