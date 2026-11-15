package com.carlmanning.carlsbrain

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.carlmanning.carlsbrain.data.local.AppDatabase
import com.carlmanning.carlsbrain.data.local.entity.BucketEntity
import com.carlmanning.carlsbrain.data.local.entity.TodoEntity
import com.carlmanning.carlsbrain.domain.model.Priority
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The two queries behind the Today surfaces, against a real (in-memory) database.
 *
 * These are SQL, so a plain unit test cannot reach them — and they are where the vault rule
 * for the widget and the notifications lives. A to-do in a vault bucket appearing on the lock
 * screen would be the worst thing this feature could do, so that is asserted, not assumed.
 *
 * Runs on a phone or emulator: Android Studio → right-click → Run.
 */
@RunWith(AndroidJUnit4::class)
class TodayQueriesTest {

    private lateinit var db: AppDatabase
    private var open = 0L
    private var vault = 0L
    private val dayEnd = 2_000_000_000_000L
    private val before = dayEnd - 60_000L
    private val after = dayEnd + 60_000L

    @Before
    fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AppDatabase::class.java
        ).build()
        open = db.bucketDao().insertBucket(BucketEntity(name = "Family"))
        vault = db.bucketDao().insertBucket(BucketEntity(name = "Hidden", isVault = true))
    }

    @After
    fun tearDown() = db.close()

    private suspend fun add(
        title: String,
        bucket: Long = open,
        priority: Priority = Priority.NORMAL,
        due: Long? = null,
        done: Boolean = false,
        archived: Boolean = false,
        deleted: Boolean = false
    ) = db.todoDao().insertTodo(
        TodoEntity(
            title = title,
            bucketId = bucket,
            priority = priority.rank,
            dueDate = due,
            isDone = done,
            isArchived = archived,
            deletedAt = if (deleted) 1L else null
        )
    )

    @Test
    fun toSort_counts_only_open_undated_non_urgent_non_vault() = runBlocking {
        add("counts")
        add("counts, someday", priority = Priority.SOMEDAY)
        add("has a date", due = before)
        add("urgent", priority = Priority.URGENT)
        add("high", priority = Priority.HIGH)
        add("done", done = true)
        add("archived", archived = true)
        add("deleted", deleted = true)
        add("in the vault", bucket = vault)

        assertEquals(2, db.todoDao().countToSortNonVault())
    }

    @Test
    fun candidates_never_include_a_vault_todo() = runBlocking {
        add("overdue, visible", due = before)
        add("overdue, vault", bucket = vault, due = before)
        add("urgent, vault", bucket = vault, priority = Priority.URGENT)

        val titles = db.todoDao().getTodayCandidatesNonVault(dayEnd).map { it.title }
        assertEquals(listOf("overdue, visible"), titles)
    }

    @Test
    fun candidates_are_dated_up_to_today_or_urgent_and_high() = runBlocking {
        add("due before day end", due = before)
        add("due tomorrow", due = after)
        add("urgent, undated", priority = Priority.URGENT)
        add("high, tomorrow", priority = Priority.HIGH, due = after)
        add("normal, undated")
        add("done", due = before, done = true)

        val titles = db.todoDao().getTodayCandidatesNonVault(dayEnd).map { it.title }.toSet()
        assertEquals(setOf("due before day end", "urgent, undated", "high, tomorrow"), titles)
    }

    @Test
    fun every_open_visible_todo_is_a_candidate_or_to_sort_or_dated_later() = runBlocking {
        // The property the "to sort" pill exists for: nothing open and visible falls through
        // both queries except a to-do that simply is not due yet.
        add("a"); add("b", priority = Priority.SOMEDAY)
        add("c", due = before); add("d", priority = Priority.HIGH)
        add("later", due = after)

        val candidates = db.todoDao().getTodayCandidatesNonVault(dayEnd).size
        val toSort = db.todoDao().countToSortNonVault()
        assertEquals(2, candidates)
        assertEquals(2, toSort)
        assertTrue(candidates + toSort == 4)
    }
}
