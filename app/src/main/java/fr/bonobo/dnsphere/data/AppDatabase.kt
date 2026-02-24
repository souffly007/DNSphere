package fr.bonobo.dnsphere.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        BlockLog::class,
        WhitelistItem::class,
        CustomList::class,
        CustomDomain::class,
        ExcludedApp::class
    ],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun blockLogDao(): BlockLogDao
    abstract fun whitelistDao(): WhitelistDao
    abstract fun customListDao(): CustomListDao
    abstract fun excludedAppDao(): ExcludedAppDao

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
                    .build()

                INSTANCE = instance
                instance
            }
        }
    }
}