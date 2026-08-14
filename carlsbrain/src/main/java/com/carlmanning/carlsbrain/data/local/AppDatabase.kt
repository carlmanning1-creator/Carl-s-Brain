package com.carlmanning.carlsbrain.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.carlmanning.carlsbrain.data.local.dao.BucketDao
import com.carlmanning.carlsbrain.data.local.dao.CalendarEventDao
import com.carlmanning.carlsbrain.data.local.dao.ChatDao
import com.carlmanning.carlsbrain.data.local.dao.MeetingDao
import com.carlmanning.carlsbrain.data.local.dao.NoteDao
import com.carlmanning.carlsbrain.data.local.dao.RecentlyViewedDao
import com.carlmanning.carlsbrain.data.local.dao.SubtaskDao
import com.carlmanning.carlsbrain.data.local.dao.TodoDao
import com.carlmanning.carlsbrain.data.local.dao.TombstoneDao
import com.carlmanning.carlsbrain.data.local.entity.BucketEntity
import com.carlmanning.carlsbrain.data.local.entity.CalendarEventEntity
import com.carlmanning.carlsbrain.data.local.entity.ChatMessageEntity
import com.carlmanning.carlsbrain.data.local.entity.ChatThreadEntity
import com.carlmanning.carlsbrain.data.local.entity.MeetingEntity
import com.carlmanning.carlsbrain.data.local.entity.NoteEntity
import com.carlmanning.carlsbrain.data.local.entity.RecentlyViewedEntity
import com.carlmanning.carlsbrain.data.local.entity.SubtaskEntity
import com.carlmanning.carlsbrain.data.local.entity.TodoEntity
import com.carlmanning.carlsbrain.data.local.entity.TombstoneEntity

