package com.carlmanning.carlsbrain.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.carlmanning.carlsbrain.data.local.dao.BucketDao
import com.carlmanning.carlsbrain.data.local.dao.NoteDao
import com.carlmanning.carlsbrain.data.local.dao.TodoDao
import com.carlmanning.carlsbrain.data.local.entity.BucketEntity
import com.carlmanning.carlsbrain.data.local.entity.NoteEntity
import com.carlmanning.carlsbrain.data.local.entity.TodoEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Database(
    entities = [BucketEntity::class, NoteEntity::class, TodoEntity::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun bucketDao(): BucketDao
    abstract fun noteDao(): NoteDao
    abstract fun todoDao(): TodoDao

    companion object {
        private const val DATABASE_NAME = "carlsbrain.db"

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context).also { INSTANCE = it }
            }
        }

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE todos ADD COLUMN isArchived INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE todos ADD COLUMN archivedAt INTEGER")
            }
        }

        private fun buildDatabase(context: Context): AppDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                DATABASE_NAME
            )
                .addMigrations(MIGRATION_1_2)
                .addCallback(SeedDatabaseCallback())
                .build()
        }
    }

    private class SeedDatabaseCallback : Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            super.onCreate(db)
            // Seed default buckets on first database creation
            CoroutineScope(Dispatchers.IO).launch {
                INSTANCE?.let { database ->
                    val dao = database.bucketDao()
                    if (dao.getBucketCount() == 0) {
                        val defaultBuckets = listOf(
                            BucketEntity(name = "SES", isVault = false, isUserCreated = false, colorHex = "#1565C0", sortOrder = 0),
                            BucketEntity(name = "Family", isVault = false, isUserCreated = false, colorHex = "#2E7D32", sortOrder = 1),
                            BucketEntity(name = "Work", isVault = false, isUserCreated = false, colorHex = "#E65100", sortOrder = 2),
                            BucketEntity(name = "Personal", isVault = false, isUserCreated = false, colorHex = "#6750A4", sortOrder = 3),
                            BucketEntity(name = "Kink", isVault = true, isUserCreated = false, colorHex = "#880E4F", sortOrder = 4),
                            BucketEntity(name = "Other", isVault = false, isUserCreated = false, colorHex = "#37474F", sortOrder = 5)
                        )
                        dao.insertBuckets(defaultBuckets)
                    }
                }
            }
        }
    }
}
