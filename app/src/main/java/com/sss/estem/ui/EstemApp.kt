package com.sss.estem.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sss.estem.AppContainer
import com.sss.estem.design.EstemStageTheme
import com.sss.estem.ui.pages.BackPage
import com.sss.estem.ui.pages.FrontPage
import com.sss.estem.ui.pages.QueuePage
import kotlinx.coroutines.launch

private const val PAGE_FRONT = 0
private const val PAGE_BACK = 1
private const val PAGE_QUEUE = 2
private const val PAGE_COUNT = 3

/**
 * The whole app is one device seen from three sides: its front face, its back, and everything
 * that is not the object itself. Swiping between them never interrupts playback — the player
 * lives in [com.sss.estem.player.PlayerController], not in any of these pages.
 */
@Composable
fun EstemApp(container: AppContainer) {
    val pagerState = rememberPagerState(initialPage = PAGE_FRONT) { PAGE_COUNT }
    val scope = rememberCoroutineScope()
    val state by container.player.state.collectAsStateWithLifecycle()
    val snackbarHost = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHost.showSnackbar(it)
            container.player.consumeMessage()
        }
    }

    EstemStageTheme {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = { SnackbarHost(snackbarHost) },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                // No `key` here on purpose. The page index is already the default key, and
                // supplying one collides with itself when the pager pre-composes the adjacent
                // page — which crashed the app on every swipe toward the queue.
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                ) { page ->
                    when (page) {
                        PAGE_FRONT -> FrontPage(container.player)
                        PAGE_BACK -> BackPage(container.player, container)
                        else -> QueuePage(
                            container = container,
                            onPlay = {
                                container.player.load(it)
                                scope.launch { pagerState.animateScrollToPage(PAGE_FRONT) }
                            },
                        )
                    }
                }

                PageIndicator(
                    current = pagerState.currentPage,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .systemBarsPadding()
                        .padding(bottom = 10.dp),
                )
            }
        }
    }
}

@Composable
private fun PageIndicator(current: Int, modifier: Modifier = Modifier) {
    val labels = listOf("Front", "Back", "Queue")
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = labels[current.coerceIn(labels.indices)],
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            Modifier.padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            repeat(PAGE_COUNT) { index ->
                val active = index == current
                Box(
                    Modifier
                        .width(if (active) 18.dp else 6.dp)
                        .height(6.dp)
                        .clip(CircleShape)
                        .background(
                            if (active) MaterialTheme.colorScheme.primary
                            else Color.White.copy(alpha = 0.22f),
                        ),
                )
            }
        }
    }
}
