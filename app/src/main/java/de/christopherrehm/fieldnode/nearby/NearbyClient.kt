package de.christopherrehm.fieldnode.nearby

import de.christopherrehm.fieldnode.dispatch.FleetConfig
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import org.json.JSONObject

/** A lead near the phone, as returned by the forwarder's /nearby (which proxies engcrm recon). */
data class Lead(
    val name: String,
    val type: String,
    val city: String,
    val distanceM: Int,
    val phone: String,
    val mapsUri: String,
    val lat: Double,
    val lng: Double,
) {
    val hasCoords: Boolean get() = lat.isFinite() && lng.isFinite()
}

/** Asks the forwarder "what leads are near (lat,lng)?" with the device token. Blocking. */
class NearbyClient(private val config: FleetConfig) {

    fun fetch(lat: Double, lng: Double, limit: Int = 15): List<Lead> {
        val connection = (URL("${config.baseUrl}/nearby?lat=$lat&lng=$lng&limit=$limit").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 20_000
            setRequestProperty("X-Device-Token", config.token)
            // Each lookup is its own unit of work (coding-standards 7.5) — mint a fresh id per call.
            setRequestProperty("X-Correlation-Id", UUID.randomUUID().toString())
        }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        connection.disconnect()
        if (code !in 200..299) throw RuntimeException("HTTP $code: ${text.take(140)}")

        val leads = JSONObject(text).optJSONArray("leads") ?: return emptyList()
        return (0 until leads.length()).map { index ->
            val entry = leads.getJSONObject(index)
            Lead(
                name = entry.optString("name"),
                type = entry.optString("type"),
                city = entry.optString("city"),
                distanceM = entry.optInt("distance_m"),
                phone = entry.optString("phone"),
                mapsUri = entry.optString("maps_uri"),
                lat = entry.optDouble("lat", Double.NaN),
                lng = entry.optDouble("lng", Double.NaN),
            )
        }
    }
}
