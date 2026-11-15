package com.carlmanning.carlsbrain.data.today

import android.content.Context
import androidx.room.InvalidationTracker
import com.carlmanning.carlsbrain.CarlsBrainApp
import com.carlmanning.carlsbrain.data.local.AppDatabase
import com.carlmanning.carlsbrain.data.local.ErrorLog
import com.carlmanning.carlsbrain.widget.updateAllTodayWidgets
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Redraws every Today surface — the widgets, the pinned notification, and any check-in still
 * in the shade — from one fresh snapshot.
 *
 * One function so that "something about today changed" has one meaning. Each surface is
 * guarded separately: a launcher that refuses a widget update must not stop the notification
 * being corrected, and neither is worth crashing a broadcast over.
 */
object TodaySurfaces {

    suspend fun refresh(context: Context) {
        val app = context.applicationContext
        runCatching { updateAllTodayWidgets(app) }
            .onFailure { ErrorLog.record("TodaySurfaces/widgets", it) }
        runCatching { TodayNotifications.refresh(app, TodayRepository.load(app)) }
            .onFailure { ErrorLog.record("TodaySurfaces/notifications", it) }
        refreshWeatherIfStale(app)
    }

    @Volatile private var fetchingWeather = false
    @Volatile private var lastWeatherAttempt = 0L

    /**
     * Fetches Dubbo's weather if the cached line is over an hour old, then redraws.
     *
     * Kicked from the widget's render and from every refresh, but it is never *part* of a
     * render: the widget draws whatever is cached and this updates the cache behind it. One
     * fetch at a time, at most hourly — Open-Meteo is free, and that should stay true.
     */
    fun refreshWeatherIfStale(context: Context) {
        val app = context.applicationContext
        val now = System.currentTimeMillis()
        if (fetchingWeather) return
        if (now - TodayState.weatherAt(app) < 60 * 60 * 1000L) return
        // A failed fetch writes nothing, so without this an offline phone would try again on
        // every to-do change.
        if (now - lastWeatherAttempt < 10 * 60 * 1000L) return
        lastWeatherAttempt = now
        fetchingWeather = true
        CarlsBrainApp.appScope.launch {
            try {
                val weather = com.carlmanning.carlsbrain.data.remote.WeatherRepository().getWeather()
                if (weather != null) {
                    TodayState.setWeatherLine(app, weatherLine(weather))
                    runCatching { updateAllTodayWidgets(app) }
                }
            } finally {
                fetchingWeather = false
            }
        }
    }

    /** "Dubbo 24° · Partly cloudy · 31° / 14°" — now, then today's high and low. */
    fun weatherLine(w: com.carlmanning.carlsbrain.data.remote.WeatherInfo): String =
        "Dubbo ${w.currentTemp}° · ${w.today.description} · ${w.today.maxTemp}° / ${w.today.minTemp}°"

    private var observing = false
    private var pending: Job? = null

    /**
     * Redraws whenever the to-do or calendar tables change, however they changed — an edit in
     * the app, a Drive pull, a voice capture, a tick on any surface.
     *
     * This is why the surfaces do not each need telling: there is one observer, on the tables
     * themselves, rather than a refresh call that every present and future writer has to
     * remember. It only works while the process is alive, which is exactly when writes
     * happen. Debounced, because a sync touches hundreds of rows in a burst.
     */
    @Synchronized
    fun observeChanges(context: Context) {
        if (observing) return
        observing = true
        val app = context.applicationContext
        val observer = object : InvalidationTracker.Observer("todos", "calendar_events", "buckets") {
            override fun onInvalidated(tables: Set<String>) {
                synchronized(this@TodaySurfaces) {
                    pending?.cancel()
                    pending = CarlsBrainApp.appScope.launch {
                        delay(600)
                        refresh(app)
                    }
                }
            }
        }
        AppDatabase.getInstance(app).invalidationTracker.addObserver(observer)
    }
}