@Database(
    entities = [BucketEntity::class, NoteEntity::class, TodoEntity::class, SubtaskEntity::class, MeetingEntity::class, CalendarEventEntity::class, TombstoneEntity::class, ChatThreadEntity::class, ChatMessageEntity::class, RecentlyViewedEntity::class],
    version = 22,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun bucketDao(): BucketDao
    abstract fun noteDao(): NoteDao
    abstract fun todoDao(): TodoDao
    abstract fun subtaskDao(): SubtaskDao
    abstract fun meetingDao(): MeetingDao
    abstract fun calendarEventDao(): CalendarEventDao
    abstract fun tombstoneDao(): TombstoneDao
    abstract fun chatDao(): ChatDao
    abstract fun recentlyViewedDao(): RecentlyViewedDao

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

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE notes ADD COLUMN attachments TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE todos ADD COLUMN reminderAt INTEGER")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE todos ADD COLUMN calendarEventId TEXT")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE notes ADD COLUMN reminderAt INTEGER")
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS subtasks (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        todoId INTEGER NOT NULL,
                        title TEXT NOT NULL,
                        isDone INTEGER NOT NULL DEFAULT 0,
                        sortOrder INTEGER NOT NULL DEFAULT 0,
                        FOREIGN KEY (todoId) REFERENCES todos(id) ON DELETE CASCADE
                    )"""
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_subtasks_todoId ON subtasks(todoId)")
                db.execSQL("ALTER TABLE todos ADD COLUMN sortOrder INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE notes ADD COLUMN sortOrder INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE notes ADD COLUMN tags TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE notes ADD COLUMN isPinned INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE todos ADD COLUMN isPinned INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE todos ADD COLUMN attachments TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS meetings (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        title TEXT NOT NULL DEFAULT '',
                        recordedAt INTEGER NOT NULL DEFAULT 0,
                        durationMs INTEGER NOT NULL DEFAULT 0,
                        localAudioPath TEXT NOT NULL DEFAULT '',
                        driveFolderId TEXT NOT NULL DEFAULT '',
                        driveAudioFileId TEXT NOT NULL DEFAULT '',
                        transcript TEXT NOT NULL DEFAULT '',
                        summary TEXT NOT NULL DEFAULT '',
                        pendingActionItems TEXT NOT NULL DEFAULT '',
                        status TEXT NOT NULL DEFAULT 'IDLE',
                        updatedAt INTEGER NOT NULL DEFAULT 0
                    )"""
                )
            }
        }

        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE notes ADD COLUMN deletedAt INTEGER")
                db.execSQL("ALTER TABLE todos ADD COLUMN deletedAt INTEGER")
                db.execSQL("ALTER TABLE meetings ADD COLUMN deletedAt INTEGER")
            }
        }

        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS calendar_events (
                        id TEXT PRIMARY KEY NOT NULL,
                        title TEXT NOT NULL,
                        startMs INTEGER NOT NULL,
                        endMs INTEGER NOT NULL,
                        isAllDay INTEGER NOT NULL DEFAULT 0,
                        location TEXT,
                        colorHex TEXT,
                        calendarName TEXT,
                        cachedAt INTEGER NOT NULL DEFAULT 0
                    )"""
                )
            }
        }

        val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE todos ADD COLUMN leadDays INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE notes ADD COLUMN attachmentUris TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Drop dead attachmentUris column by recreating the notes table without it
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `notes_new` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `title` TEXT NOT NULL,
                        `content` TEXT NOT NULL,
                        `bucketId` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        `isSynced` INTEGER NOT NULL DEFAULT 0,
                        `attachments` TEXT NOT NULL DEFAULT '',
                        `reminderAt` INTEGER,
                        `sortOrder` INTEGER NOT NULL DEFAULT 0,
                        `tags` TEXT NOT NULL DEFAULT '',
                        `isPinned` INTEGER NOT NULL DEFAULT 0,
                        `deletedAt` INTEGER,
                        FOREIGN KEY (`bucketId`) REFERENCES `buckets`(`id`) ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("""
                    INSERT INTO notes_new SELECT
                        id, title, content, bucketId, createdAt, updatedAt,
                        isSynced, attachments, reminderAt, sortOrder, tags, isPinned, deletedAt
                    FROM notes
                """.trimIndent())
                db.execSQL("DROP TABLE notes")
                db.execSQL("ALTER TABLE notes_new RENAME TO notes")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_notes_bucketId` ON `notes`(`bucketId`)")
            }
        }

        val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_todos_isDone` ON `todos`(`isDone`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_todos_priority` ON `todos`(`priority`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_todos_deletedAt` ON `todos`(`deletedAt`)")
            }
        }

        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS tombstones (
                        id INTEGER NOT NULL,
                        type TEXT NOT NULL,
                        deletedAt INTEGER NOT NULL,
                        PRIMARY KEY (id, type)
                    )"""
                )
            }
        }

        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Recreate todos table with priority as INTEGER rank instead of TEXT name
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `todos_new` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `title` TEXT NOT NULL,
                        `bucketId` INTEGER NOT NULL,
                        `priority` INTEGER NOT NULL DEFAULT 2,
                        `dueDate` INTEGER,
                        `reminderAt` INTEGER,
                        `recurrence` TEXT NOT NULL,
                        `calendarEventId` TEXT,
                        `isDone` INTEGER NOT NULL DEFAULT 0,
                        `isArchived` INTEGER NOT NULL DEFAULT 0,
                        `archivedAt` INTEGER,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        `isSynced` INTEGER NOT NULL DEFAULT 0,
                        `sortOrder` INTEGER NOT NULL DEFAULT 0,
                        `isPinned` INTEGER NOT NULL DEFAULT 0,
                        `attachments` TEXT NOT NULL DEFAULT '',
                        `deletedAt` INTEGER,
                        FOREIGN KEY (`bucketId`) REFERENCES `buckets`(`id`) ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("""
                    INSERT INTO `todos_new`
                    SELECT id, title, bucketId,
                        CASE priority
                            WHEN 'URGENT' THEN 0
                            WHEN 'HIGH'   THEN 1
                            WHEN 'NORMAL' THEN 2
                            WHEN 'SOMEDAY' THEN 3
                            ELSE 2
                        END,
                        dueDate, reminderAt, recurrence, calendarEventId,
                        isDone, isArchived, archivedAt, createdAt, updatedAt,
                        isSynced, sortOrder, isPinned, attachments, deletedAt
                    FROM todos
                """.trimIndent())
                db.execSQL("DROP TABLE todos")
                db.execSQL("ALTER TABLE todos_new RENAME TO todos")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_todos_bucketId` ON `todos`(`bucketId`)")
            }
        }

        val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `chat_threads` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `title` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `chat_messages` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `threadId` INTEGER NOT NULL,
                        `content` TEXT NOT NULL,
                        `isFromUser` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        FOREIGN KEY (`threadId`) REFERENCES `chat_threads`(`id`) ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_chat_messages_threadId` ON `chat_messages`(`threadId`)")
            }
        }

        val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE meetings ADD COLUMN firefliesId TEXT")
            }
        }

        val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE meetings ADD COLUMN bucketId INTEGER")
                db.execSQL("ALTER TABLE todos ADD COLUMN sourceMeetingId INTEGER")
                db.execSQL("ALTER TABLE todos ADD COLUMN sourceNoteId INTEGER")
                db.execSQL("ALTER TABLE notes ADD COLUMN sourceMeetingId INTEGER")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS recently_viewed (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "itemType TEXT NOT NULL, " +
                        "itemId INTEGER NOT NULL, " +
                        "title TEXT NOT NULL, " +
                        "bucketId INTEGER, " +
                        "viewedAt INTEGER NOT NULL)"
                )
            }
        }

        val MIGRATION_21_22 = object : Migration(21, 22) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE todos ADD COLUMN estimateMinutes INTEGER")
            }
        }

        private fun buildDatabase(context: Context): AppDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                DATABASE_NAME
            )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21, MIGRATION_21_22)
                // Forward migrations must be explicit: a schema mismatch should fail loudly
                // rather than silently wiping Carl's data. Downgrades are different -- without
                // this, reinstalling an older APK over a newer database throws on every launch
                // and the only way out is clearing app data.
                .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
                .addCallback(SeedDatabaseCallback())
                .build()
        }
    }

    private class SeedDatabaseCallback : Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            super.onCreate(db)
            // Use execSQL directly — INSTANCE is null during build() callback
            db.beginTransaction()
            try {
                db.execSQL("INSERT INTO buckets (name,isVault,isUserCreated,colorHex,sortOrder) VALUES ('SES',0,0,'#1565C0',0)")
                db.execSQL("INSERT INTO buckets (name,isVault,isUserCreated,colorHex,sortOrder) VALUES ('Family',0,0,'#2E7D32',1)")
                db.execSQL("INSERT INTO buckets (name,isVault,isUserCreated,colorHex,sortOrder) VALUES ('Work',0,0,'#E65100',2)")
                db.execSQL("INSERT INTO buckets (name,isVault,isUserCreated,colorHex,sortOrder) VALUES ('Personal',0,0,'#6750A4',3)")
                db.execSQL("INSERT INTO buckets (name,isVault,isUserCreated,colorHex,sortOrder) VALUES ('Kink',1,0,'#880E4F',4)")
                db.execSQL("INSERT INTO buckets (name,isVault,isUserCreated,colorHex,sortOrder) VALUES ('Other',0,0,'#37474F',5)")
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }
}
