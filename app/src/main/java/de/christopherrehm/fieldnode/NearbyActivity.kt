package de.christopherrehm.fieldnode

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import de.christopherrehm.fieldnode.dispatch.FleetConfig
import de.christopherrehm.fieldnode.nearby.Lead
import de.christopherrehm.fieldnode.nearby.NearbyClient
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

/**
 * v2b on-site awareness: get the phone's location and ask the fleet "what leads are near me?"
 * (forwarder → engcrm recon). Leads show on an interactive OpenStreetMap (pinch-zoom to building level,
 * drag to pan, tap a pin to navigate) and in a numbered list. "Route" hands the nearest stops to Google
 * Maps as a multi-stop driving route. Manual/foreground only — no background location.
 */
class NearbyActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var list: ListView
    private lateinit var mapView: MapView
    private lateinit var routeButton: Button
    private val leads = mutableListOf<Lead>()
    private var lastFix: Location? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // osmdroid needs a non-default User-Agent or OSM's tile servers reject the requests.
        Configuration.getInstance().load(applicationContext, getSharedPreferences("osmdroid", MODE_PRIVATE))
        Configuration.getInstance().userAgentValue = packageName

        setContentView(R.layout.activity_nearby)
        status = findViewById(R.id.nearby_status)
        list = findViewById(R.id.lead_list)
        routeButton = findViewById(R.id.route_button)
        mapView = findViewById(R.id.nearby_map)
        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.setMultiTouchControls(true)
        mapView.controller.setZoom(13.0)

        findViewById<Button>(R.id.find_button).setOnClickListener { findNearby() }
        routeButton.setOnClickListener { routeThese() }
        list.setOnItemClickListener { _, _, position, _ -> openInMaps(leads[position]) }
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
    }

    override fun onPause() {
        super.onPause()
        mapView.onPause()
    }

    private fun findNearby() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), REQUEST_LOCATION)
            return
        }
        status.text = "Locating…"
        val cached = lastKnownLocation()
        if (cached != null) queryNearby(cached) else requestSingleFix()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_LOCATION && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            findNearby()
        } else {
            status.text = "Location permission needed"
        }
    }

    private fun lastKnownLocation(): Location? {
        val manager = getSystemService(LocationManager::class.java) ?: return null
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        return providers.mapNotNull { provider ->
            try {
                manager.getLastKnownLocation(provider)
            } catch (error: SecurityException) {
                null
            }
        }.maxByOrNull { it.time }
    }

    private fun requestSingleFix() {
        val manager = getSystemService(LocationManager::class.java) ?: return
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                manager.removeUpdates(this)
                queryNearby(location)
            }
        }
        try {
            for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                if (manager.isProviderEnabled(provider)) {
                    manager.requestLocationUpdates(provider, 0L, 0f, listener, mainLooper)
                }
            }
            status.postDelayed({
                manager.removeUpdates(listener)
                if (leads.isEmpty()) status.text = "No location fix — enable GPS / open Maps once, then retry"
            }, 20_000)
        } catch (error: SecurityException) {
            status.text = "Location permission needed"
        }
    }

    private fun queryNearby(location: Location) {
        val config = FleetConfig.load()
        if (config == null) {
            status.text = "No fleet.config set"
            return
        }
        lastFix = location
        status.text = "Finding leads near ${"%.4f".format(location.latitude)}, ${"%.4f".format(location.longitude)}…"
        Thread {
            try {
                val found = NearbyClient(config).fetch(location.latitude, location.longitude)
                runOnUiThread { showLeads(found) }
            } catch (error: Exception) {
                runOnUiThread { status.text = "Error: ${error.message}" }
            }
        }.start()
    }

    private fun showLeads(found: List<Lead>) {
        leads.clear()
        leads.addAll(found)
        routeButton.isEnabled = found.any { it.hasCoords }
        status.text = if (found.isEmpty()) "No leads nearby" else "${found.size} leads nearby — tap a pin or row"
        list.adapter = object : ArrayAdapter<Lead>(this, 0, leads) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val row = convertView ?: layoutInflater.inflate(R.layout.row_lead, parent, false)
                val lead = getItem(position)!!
                row.findViewById<TextView>(R.id.lead_name).text =
                    "${position + 1}.  ${lead.name}  ·  ${lead.distanceM} m"
                row.findViewById<TextView>(R.id.lead_meta).text =
                    listOf(lead.type, lead.city, lead.phone).filter { it.isNotBlank() }.joinToString("  ·  ")
                return row
            }
        }
        showOnMap(found)
    }

    /** Plot the phone (blue-ish dot) + a numbered pin per lead, then fit the view to all of them.
     *  Pinch-zoom and drag are on; tapping a pin shows its name, tapping again navigates. */
    private fun showOnMap(found: List<Lead>) {
        mapView.overlays.clear()
        val points = mutableListOf<GeoPoint>()

        lastFix?.let {
            val here = GeoPoint(it.latitude, it.longitude)
            points.add(here)
            mapView.overlays.add(Marker(mapView).apply {
                position = here
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                title = "You are here"
            })
        }

        found.filter { it.hasCoords }.forEachIndexed { index, lead ->
            val point = GeoPoint(lead.lat, lead.lng)
            points.add(point)
            mapView.overlays.add(Marker(mapView).apply {
                position = point
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                title = "${index + 1}. ${lead.name}"
                subDescription = "${lead.distanceM} m · ${lead.type}"
                setOnMarkerClickListener { marker, _ ->
                    if (marker.isInfoWindowShown) openInMaps(lead) else marker.showInfoWindow()
                    true
                }
            })
        }
        mapView.invalidate()

        when {
            points.isEmpty() -> Unit
            points.size == 1 -> mapView.controller.apply { setZoom(16.0); setCenter(points.first()) }
            else -> mapView.post {
                mapView.zoomToBoundingBox(BoundingBox.fromGeoPoints(points).increaseByScale(1.3f), false, 64)
            }
        }
    }

    /** Hand the nearest leads to Google Maps as a multi-stop driving route (you → nearest → … →
     *  farthest). Maps gives the interactive zoom/drag + turn-by-turn we don't render in-app. */
    private fun routeThese() {
        val stops = leads.filter { it.hasCoords }.take(MAX_ROUTE_STOPS)
        if (stops.isEmpty()) {
            status.text = "No mappable leads to route"
            return
        }
        val url = buildString {
            append("https://www.google.com/maps/dir/?api=1&travelmode=driving")
            lastFix?.let { append("&origin=${it.latitude},${it.longitude}") }
            append("&destination=${stops.last().lat},${stops.last().lng}")
            val waypoints = stops.dropLast(1)
            if (waypoints.isNotEmpty()) {
                val joined = waypoints.joinToString("|") { "${it.lat},${it.lng}" }
                append("&waypoints=${Uri.encode(joined)}")
            }
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (error: Exception) {
            status.text = "Can't open maps: ${error.message}"
        }
    }

    private fun openInMaps(lead: Lead) {
        val uri = lead.mapsUri.ifBlank { "geo:0,0?q=${Uri.encode(lead.name)}" }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)))
        } catch (error: Exception) {
            status.text = "Can't open maps: ${error.message}"
        }
    }

    private companion object {
        const val REQUEST_LOCATION = 1
        const val MAX_ROUTE_STOPS = 9   // Google Maps' universal dir URL caps at ~9 waypoints
    }
}
