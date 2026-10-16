package com.secrm.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.secrm.HostSession
import com.secrm.SecureRemoteApp

/**
 * Swipe between the connected hosts, one [content] page each. Swiping makes that host the current one,
 * so the terminal and files tabs follow each other.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HostPager(app: SecureRemoteApp, hosts: List<HostSession>, content: @Composable (HostSession) -> Unit) {
    val current by app.sessions.current.collectAsState()
    val index = hosts.indexOfFirst { it.name == current?.name }.coerceAtLeast(0)
    val pager = rememberPagerState(initialPage = index) { hosts.size }
    LaunchedEffect(index) { if (pager.currentPage != index) pager.animateScrollToPage(index) }
    LaunchedEffect(pager.settledPage) { hosts.getOrNull(pager.settledPage)?.let { app.sessions.select(it.name) } }

    Column(Modifier.fillMaxSize()) {
        HorizontalPager(
            state = pager,
            key = { hosts.getOrNull(it)?.name ?: it },
            modifier = Modifier.weight(1f),
        ) { page -> hosts.getOrNull(page)?.let { content(it) } }
        if (hosts.size > 1) Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.Center) {
            hosts.indices.forEach { i ->
                Box(
                    Modifier.padding(horizontal = 3.dp).size(6.dp).background(
                        if (i == pager.currentPage) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                        CircleShape,
                    )
                )
            }
        }
    }
}
