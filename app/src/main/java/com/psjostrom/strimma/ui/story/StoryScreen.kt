package com.psjostrom.strimma.ui.story

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.psjostrom.strimma.data.GlucoseUnit

private const val PAGE_OVERVIEW = 0
private const val PAGE_STABILITY = 1
private const val PAGE_EVENTS = 2
private const val PAGE_PATTERNS = 3
private const val PAGE_MEALS = 4
private const val PAGE_SUMMARY = 5

private const val PAGES_WITH_MEALS = 6
private const val PAGES_WITHOUT_MEALS = 5

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StoryScreen(
    glucoseUnit: GlucoseUnit,
    onBack: () -> Unit,
    viewModel: StoryViewModel = hiltViewModel()
) {
    val story by viewModel.story.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val bg = MaterialTheme.colorScheme.background

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bg)
    ) {
        when {
            loading -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
            story == null -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.statusBars),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "Not enough data",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "At least 7 days of readings are needed to generate your story.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 48.dp)
                        )
                    }
                }
            }
            else -> {
                val data = story!!
                val hasMeals = data.meals != null
                val pageCount = if (hasMeals) PAGES_WITH_MEALS else PAGES_WITHOUT_MEALS
                val pagerState = rememberPagerState(pageCount = { pageCount })

                Column(modifier = Modifier.fillMaxSize()) {
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.weight(1f)
                    ) { page ->
                        val effectivePage = if (!hasMeals && page >= PAGE_MEALS) page + 1 else page
                        when (effectivePage) {
                            PAGE_OVERVIEW -> OverviewPage(data, glucoseUnit)
                            PAGE_STABILITY -> StabilityPage(data)
                            PAGE_EVENTS -> EventsPage(data)
                            PAGE_PATTERNS -> PatternsPage(data)
                            PAGE_MEALS -> data.meals?.let { MealsPage(it, glucoseUnit) }
                            PAGE_SUMMARY -> SummaryPage(data)
                        }
                    }

                    // Page indicator dots
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .windowInsetsPadding(WindowInsets.navigationBars)
                            .padding(bottom = 16.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        repeat(pageCount) { index ->
                            val isSelected = pagerState.currentPage == index
                            Box(
                                modifier = Modifier
                                    .padding(horizontal = 4.dp)
                                    .size(if (isSelected) 8.dp else 6.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (isSelected) MaterialTheme.colorScheme.onBackground
                                        else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f)
                                    )
                            )
                        }
                    }
                }
            }
        }

        // Back button — always visible
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(8.dp)
                .align(Alignment.TopStart)
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = MaterialTheme.colorScheme.onBackground
            )
        }
    }
}

@Composable
fun StoryPageScaffold(
    tintColor: Color,
    content: @Composable () -> Unit
) {
    val bg = MaterialTheme.colorScheme.background
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0f to tintColor,
                    0.3f to bg,
                    1f to bg
                )
            )
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 24.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Top
    ) {
        // Top spacing for back button
        Spacer(Modifier.height(56.dp))
        content()
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
fun StoryCard(
    label: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                label.uppercase(),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.5.sp
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}
