package space.megaworld.streetpass.ui.stats

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
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
import space.megaworld.streetpass.R
import space.megaworld.streetpass.data.DailyCount
import space.megaworld.streetpass.data.achievements.AchievementProgress
import space.megaworld.streetpass.data.db.PeerEntity
import space.megaworld.streetpass.ui.AppViewModelProvider
import space.megaworld.streetpass.ui.Format
import space.megaworld.streetpass.ui.components.AchievementTile
import space.megaworld.streetpass.ui.components.BarColumn
import space.megaworld.streetpass.ui.components.LabeledRow
import space.megaworld.streetpass.ui.components.PeerName
import space.megaworld.streetpass.ui.components.SectionTitle
import space.megaworld.streetpass.ui.components.StatTile
import space.megaworld.streetpass.ui.friends.AddFriendDialog
import space.megaworld.streetpass.ui.friends.MyInviteDialog
import space.megaworld.streetpass.ui.peer.PeerDialog

data class StatsUiState(
    val todayEncounters: Int = 0,
    val todayPeople: Int = 0,
    val weekEncounters: Int = 0,
    val weekPeople: Int = 0,
    val weekDaily: List<DailyCount> = emptyList(),
    val totalEncounters: Int = 0,
    val totalPeers: Int = 0,
    val topPeers: List<PeerEntity> = emptyList(),
    val friends: List<PeerEntity> = emptyList(),
    val achievements: List<AchievementProgress> = emptyList(),
) {
    val averagePerPerson: String
        get() = if (totalPeers == 0) "—" else Format.decimal(totalEncounters.toFloat() / totalPeers)
}

class StatsViewModel(container: AppContainer) : ViewModel() {

    private class Today(val encounters: Int, val people: Int)

    private class Week(val encounters: Int, val people: Int, val daily: List<DailyCount>)

    private class Total(val encounters: Int, val peers: Int, val top: List<PeerEntity>, val friends: List<PeerEntity>)

    private val repository = container.encounterRepository

    private val today = combine(repository.todayEncounters, repository.todayPeers) { e, p -> Today(e, p) }

    private val week = combine(repository.weekEncounters, repository.weekPeers, repository.weekDaily) { e, p, d ->
        Week(e, p, d)
    }

    private val total = combine(
        repository.totalEncounters,
        repository.totalPeers,
        repository.topPeers(TOP_LIMIT),
        repository.friends,
    ) { e, p, t, f -> Total(e, p, t, f) }

    val uiState: StateFlow<StatsUiState> = combine(
        today,
        week,
        total,
        container.achievementRepository.visibleProgress,
    ) { today, week, total, achievements ->
        StatsUiState(
            todayEncounters = today.encounters,
            todayPeople = today.people,
            weekEncounters = week.encounters,
            weekPeople = week.people,
            weekDaily = week.daily,
            totalEncounters = total.encounters,
            totalPeers = total.peers,
            topPeers = total.top,
            friends = total.friends,
            achievements = achievements,
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
    var selectedPeer by remember { mutableStateOf<String?>(null) }
    var showMyInvite by remember { mutableStateOf(false) }
    var showAddFriend by remember { mutableStateOf(false) }

    selectedPeer?.let { peerId ->
        PeerDialog(peerId = peerId, onDismiss = { selectedPeer = null })
    }
    if (showMyInvite) MyInviteDialog(onDismiss = { showMyInvite = false })
    if (showAddFriend) AddFriendDialog(onDismiss = { showAddFriend = false })

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionTitle(stringResource(R.string.stats_today))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile(stringResource(R.string.stat_encounters), state.todayEncounters.toString(), Modifier.weight(1f))
                StatTile(stringResource(R.string.stat_people), state.todayPeople.toString(), Modifier.weight(1f))
            }
        }

        item {
            SectionTitle(stringResource(R.string.stats_week))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile(stringResource(R.string.stat_encounters), state.weekEncounters.toString(), Modifier.weight(1f))
                StatTile(stringResource(R.string.stat_people), state.weekPeople.toString(), Modifier.weight(1f))
            }
            Spacer(modifier = Modifier.height(12.dp))
            WeekChart(daily = state.weekDaily)
        }

        item {
            SectionTitle(stringResource(R.string.stats_all_time))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    LabeledRow(stringResource(R.string.row_encounters), state.totalEncounters.toString())
                    LabeledRow(stringResource(R.string.row_people), state.totalPeers.toString())
                    LabeledRow(stringResource(R.string.row_avg), state.averagePerPerson)
                }
            }
        }

        item {
            SectionTitle(stringResource(R.string.stats_top))
            if (state.topPeers.isEmpty()) {
                Text(
                    text = stringResource(R.string.top_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                        state.topPeers.forEachIndexed { index, peer ->
                            TopPeerRow(index + 1, peer, onClick = { selectedPeer = peer.peerId })
                            if (index != state.topPeers.lastIndex) HorizontalDivider()
                        }
                    }
                }
            }
        }

        item {
            SectionTitle(stringResource(R.string.section_friends))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(onClick = { showMyInvite = true }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.friends_my_invite))
                }
                OutlinedButton(onClick = { showAddFriend = true }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.friends_add))
                }
            }
            if (state.friends.isEmpty()) {
                Text(
                    text = stringResource(R.string.friends_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                        state.friends.forEachIndexed { index, peer ->
                            FriendRow(peer, onClick = { selectedPeer = peer.peerId })
                            if (index != state.friends.lastIndex) HorizontalDivider()
                        }
                    }
                }
            }
        }

        item { SectionTitle(stringResource(R.string.section_achievements)) }
        // Сетка из двух колонок внутри LazyColumn: LazyVerticalGrid сюда не вложить,
        // а достижений немного, так что строки по две плитки — самое простое.
        items(state.achievements.chunked(2)) { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                pair.forEach { AchievementTile(it, Modifier.weight(1f)) }
                if (pair.size == 1) Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun FriendRow(peer: PeerEntity, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            PeerName(nickname = peer.nickname, peerId = peer.peerId, alias = peer.alias, friend = true)
            peer.friendSince?.let { since ->
                Text(
                    text = stringResource(R.string.friend_since, Format.dateTime(since)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            text = pluralStringResource(R.plurals.encounters_count, peer.encounterCount, peer.encounterCount),
            style = MaterialTheme.typography.bodyMedium,
        )
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
private fun TopPeerRow(position: Int, peer: PeerEntity, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            PeerName(
                nickname = peer.nickname,
                peerId = peer.peerId,
                alias = peer.alias,
                friend = peer.isFriend,
                prefix = "$position. ",
            )
            Text(
                text = stringResource(R.string.top_last, Format.dateTime(peer.lastEncounterAt)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = pluralStringResource(R.plurals.encounters_count, peer.encounterCount, peer.encounterCount),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
