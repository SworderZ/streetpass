package space.megaworld.streetpass.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [PeerEntity::class, EncounterEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun peerDao(): PeerDao

    abstract fun encounterDao(): EncounterDao

    companion object {
        /** v2: ник peer'а, пойманный из scan-response. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE peers ADD COLUMN nickname TEXT")
            }
        }

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "streetpass.db")
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
