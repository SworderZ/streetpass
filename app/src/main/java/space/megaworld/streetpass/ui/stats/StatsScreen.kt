package space.megaworld.streetpass.ui.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import space.megaworld.streetpass.AppContainer
import space.megaworld.streetpass.core.Hex
import space.megaworld.streetpass.data.DailyCount
import space.megaworld.streetpass.data.db.PeerEntity
import space.megaworld.streetpass.ui.AppViewModelProvider
import space.megaworld.streetpass.ui.Format
import space.megaworld.streetpass.ui.components.BarColumn
import space.megaworld.streetpass.ui.components.LabeledRow
import space.megaworld.streetpass.ui.components.SectionTitle
import space.megaworld.streetpass.ui.components.StatTile

data class StatsUiState(
    val todayEncounters: Int = 0,
    val todayPeople: Int = 0,
    val weekEncounters: Int = 0,
    val weekPeople: Int = 0,
    val weekDaily: List<DailyCount> = emptyList(),
    val totalEncounters: Int = 0,
    val totalPeers: Int = 0,
    val topPeers: List<PeerEntity> = emptyList(),
) {
    val averagePerPerson: String
        get() = if (totalPeers == 0) "—" else String.format(Format.locale, "%.1f", totalEncounters.toFloat() / totalPeers)
}

class StatsViewModel(container: AppContainer) : ViewModel() {

    private class Today(val encounters: Int, val people: Int)

    private class Week(val encounters: Int, val people: Int, val daily: List<DailyCount>)

    private class Total(val encounters: Int, val peers: Int, val top: List<PeerEntity>)

    private val repository = container.encounterRepository

    private val today = combine(repository.todayEncounters, repository.todayPeers) { e, p -> Today(e, p) }

    private val week = combine(repository.weekEncounters, repository.weekPeers, repository.weekDaily) { e, p, d ->
        Week(e, p, d)
    }

    private val total = combine(repository.totalEncounters, repository.totalPeers, repository.topPeers(TOP_LIMIT)) { e, p, t ->
        Total(e, p, t)
    }

    val uiState: StateFlow<StatsUiState> = combine(today, week, total) { today, week, total ->
        StatsUiState(
            todayEncounters = today.encounters,
            todayPeople = today.people,
            weekEncounters = week.encounters,
            weekPeople = week.people,
            weekDaily = week.daily,
            totalEncounters = total.encounters,
            totalPeers = total.peers,
            topPeers = total.top,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatsUiState())

    companion object {
        const val TOP_LIMIT = 5
    }
}

@Composable
fun StatsScreen(
    viewModel: StatsViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionTitle("Сегодня")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile("встреч", state.todayEncounters.toString(), Modifier.weight(1f))
                StatTile("людей", state.todayPeople.toString(), Modifier.weight(1f))
            }
        }

        item {
            SectionTitle("Последние 7 дней")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile("встреч", state.weekEncounters.toString(), Modifier.weight(1f))
                StatTile("людей", state.weekPeople.toString(), Modifier.weight(1f))
            }
            Spacer(modifier = Modifier.height(12.dp))
            WeekChart(daily = state.weekDaily)
        }

        item {
            SectionTitle("За всё время")
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    LabeledRow("Встреч", state.totalEncounters.toString())
                    LabeledRow("Уникальных людей", state.totalPeers.toString())
                    LabeledRow("Среднее встреч на человека", state.averagePerPerson)
                }
            }
        }

        item {
            SectionTitle("Чаще всего встречаются")
            if (state.topPeers.isEmpty()) {
                Text(
                    text = "Пока некого показать.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                        state.topPeers.forEachIndexed { index, peer ->
                            TopPeerRow(index + 1, peer)
                            if (index != state.topPeers.lastIndex) HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WeekChart(daily: List<DailyCount>) {
    val max = daily.maxOfOrNull { it.count } ?: 0
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 16.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            daily.forEachIndexed { index, day ->
                BarColumn(
                    value = day.count,
                    max = max,
                    label = Format.weekdayShort(day.date),
                    barMaxHeight = 96.dp,
                    highlighted = index == daily.lastIndex,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun TopPeerRow(position: Int, peer: PeerEntity) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(
                text = "$position. ${Hex.short(peer.peerId)}",
                style = MaterialTheme.typography.bodyLarge,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                text = "последняя: ${Format.dateTime(peer.lastEncounterAt)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = Format.encounters(peer.encounterCount),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
