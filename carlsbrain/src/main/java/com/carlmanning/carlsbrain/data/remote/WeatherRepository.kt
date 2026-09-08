package com.carlmanning.carlsbrain.data.remote

import com.carlmanning.carlsbrain.CarlsBrainApp
import kotlinx.coroutines.Dispatchers
import okhttp3.Request
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class WeatherDay(
    val maxTemp: Int,
    val minTemp: Int,
    val weatherCode: Int,
    val description: String
)

data class WeatherInfo(
    val today: WeatherDay,
    val tomorrow: WeatherDay,
    val currentTemp: Int
)

class WeatherRepository {

    /**
     * Today's and tomorrow's weather for Dubbo, or null when it cannot be fetched.
     *
     * On the app's shared OkHttp client rather than a raw HttpURLConnection. The old shape sat
     * outside the whole HTTP configuration — no shared connection pool, none of the app's
     * timeouts — and its reader was never closed on the exception path, so a malformed response
     * leaked the connection.
     *
     * Its own short timeouts, because this is decoration on the Dashboard: a slow weather
     * service must not hold the briefing up.
     */
    suspend fun getWeather(): WeatherInfo? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(
                "https://api.open-meteo.com/v1/forecast" +
                    "?latitude=-32.2571&longitude=148.6016" +
                    "&daily=weather_code,temperature_2m_max,temperature_2m_min" +
                    "&current=temperature_2m&forecast_days=2&timezone=auto"
            )
            .build()
        runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body?.string() ?: return@use null
                parse(JSONObject(body))
            }
        }.getOrNull()
    }

    private val client = CarlsBrainApp.httpClient.newBuilder()
        .callTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
        .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private fun parse(json: JSONObject): WeatherInfo? {
        val daily = json.optJSONObject("daily") ?: return null
        val codes = daily.optJSONArray("weather_code") ?: return null
        val maxTemps = daily.optJSONArray("temperature_2m_max") ?: return null
        val minTemps = daily.optJSONArray("temperature_2m_min") ?: return null
        val currentTemp = json.optJSONObject("current")
            ?.optDouble("temperature_2m")?.toInt() ?: 0

        fun day(i: Int): WeatherDay {
            val code = codes.optInt(i)
            return WeatherDay(
                maxTemp = maxTemps.optDouble(i).toInt(),
                minTemp = minTemps.optDouble(i).toInt(),
                weatherCode = code,
                description = describe(code)
            )
        }
        return WeatherInfo(today = day(0), tomorrow = day(1), currentTemp = currentTemp)
    }

    private fun describe(code: Int): String = when (code) {
        0 -> "Clear sky"
        1, 2, 3 -> "Partly cloudy"
        45, 48 -> "Foggy"
        51, 53, 55 -> "Drizzle"
        61, 63, 65 -> "Rain"
        71, 73, 75 -> "Snow"
        80, 81, 82 -> "Showers"
        95 -> "Thunderstorm"
        96, 99 -> "Thunderstorm with hail"
        else -> "Cloudy"
    }
}
