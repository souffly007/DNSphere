package fr.bonobo.dnsphere.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Database(
    entities = [
        BlockLog::class,
        WhitelistItem::class,
        CustomList::class,
        CustomListDomain::class,
        ExcludedApp::class,
        ExternalList::class,
        ExternalListDomain::class,
        Schedule::class,
        Profile::class,
        ParentalControl::class
    ],
    version = 8,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun blockLogDao(): BlockLogDao
    abstract fun whitelistDao(): WhitelistDao
    abstract fun customListDao(): CustomListDao
    abstract fun excludedAppDao(): ExcludedAppDao
    abstract fun externalListDao(): ExternalListDao
    abstract fun scheduleDao(): ScheduleDao
    abstract fun profileDao(): ProfileDao
    abstract fun parentalControlDao(): ParentalControlDao

    companion object {

        @Volatile
        private var INSTANCE: AppDatabase? = null

        // Migration 5 → 6 : ajout table parental_control
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS parental_control (
                        id INTEGER PRIMARY KEY NOT NULL DEFAULT 1,
                        pinHash TEXT NOT NULL DEFAULT '',
                        pinEnabled INTEGER NOT NULL DEFAULT 0,
                        blockAdult INTEGER NOT NULL DEFAULT 0,
                        blockGaming INTEGER NOT NULL DEFAULT 0,
                        blockSocialMedia INTEGER NOT NULL DEFAULT 0,
                        blockStreaming INTEGER NOT NULL DEFAULT 0,
                        blockForums INTEGER NOT NULL DEFAULT 0,
                        scheduleEnabled INTEGER NOT NULL DEFAULT 0,
                        allowedStartHour INTEGER NOT NULL DEFAULT 8,
                        allowedStartMinute INTEGER NOT NULL DEFAULT 0,
                        allowedEndHour INTEGER NOT NULL DEFAULT 21,
                        allowedEndMinute INTEGER NOT NULL DEFAULT 0,
                        activeDays INTEGER NOT NULL DEFAULT 127
                    )
                """.trimIndent())
            }
        }

        // Migration 6 → 7 : vide, réservée
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Rien pour l'instant
            }
        }

        // Migration 7 → 8 : ajout forceBlock + suppression colonne note
        // SQLite ne supporte pas DROP COLUMN → on recrée la table
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // 1. Créer la nouvelle table avec le bon schéma
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS whitelist_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        domain TEXT NOT NULL,
                        addedAt INTEGER NOT NULL,
                        forceBlock INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
                // 2. Copier les données existantes (sans la colonne note)
                database.execSQL("""
                    INSERT INTO whitelist_new (id, domain, addedAt, forceBlock)
                    SELECT id, domain, addedAt, 0 FROM whitelist
                """.trimIndent())
                // 3. Supprimer l'ancienne table
                database.execSQL("DROP TABLE whitelist")
                // 4. Renommer la nouvelle
                database.execSQL("ALTER TABLE whitelist_new RENAME TO whitelist")
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "dnsphere_database"
                )
                    .addMigrations(
                        MIGRATION_5_6,
                        MIGRATION_6_7,
                        MIGRATION_7_8
                    )
                    .addCallback(object : Callback() {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            super.onCreate(db)
                            CoroutineScope(Dispatchers.IO).launch {
                                INSTANCE?.profileDao()?.insertAll(Profile.getDefaultProfiles())
                            }
                        }
                    })
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}