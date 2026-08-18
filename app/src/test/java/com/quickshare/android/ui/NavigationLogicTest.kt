package com.quickshare.android.ui

import com.quickshare.android.ui.navigation.NavDestination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationLogicTest {

    @Test
    fun testNavDestinationRoutes() {
        val routes = NavDestination.entries.map { it.route }
        assertEquals(4, routes.size)
        assertTrue(routes.contains("connection"))
        assertTrue(routes.contains("server_mode"))
        assertTrue(routes.contains("file_browser"))
        assertTrue(routes.contains("dashboard"))
    }

    @Test
    fun testNavDestinationFromRoute() {
        assertEquals(NavDestination.CONNECTION, NavDestination.fromRoute("connection"))
        assertEquals(NavDestination.SERVER, NavDestination.fromRoute("server_mode"))
        assertEquals(NavDestination.FILE_BROWSER, NavDestination.fromRoute("file_browser"))
        assertEquals(NavDestination.DASHBOARD, NavDestination.fromRoute("dashboard"))
        assertEquals(NavDestination.CONNECTION, NavDestination.fromRoute("unknown_route"))
    }
}
