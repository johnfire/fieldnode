package de.christopherrehm.fieldnode

import android.os.Bundle
import android.text.format.DateUtils
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import de.christopherrehm.fieldnode.capture.Capture
import de.christopherrehm.fieldnode.capture.CaptureStoreFactory
import de.christopherrehm.fieldnode.dispatch.Dispatcher
import de.christopherrehm.fieldnode.dispatch.FleetClient
import de.christopherrehm.fieldnode.dispatch.FleetConfig

/**
 * v1a Captures screen: type a quick note into the queue, and see the offline queue itself — every
 * capture with its kind, age and status. Dispatch to the fleet arrives in v1b; for now everything
 * sits PENDING, which is the whole point of proving the queue holds it.
 */
class CapturesActivity : AppCompatActivity() {

    private val store = CaptureStoreFactory.create()
    private lateinit var noteInput: EditText
    private lateinit var captureList: ListView
    private lateinit var emptyLabel: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_captures)
        noteInput = findViewById(R.id.note_input)
        captureList = findViewById(R.id.capture_list)
        emptyLabel = findViewById(R.id.empty_label)

        findViewById<Button>(R.id.capture_note_button).setOnClickListener { captureNote() }
        findViewById<Button>(R.id.dispatch_button).setOnClickListener { dispatch() }
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun captureNote() {
        val text = noteInput.text.toString().trim()
        if (text.isEmpty()) return
        val id = System.currentTimeMillis().toString()
        store.save(Capture(id, Capture.Kind.TEXT, text, null, id.toLong(), Capture.Status.PENDING))
        noteInput.setText("")
        Toast.makeText(this, "Captured", Toast.LENGTH_SHORT).show()
        refresh()
    }

    /** Send unsent captures to the fleet on a background thread (network can't run on the UI thread). */
    private fun dispatch() {
        val config = FleetConfig.load()
        if (config == null) {
            Toast.makeText(this, "No fleet.config set yet", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "Dispatching…", Toast.LENGTH_SHORT).show()
        Thread {
            val outcome = Dispatcher(store, FleetClient(config)).dispatchUnsent()
            runOnUiThread {
                Toast.makeText(
                    this,
                    "Sent ${outcome.sent}, failed ${outcome.failed}",
                    Toast.LENGTH_LONG,
                ).show()
                refresh()
            }
        }.start()
    }

    private fun refresh() {
        val captures = store.list()
        emptyLabel.visibility = if (captures.isEmpty()) View.VISIBLE else View.GONE

        captureList.adapter = object : ArrayAdapter<Capture>(this, 0, captures) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val row = convertView
                    ?: layoutInflater.inflate(R.layout.row_capture, parent, false)
                val capture = getItem(position)!!
                row.findViewById<TextView>(R.id.capture_headline).text =
                    "[${capture.kind}] ${preview(capture)}"
                val age = DateUtils.getRelativeTimeSpanString(capture.createdAt)
                row.findViewById<TextView>(R.id.capture_meta).text = "${capture.status}  ·  $age"
                return row
            }
        }
    }

    private fun preview(capture: Capture): String = when (capture.kind) {
        Capture.Kind.IMAGE -> capture.attachment?.let { "image + \"${capture.text.take(40)}\"" } ?: "image"
        else -> capture.text.replace('\n', ' ').take(60)
    }
}
