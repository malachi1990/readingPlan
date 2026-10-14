package org.bible.reading

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.bible.reading.dto.Bible
import org.bible.reading.dto.BookCollection
import org.bible.reading.dto.ExportFormat
import org.bible.reading.strategy.ContinuationStrategyType
import org.bible.reading.strategy.StrategyRegistry
import org.bible.reading.ui.PlanState
import org.bible.reading.ui.PlanStrategyChoice
import java.awt.Desktop
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun App(state: PlanState = remember { PlanState() }) {
    MaterialTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header Bar
                Surface(
                    tonalElevation = 2.dp,
                    shadowElevation = 2.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                text = "Bible Reading Plan Generator",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Customize book schedules, daily chapter goals, and completion strategies",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { state.generatePlan() },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                            ) {
                                Text("Generate Reading Plan", fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }

                // Main Content: Split View (Settings on Left, Generated Plan on Right)
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Left Column: Configuration Settings
                    Card(
                        modifier = Modifier
                            .weight(1.05f)
                            .fillMaxHeight(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            // Section 1: Strategy Selection
                            StrategySelectionSection(state)

                            HorizontalDivider()

                            // Section 2: Chapters Per Day Configuration
                            ChaptersPerDaySection(state)

                            // Section 3: Day-of-Week Specific Book & Continuation Settings
                            if (state.selectedStrategyChoice == PlanStrategyChoice.DAY_OF_WEEK ||
                                state.selectedStrategyChoice == PlanStrategyChoice.CUSTOM_STRATEGY
                            ) {
                                HorizontalDivider()
                                ContinuationStrategySection(state)

                                HorizontalDivider()
                                BooksByDaySection(state)
                            }

                            HorizontalDivider()

                            // Section 4: Start Date
                            StartDateSection(state)
                        }
                    }

                    // Right Column: Generated Plan View & Export
                    Card(
                        modifier = Modifier
                            .weight(1.15f)
                            .fillMaxHeight(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                    ) {
                        PlanResultsSection(state)
                    }
                }
            }
        }
    }
}

