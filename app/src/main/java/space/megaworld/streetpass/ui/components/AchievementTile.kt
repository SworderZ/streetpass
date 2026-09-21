package space.megaworld.streetpass.ui.components

import androidx.annotation.PluralsRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Handshake
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import space.megaworld.streetpass.R
import space.megaworld.streetpass.data.achievements.Achievement
import space.megaworld.streetpass.data.achievements.AchievementKind
import space.megaworld.streetpass.data.achievements.AchievementProgress
import space.megaworld.streetpass.ui.Format

@Composable
fun AchievementTile(
    progress: AchievementProgress,
    modifier: Modifier = Modifier,
) {
    val achievement = progress.achievement
    val colors = if (progress.unlocked) {
        CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    } else {
        CardDefaults.cardColors()
    }
    Card(modifier = modifier, colors = colors) {
        Column(modifier = Modifier.padding(12.dp)) {
            Icon(
                imageVector = achievement.kind.icon,
                contentDescription = null,
                tint = if (progress.unlocked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(28.dp),
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = achievementTitle(achievement), style = MaterialTheme.typography.titleSmall)
            Text(
                text = achievementDescription(achievement),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { progress.fraction },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = progress.unlockedAt?.let { stringResource(R.string.ach_unlocked_at, Format.dateTime(it)) }
                    ?: stringResource(R.string.ach_progress, progress.current, achievement.threshold),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun achievementTitle(achievement: Achievement): String =
    pluralStringResource(achievement.kind.titleRes, achievement.threshold, achievement.threshold)

@Composable
fun achievementDescription(achievement: Achievement): String =
    pluralStringResource(achievement.kind.descriptionRes, achievement.threshold, achievement.threshold)

private val AchievementKind.icon: ImageVector
    get() = when (this) {
        AchievementKind.PEOPLE -> Icons.Filled.Groups
        AchievementKind.ENCOUNTERS -> Icons.Filled.Handshake
        AchievementKind.FRIENDS -> Icons.Filled.Favorite
        AchievementKind.FRIEND_ENCOUNTERS -> Icons.Filled.Star
        AchievementKind.STREAK -> Icons.Filled.LocalFireDepartment
    }

private val AchievementKind.titleRes: Int
    @PluralsRes get() = when (this) {
        AchievementKind.PEOPLE -> R.plurals.ach_people_title
        AchievementKind.ENCOUNTERS -> R.plurals.ach_encounters_title
        AchievementKind.FRIENDS -> R.plurals.ach_friends_title
        AchievementKind.FRIEND_ENCOUNTERS -> R.plurals.ach_friend_encounters_title
        AchievementKind.STREAK -> R.plurals.ach_streak_title
    }

private val AchievementKind.descriptionRes: Int
    @PluralsRes get() = when (this) {
        AchievementKind.PEOPLE -> R.plurals.ach_people_desc
        AchievementKind.ENCOUNTERS -> R.plurals.ach_encounters_desc
        AchievementKind.FRIENDS -> R.plurals.ach_friends_desc
        AchievementKind.FRIEND_ENCOUNTERS -> R.plurals.ach_friend_encounters_desc
        AchievementKind.STREAK -> R.plurals.ach_streak_desc
    }
