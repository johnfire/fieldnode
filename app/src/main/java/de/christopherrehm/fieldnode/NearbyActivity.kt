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

/**
 * v2b on-site awareness: get the phone's location and ask the fleet "what leads are near me?"
 * (forwarder → engcrm recon). Tap a lead to open it in Maps. Manual/foreground only — no background
 * location — which keeps it simple and within Android 11's friendly permission tier.
 */
class NearbyActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var list: ListView
    private val leads = mutableListOf<Lead>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_nearby)
        status = findViewById(R.id.nearby_status)
        list = findViewById(R.id.lead_list)
        findViewById<Button>(R.id.find_button).setOnClickListener { findNearby() }
        list.setOnItemClickListener { _, _, position, _ -> openInMaps(leads[position]) }
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
        status.text = if (found.isEmpty()) "No leads nearby" else "${found.size} leads nearby — tap for Maps"
        list.adapter = object : ArrayAdapter<Lead>(this, 0, leads) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val row = convertView ?: layoutInflater.inflate(R.layout.row_lead, parent, false)
                val lead = getItem(position)!!
                row.findViewById<TextView>(R.id.lead_name).text = "${lead.name}  ·  ${lead.distanceM} m"
                row.findViewById<TextView>(R.id.lead_meta).text =
                    listOf(lead.type, lead.city, lead.phone).filter { it.isNotBlank() }.joinToString("  ·  ")
                return row
            }
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
    }
}
