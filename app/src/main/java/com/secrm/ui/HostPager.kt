package com.secrm.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.dp
import com.secrm.HostSession
import com.secrm.SecureRemoteApp
import kotlinx.coroutines.launch
import kotlin.math.abs

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
    val scope = rememberCoroutineScope()
    LaunchedEffect(index) { if (pager.currentPage != index) pager.animateScrollToPage(index) }
    LaunchedEffect(pager.settledPage) { hosts.getOrNull(pager.settledPage)?.let { app.sessions.select(it.name) } }

    Column(Modifier.fillMaxSize()) {
        HorizontalPager(
            state = pager,
            key = { hosts.getOrNull(it)?.name ?: it },
            modifier = Modifier.weight(1f).horizontalSwipe(pager) { scope.launch { pager.animateScrollToPage(it) } },
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

/**
 * The terminal consumes every drag it sees, so the pager never gets a horizontal swipe. This reads the
 * gesture in the initial pass (parent first): a mostly-horizontal drag is taken here and turned into a page
 * change; anything vertical is left to the child, so scrolling still works.
 */
@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.horizontalSwipe(pager: PagerState, go: (Int) -> Unit): Modifier = pointerInput(pager) {
    val slop = viewConfiguration.touchSlop
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val id = down.id
        var dx = 0f
        var dy = 0f
        var swiping = false
        while (true) {
            val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == id } ?: break
            if (!change.pressed) break
            val d = change.positionChange()
            dx += d.x
            dy += d.y
            if (!swiping) {
                if (abs(dx) < slop && abs(dy) < slop) continue
                if (abs(dx) <= abs(dy) * 2) return@awaitEachGesture // vertical: the child keeps it
                swiping = true
            }
            change.consume()
        }
        if (swiping) {
            val step = if (dx < 0) 1 else -1
            val target = (pager.currentPage + step).coerceIn(0, pager.pageCount - 1)
            if (abs(dx) > size.width / 4f && target != pager.currentPage) go(target)
        }
    }
}