@Composable
private fun StrategySelectionSection(state: PlanState) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "1. Reading Strategy",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )

        val customStrategies = StrategyRegistry.getAllCustomStrategies()

        PlanStrategyChoice.entries.forEach { choice ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable {
                        state.selectedStrategyChoice = choice
                        if (choice == PlanStrategyChoice.CUSTOM_STRATEGY && customStrategies.isNotEmpty() && state.selectedCustomStrategyName == null) {
                            state.selectedCustomStrategyName = customStrategies.keys.first()
                        }
                    }
                    .padding(vertical = 4.dp, horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = state.selectedStrategyChoice == choice,
                    onClick = {
                        state.selectedStrategyChoice = choice
                        if (choice == PlanStrategyChoice.CUSTOM_STRATEGY && customStrategies.isNotEmpty() && state.selectedCustomStrategyName == null) {
                            state.selectedCustomStrategyName = customStrategies.keys.first()
                        }
                    }
                )
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(
                        text = choice.displayName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = choice.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // If custom strategy is selected, let user pick from registered custom strategies
        if (state.selectedStrategyChoice == PlanStrategyChoice.CUSTOM_STRATEGY) {
            if (customStrategies.isNotEmpty()) {
                var menuExpanded by remember { mutableStateOf(false) }
                OutlinedCard(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { menuExpanded = true }
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Selected Custom Strategy: ${state.selectedCustomStrategyName ?: "None"}",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text("Change", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)

                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false }
                        ) {
                            customStrategies.keys.forEach { name ->
                                DropdownMenuItem(
                                    text = { Text(name) },
                                    onClick = {
                                        state.selectedCustomStrategyName = name
                                        menuExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }
            } else {
                Text(
                    text = "No additional custom strategies registered in StrategyRegistry. Using configured Day-of-Week settings.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 12.dp)
                )
            }
        }
    }
}

@Composable
private fun ChaptersPerDaySection(state: PlanState) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = "2. Chapters Per Day",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            NumberStepper(
                label = "Chapters / Day",
                value = state.chaptersPerDay,
                onValueChange = { state.chaptersPerDay = it.coerceAtLeast(1) },
                modifier = Modifier.weight(1f)
            )

            if (state.selectedStrategyChoice != PlanStrategyChoice.WEEKDAY_ONLY) {
                NumberStepper(
                    label = "Sunday Chapters",
                    value = state.chaptersOnSunday,
                    onValueChange = { state.chaptersOnSunday = it.coerceAtLeast(1) },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // Toggle per-day-of-week custom chapters
        if (state.selectedStrategyChoice == PlanStrategyChoice.DAY_OF_WEEK ||
            state.selectedStrategyChoice == PlanStrategyChoice.SIMPLE_SEQUENTIAL
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { state.useCustomPerDayChapters = !state.useCustomPerDayChapters }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = state.useCustomPerDayChapters,
                    onCheckedChange = { state.useCustomPerDayChapters = it }
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Configure custom chapters for each individual day of the week",
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            if (state.useCustomPerDayChapters) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    DayOfWeek.entries.forEach { dow ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = dow.getDisplayName(TextStyle.FULL, Locale.getDefault()),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.width(120.dp)
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedButton(
                                    onClick = {
                                        val current = state.perDayChapters[dow] ?: 3
                                        state.setPerDayChapter(dow, current - 1)
                                    },
                                    contentPadding = PaddingValues(0.dp),
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Text("-")
                                }
                                Text(
                                    text = "${state.perDayChapters[dow] ?: 3}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 12.dp)
                                )
                                OutlinedButton(
                                    onClick = {
                                        val current = state.perDayChapters[dow] ?: 3
                                        state.setPerDayChapter(dow, current + 1)
                                    },
                                    contentPadding = PaddingValues(0.dp),
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Text("+")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ContinuationStrategySection(state: PlanState) {
    var menuExpanded by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "3. Day Completion Strategy",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "When a day's assigned reading is finished, how should readings continue?",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        OutlinedCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { menuExpanded = true }
                    .padding(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = state.continuationStrategyType.displayName,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text("Select ▼", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = state.continuationStrategyType.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    ContinuationStrategyType.entries.forEach { type ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(type.displayName, fontWeight = FontWeight.Medium)
                                    Text(
                                        type.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            },
                            onClick = {
                                state.continuationStrategyType = type
                                menuExpanded = false
                            }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BooksByDaySection(state: PlanState) {
    var showBookPicker by remember { mutableStateOf(false) }
    var showCollectionPicker by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "4. Books Assigned on Specific Days",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            TextButton(onClick = { state.resetToCanonicalSchedule() }) {
                Text("Reset Canonical", style = MaterialTheme.typography.bodySmall)
            }
        }

        // Day of Week Tabs
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            DayOfWeek.entries.forEach { dow ->
                val isSelected = state.activeDayOfWeek == dow
                val booksCount = state.booksByDay[dow]?.size ?: 0

                FilterChip(
                    selected = isSelected,
                    onClick = { state.activeDayOfWeek = dow },
                    label = {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(vertical = 2.dp)
                        ) {
                            Text(
                                text = dow.getDisplayName(TextStyle.SHORT, Locale.getDefault()),
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                            Text(
                                text = "$booksCount bks",
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 10.sp,
                                color = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // Active Day Info & Actions
        val activeDow = state.activeDayOfWeek
        val assignedBooks = state.booksByDay[activeDow] ?: emptyList()
        val totalChapters = state.getTotalChaptersForDay(activeDow)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${activeDow.getDisplayName(TextStyle.FULL, Locale.getDefault())}: ${assignedBooks.size} books, $totalChapters chapters",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                // Add Collection Button
                Box {
                    OutlinedButton(
                        onClick = { showCollectionPicker = true },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text("+ Collection", style = MaterialTheme.typography.bodySmall)
                    }

                    DropdownMenu(
                        expanded = showCollectionPicker,
                        onDismissRequest = { showCollectionPicker = false }
                    ) {
                        BookCollection.entries.forEach { collection ->
                            DropdownMenuItem(
                                text = { Text(collection.name.replace("_", " ")) },
                                onClick = {
                                    state.addCollectionToDay(activeDow, collection)
                                    showCollectionPicker = false
                                }
                            )
                        }
                    }
                }

                // Add Book Button
                Box {
                    Button(
                        onClick = { showBookPicker = true },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text("+ Book", style = MaterialTheme.typography.bodySmall)
                    }

                    DropdownMenu(
                        expanded = showBookPicker,
                        onDismissRequest = { showBookPicker = false }
                    ) {
                        Bible.getBooksInOrder().forEach { book ->
                            val alreadyAssigned = assignedBooks.contains(book)
                            DropdownMenuItem(
                                text = {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(book.bookName)
                                        Spacer(Modifier.width(12.dp))
                                        Text(
                                            if (alreadyAssigned) "✓ Added" else "${book.chapters} ch",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = if (alreadyAssigned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                },
                                onClick = {
                                    if (alreadyAssigned) {
                                        state.removeBookFromDay(activeDow, book)
                                    } else {
                                        state.addBookToDay(activeDow, book)
                                    }
                                }
                            )
                        }
                    }
                }

                // Clear Day
                OutlinedButton(
                    onClick = { state.clearDay(activeDow) },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Text("Clear", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        }

        // Assigned Books Display (Flow of Chips)
        if (assignedBooks.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                    .padding(20.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No books assigned to ${activeDow.getDisplayName(TextStyle.FULL, Locale.getDefault())}. Use '+ Collection' or '+ Book' to assign books.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                    .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                assignedBooks.forEach { book ->
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier.padding(2.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(start = 10.dp, end = 6.dp, top = 4.dp, bottom = 4.dp)
                        ) {
                            Text(
                                text = "${book.bookName} (${book.chapters})",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            Spacer(Modifier.width(6.dp))
                            Box(
                                modifier = Modifier
                                    .size(16.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.15f))
                                    .clickable { state.removeBookFromDay(activeDow, book) },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "×",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StartDateSection(state: PlanState) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "5. Plan Start Date",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            NumberStepper(
                label = "Year",
                value = state.startDate.year,
                onValueChange = { newYear ->
                    state.startDate = LocalDate.of(newYear, state.startDate.monthValue, state.startDate.dayOfMonth)
                },
                modifier = Modifier.weight(1f)
            )

            NumberStepper(
                label = "Month",
                value = state.startDate.monthValue,
                onValueChange = { newMonth ->
                    val clampedMonth = newMonth.coerceIn(1, 12)
                    val maxDay = LocalDate.of(state.startDate.year, clampedMonth, 1).lengthOfMonth()
                    val clampedDay = state.startDate.dayOfMonth.coerceAtMost(maxDay)
                    state.startDate = LocalDate.of(state.startDate.year, clampedMonth, clampedDay)
                },
                modifier = Modifier.weight(1f)
            )

            NumberStepper(
                label = "Day",
                value = state.startDate.dayOfMonth,
                onValueChange = { newDay ->
                    val maxDay = state.startDate.lengthOfMonth()
                    state.startDate = LocalDate.of(state.startDate.year, state.startDate.monthValue, newDay.coerceIn(1, maxDay))
                },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun PlanResultsSection(state: PlanState) {
    val plan = state.readingPlan
    val dateFormatter = DateTimeFormatter.ofPattern("EEE, MMM d, yyyy")

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Generated Reading Plan",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            if (plan != null) {
                Text(
                    text = "${plan.readings.size} Total Days",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        if (plan == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "No Reading Plan Generated",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Configure your parameters on the left and click 'Generate Reading Plan' above to view your personalized daily schedule.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 24.dp)
                    )
                }
            }
        } else {
            val totalChapters = plan.readings.sumOf { it.reading.size }
            val startDateStr = plan.readings.firstOrNull()?.date?.format(dateFormatter) ?: "N/A"
            val endDateStr = plan.readings.lastOrNull()?.date?.format(dateFormatter) ?: "N/A"

            // Summary Stats Cards
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatCard(
                    title = "Total Days",
                    value = "${plan.readings.size}",
                    modifier = Modifier.weight(1f)
                )
                StatCard(
                    title = "Total Chapters",
                    value = "$totalChapters",
                    modifier = Modifier.weight(1f)
                )
                StatCard(
                    title = "End Date",
                    value = plan.readings.lastOrNull()?.date?.toString() ?: "",
                    modifier = Modifier.weight(1.3f)
                )
            }

            // Save to File Section
            SavePlanToFileSection(state)

            // Search Filter Bar
            OutlinedTextField(
                value = state.planSearchQuery,
                onValueChange = { state.planSearchQuery = it },
                label = { Text("Filter readings by book or date (e.g. Genesis or 2026-01)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            // Filtered daily readings list
            val filteredReadings = remember(plan, state.planSearchQuery) {
                if (state.planSearchQuery.isBlank()) {
                    plan.readings
                } else {
                    val query = state.planSearchQuery.trim().lowercase()
                    plan.readings.filter { daily ->
                        daily.date.toString().lowercase().contains(query) ||
                                daily.reading.any { it.book.lowercase().contains(query) }
                    }
                }
            }

            Text(
                text = "Showing ${filteredReadings.size} of ${plan.readings.size} days (Start: $startDateStr • End: $endDateStr)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                itemsIndexed(filteredReadings) { _, daily ->
                    ElevatedCard(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = daily.date.format(dateFormatter),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = "${daily.reading.size} chapters",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            val groupedText = daily.reading.groupBy { it.book }
                                .entries.joinToString(" • ") { entry ->
                                    val chapters = entry.value.map { it.chapterNumber }
                                    "${entry.key} ${formatChapterRanges(chapters)}"
                                }
                            Text(
                                text = groupedText,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SavePlanToFileSection(state: PlanState) {

    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Save Reading Plan to File",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "User-specified directory (default: ~/downloads)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Button(
                    onClick = { state.savePlanToFile() },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                ) {
                    Text("Save to File", fontWeight = FontWeight.SemiBold)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = state.saveDirectory,
                    onValueChange = { state.saveDirectory = it },
                    label = { Text("Directory (default: ~/downloads)") },
                    singleLine = true,
                    modifier = Modifier.weight(1.5f)
                )

                OutlinedTextField(
                    value = state.saveFileName,
                    onValueChange = { state.saveFileName = it },
                    label = { Text("File Name") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }

            // Export format chips
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Format:",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium
                )
                ExportFormat.entries.forEach { format ->
                    FilterChip(
                        selected = state.saveFormat == format,
                        onClick = { state.saveFormat = format },
                        label = { Text(format.displayName, fontSize = 12.sp) }
                    )
                }
            }

            // Feedback / Status Message
            state.saveStatusMessage?.let { msg ->
                val bgColor = if (state.isSaveSuccess) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                } else {
                    MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                }
                val textColor = if (state.isSaveSuccess) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onErrorContainer
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(bgColor, RoundedCornerShape(6.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = msg,
                        style = MaterialTheme.typography.bodySmall,
                        color = textColor,
                        modifier = Modifier.weight(1f)
                    )

                    if (state.isSaveSuccess && state.lastSavedFile != null) {
                        TextButton(
                            onClick = {
                                try {
                                    if (Desktop.isDesktopSupported()) {
                                        Desktop.getDesktop().open(state.lastSavedFile)
                                    }
                                } catch (_: Exception) {}
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text("Open", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}

private fun formatChapterRanges(chapters: List<Int>): String {
    if (chapters.isEmpty()) return ""
    val sorted = chapters.sorted()
    val ranges = mutableListOf<String>()
    var rangeStart = sorted[0]
    var prev = sorted[0]

    for (i in 1 until sorted.size) {
        val curr = sorted[i]
        if (curr == prev + 1) {
            prev = curr
        } else {
            if (rangeStart == prev) ranges.add("$rangeStart") else ranges.add("$rangeStart-$prev")
            rangeStart = curr
            prev = curr
        }
    }
    if (rangeStart == prev) ranges.add("$rangeStart") else ranges.add("$rangeStart-$prev")
    return ranges.joinToString(", ")
}

@Composable
private fun StatCard(title: String, value: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun NumberStepper(
    label: String,
    value: Int,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedCard(modifier = modifier) {
        Column(
            modifier = Modifier.padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedButton(
                    onClick = { onValueChange(value - 1) },
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.size(32.dp)
                ) {
                    Text("-", fontWeight = FontWeight.Bold)
                }
                Text(
                    text = "$value",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold
                )
                OutlinedButton(
                    onClick = { onValueChange(value + 1) },
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.size(32.dp)
                ) {
                    Text("+", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
