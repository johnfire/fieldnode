package de.christopherrehm.fieldnode

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import de.christopherrehm.fieldnode.file.FileEngineSelfTest
import java.io.File

/**
 * v0 entry point. Shows liveness + all-files-access state (0.3), the file engine self-test and the
 * file browser (0.4–0.6), and start/stop + heartbeat readout for the MIUI survival canary (0.7).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var grantButton: Button
    private lateinit var capturesButton: Button
    private lateinit var browserButton: Button
    private lateinit var selfTestButton: Button
    private lateinit var selfTestOutput: TextView
    private lateinit var canaryStartButton: Button
    private lateinit var canaryStopButton: Button
    private lateinit var canaryRefreshButton: Button
    private lateinit var canaryStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        grantButton = findViewById(R.id.grant_button)
        capturesButton = findViewById(R.id.captures_button)
        browserButton = findViewById(R.id.browser_button)
        selfTestButton = findViewById(R.id.selftest_button)
        selfTestOutput = findViewById(R.id.selftest_output)
        canaryStartButton = findViewById(R.id.canary_start_button)
        canaryStopButton = findViewById(R.id.canary_stop_button)
        canaryRefreshButton = findViewById(R.id.canary_refresh_button)
        canaryStatus = findViewById(R.id.canary_status)

        val canaryIntent = Intent(this, SurvivalCanaryService::class.java)
        grantButton.setOnClickListener { openAllFilesAccessSettings() }
        capturesButton.setOnClickListener { startActivity(Intent(this, CapturesActivity::class.java)) }
        browserButton.setOnClickListener { startActivity(Intent(this, FileBrowserActivity::class.java)) }
        selfTestButton.setOnClickListener { selfTestOutput.text = FileEngineSelfTest.run() }
        canaryStartButton.setOnClickListener {
            startForegroundService(canaryIntent)
            renderCanary()
        }
        canaryStopButton.setOnClickListener {
            stopService(canaryIntent)
            renderCanary()
        }
        canaryRefreshButton.setOnClickListener { renderCanary() }
    }

    override fun onResume() {
        super.onResume()
        render()
        renderCanary()
    }

    private fun render() {
        val granted = Environment.isExternalStorageManager()

        status.text = buildString {
            appendLine("Fieldnode v0 — alive")
            appendLine()
            appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine()
            append("All-files access: ")
            append(if (granted) "GRANTED" else "not yet granted")
        }

        val visibility = if (granted) View.VISIBLE else View.GONE
        grantButton.visibility = if (granted) View.GONE else View.VISIBLE
        capturesButton.visibility = visibility
        browserButton.visibility = visibility
        selfTestButton.visibility = visibility
        canaryStartButton.visibility = visibility
        canaryStopButton.visibility = visibility
        canaryRefreshButton.visibility = visibility
    }

    /** Show the tail of the heartbeat log so survival is visible without leaving the app. */
    private fun renderCanary() {
        val log = File(Environment.getExternalStorageDirectory(), "Fieldnode/.canary/heartbeat.log")
        canaryStatus.text = if (!log.exists()) {
            "Canary: never started"
        } else {
            val tail = log.readLines().filter { it.isNotBlank() }.takeLast(6)
            "Canary heartbeat (last ${tail.size}):\n" + tail.joinToString("\n")
        }
    }

    private fun openAllFilesAccessSettings() {
        // Prefer the per-app screen (lands directly on Fieldnode's toggle); fall back to the
        // global "All files access" list if an OEM doesn't expose the per-app intent.
        val perApp = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
            data = Uri.fromParts("package", packageName, null)
        }
        startActivity(
            if (perApp.resolveActivity(packageManager) != null) {
                perApp
            } else {
                Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
            },
        )
    }
}
