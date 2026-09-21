package space.megaworld.streetpass.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [PeerEntity::class, EncounterEntity::class, AchievementEntity::class],
    version = 3,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun peerDao(): PeerDao

    abstract fun encounterDao(): EncounterDao

    abstract fun achievementDao(): AchievementDao

    companion object {
        /** v2: ник peer'а, пойманный из scan-response. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE peers ADD COLUMN nickname TEXT")
            }
        }

        /** v3: друзья и локальные имена у peer'ов, таблица достижений. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE peers ADD COLUMN friendSince INTEGER")
                db.execSQL("ALTER TABLE peers ADD COLUMN alias TEXT")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS achievements (
                        id TEXT NOT NULL PRIMARY KEY,
                        unlockedAt INTEGER NOT NULL,
                        seenAt INTEGER
                    )
                    """.trimIndent(),
                )
            }
        }

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "streetpass.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
    }
}
