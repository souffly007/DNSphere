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
        Profile::class,
        ParentalControl::class,
        UserRule::class,
        AppRule::class,
        ProfileSchedule::class
    ],
    version = 11,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun blockLogDao(): BlockLogDao
    abstract fun whitelistDao(): WhitelistDao
    abstract fun customListDao(): CustomListDao
    abstract fun excludedAppDao(): ExcludedAppDao
    abstract fun externalListDao(): ExternalListDao
    abstract fun profileDao(): ProfileDao
    abstract fun parentalControlDao(): ParentalControlDao
    abstract fun userRuleDao(): UserRuleDao
    abstract fun appRuleDao(): AppRuleDao
    abstract fun profileScheduleDao(): ProfileScheduleDao

    companion object {

        @Volatile
        private var INSTANCE: AppDatabase? = null

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
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

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) { }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS whitelist_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        domain TEXT NOT NULL,
                        addedAt INTEGER NOT NULL,
                        forceBlock INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
                db.execSQL("""
                    INSERT INTO whitelist_new (id, domain, addedAt, forceBlock)
                    SELECT id, domain, addedAt, 0 FROM whitelist
                """.trimIndent())
                db.execSQL("DROP TABLE whitelist")
                db.execSQL("ALTER TABLE whitelist_new RENAME TO whitelist")
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS user_rules (
                        id        INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        pattern   TEXT    NOT NULL,
                        type      TEXT    NOT NULL,
                        action    TEXT    NOT NULL,
                        enabled   INTEGER NOT NULL DEFAULT 1,
                        comment   TEXT    NOT NULL DEFAULT '',
                        createdAt INTEGER NOT NULL
                    )
                """.trimIndent())
            }
        }

        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS app_rules (
                        uid         INTEGER PRIMARY KEY NOT NULL,
                        packageName TEXT    NOT NULL,
                        appName     TEXT    NOT NULL,
                        rule        TEXT    NOT NULL,
                        customDns   TEXT    NOT NULL DEFAULT '',
                        createdAt   INTEGER NOT NULL
                    )
                """.trimIndent())
            }
        }

        // Migration 10 → 11 : ajout table profile_schedules
        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS profile_schedules (
                        id          INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        profileId   INTEGER NOT NULL,
                        label       TEXT    NOT NULL DEFAULT '',
                        startHour   INTEGER NOT NULL,
                        startMinute INTEGER NOT NULL,
                        endHour     INTEGER NOT NULL,
                        endMinute   INTEGER NOT NULL,
                        activeDays  INTEGER NOT NULL DEFAULT 127,
                        enabled     INTEGER NOT NULL DEFAULT 1,
                        createdAt   INTEGER NOT NULL,
                        FOREIGN KEY (profileId) REFERENCES profiles(id) ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_profile_schedules_profileId ON profile_schedules(profileId)")
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
                        MIGRATION_7_8,
                        MIGRATION_8_9,
                        MIGRATION_9_10,
                        MIGRATION_10_11
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