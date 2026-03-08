package fr.bonobo.dnsphere.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
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
        Profile::class
    ],
    version = 5,
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

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "dnsphere_database"
                )
                    .fallbackToDestructiveMigration()
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