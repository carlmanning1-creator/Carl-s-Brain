package com.carlmanning.carlsbrain.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

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

    suspend fun getWeather(): WeatherInfo? = withContext(Dispatchers.IO) {
        try {
            val url = URL(
                "https://api.open-meteo.com/v1/forecast" +
                "?latitude=-32.2571&longitude=148.6016" +
                "&daily=weather_code,temperature_2m_max,temperature_2m_min" +
                "&current=temperature_2m&forecast_days=2&timezone=auto"
            )
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 5_000
            conn.readTimeout = 5_000
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            conn.disconnect()
            parse(json)
        } catch (e: Exception) {
            null
        }
    }

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
