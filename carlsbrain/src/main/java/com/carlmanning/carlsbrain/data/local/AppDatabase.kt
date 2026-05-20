package com.carlmanning.carlsbrain.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.carlmanning.carlsbrain.data.local.dao.BucketDao
import com.carlmanning.carlsbrain.data.local.dao.NoteDao
import com.carlmanning.carlsbrain.data.local.dao.TodoDao
import com.carlmanning.carlsbrain.data.local.entity.BucketEntity
import com.carlmanning.carlsbrain.data.local.entity.NoteEntity
import com.carlmanning.carlsbrain.data.local.entity.TodoEntity

@Database(
    entities = [BucketEntity::class, NoteEntity::class, TodoEntity::class],
    version = 1,
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

        private fun buildDatabase(context: Context): AppDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                DATABASE_NAME
            )
                .addCallback(SeedDatabaseCallback)
                // UAT-only safety net: if Carl has an older debug build installed, schema changes
                // wipe the local DB instead of throwing IllegalStateException. Remove before any
                // real data is trusted to the app.
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
        }
    }

    /**
     * Seeds the default life-bucket rows the first (and only the first) time the database file
     * is created. Implemented with raw [SupportSQLiteDatabase.execSQL] rather than DAO calls so
     * we don't re-enter Room while it's still initialising, and don't depend on the singleton
     * INSTANCE field being assigned — the previous implementation captured `INSTANCE` which is
     * null at the moment `Room.databaseBuilder(...).build()` is mid-flight.
     */
    private object SeedDatabaseCallback : Callback() {
        // (name, isVault, colorHex, sortOrder). isUserCreated is always 0 for defaults.
        private val DEFAULT_BUCKETS = listOf(
            Quadruple("SES", 0, "#1565C0", 0),
            Quadruple("Family", 0, "#2E7D32", 1),
            Quadruple("Work", 0, "#E65100", 2),
            Quadruple("Personal", 0, "#6750A4", 3),
            Quadruple("Kink", 1, "#880E4F", 4),
            Quadruple("Other", 0, "#37474F", 5),
        )

        override fun onCreate(db: SupportSQLiteDatabase) {
            super.onCreate(db)
            db.beginTransaction()
            try {
                DEFAULT_BUCKETS.forEach { (name, isVault, colorHex, sortOrder) ->
                    db.execSQL(
                        "INSERT INTO buckets (name, isVault, isUserCreated, colorHex, sortOrder) " +
                            "VALUES (?, ?, 0, ?, ?)",
                        arrayOf<Any>(name, isVault, colorHex, sortOrder)
                    )
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }

        private data class Quadruple(
            val name: String,
            val isVault: Int,
            val colorHex: String,
            val sortOrder: Int,
        )
    }
}
