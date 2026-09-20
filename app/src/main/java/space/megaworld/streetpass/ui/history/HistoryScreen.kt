package space.megaworld.streetpass.ui.history

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import java.time.LocalDate
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import space.megaworld.streetpass.AppContainer
import space.megaworld.streetpass.core.TimeRanges
import space.megaworld.streetpass.data.db.EncounterRow
import space.megaworld.streetpass.ui.AppViewModelProvider
import space.megaworld.streetpass.ui.Format
import space.megaworld.streetpass.ui.components.EncounterItem

data class HistorySection(val date: LocalDate, val title: String, val rows: List<EncounterRow>)

data class HistoryUiState(
    val sections: List<HistorySection> = emptyList(),
    val loaded: Boolean = false,
)

class HistoryViewModel(container: AppContainer) : ViewModel() {

    val uiState: StateFlow<HistoryUiState> = combine(
        container.encounterRepository.recent(HISTORY_LIMIT),
        TimeRanges.midnightTicker(),
    ) { rows, today ->
        // Выборка отсортирована по времени убыванию, groupBy сохраняет порядок — дни идут от новых к старым.
        val sections = rows
            .groupBy { TimeRanges.toLocalDate(it.timestamp) }
            .map { (date, dayRows) ->
                HistorySection(
                    date = date,
                    title = "${Format.dayTitle(date, today)} · ${dayRows.size}",
                    rows = dayRows,
                )
            }
        HistoryUiState(sections = sections, loaded = true)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HistoryUiState())

    companion object {
        /** История на экране, а не выгрузка базы в память. */
        const val HISTORY_LIMIT = 500
    }
}

@Composable
fun HistoryScreen(
    viewModel: HistoryViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    if (state.loaded && state.sections.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "История пуста.\n\nВключите обнаружение на главном экране и держите телефон при " +
                    "себе. Как только рядом окажется другой пользователь StreetPass с включённым " +
                    "приложением, встреча появится здесь.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    ) {
        state.sections.forEach { section ->
            item(key = "header-${section.date}") {
                Text(
                    text = section.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp, bottom = 4.dp),
                )
            }
            items(section.rows, key = { it.id }) { row ->
                EncounterItem(row)
                HorizontalDivider()
            }
        }
    }
}
