package de.christopherrehm.fieldnode

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Environment
import androidx.core.content.FileProvider
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.snackbar.Snackbar
import de.christopherrehm.fieldnode.file.FileEngineFactory
import de.christopherrehm.fieldnode.file.FileOpResult
import java.io.File

/**
 * Step 0.6 debug UI: drive every file op by hand against real storage. Destructive ops pass through
 * a confirm dialog (the per-op gate deferred from 0.5) and offer one-tap Undo; the Trash and Log
 * views make the safety layer visible. Out-of-scope mutations are refused by the engine and surfaced
 * as a "blocked" toast, so the earned-scope boundary is observable, not theoretical.
 */
class FileBrowserActivity : AppCompatActivity() {

    private val engine = FileEngineFactory.create()
    private val storageRoot: File = Environment.getExternalStorageDirectory()
    private var currentDir: File = storageRoot

    private lateinit var pathLabel: TextView
    private lateinit var entryList: ListView
    private lateinit var rootView: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_file_browser)
        rootView = findViewById(R.id.browser_root)
        pathLabel = findViewById(R.id.path_label)
        entryList = findViewById(R.id.entry_list)

        findViewById<Button>(R.id.up_button).setOnClickListener { navigateUp() }
        findViewById<Button>(R.id.new_file_button).setOnClickListener { promptCreate(isDir = false) }
        findViewById<Button>(R.id.new_folder_button).setOnClickListener { promptCreate(isDir = true) }
        findViewById<Button>(R.id.trash_button).setOnClickListener { showTrash() }
        findViewById<Button>(R.id.log_button).setOnClickListener { showLog() }

        entryList.setOnItemClickListener { _, _, position, _ ->
            val entry = entryList.adapter.getItem(position) as File
            if (entry.isDirectory) navigateTo(entry) else showFileActions(entry)
        }
        refresh()
    }

    // --- navigation ------------------------------------------------------------------------------

    private fun navigateTo(dir: File) {
        currentDir = dir
        refresh()
    }

    private fun navigateUp() {
        val parent = currentDir.parentFile
        if (currentDir == storageRoot || parent == null) {
            toast("At storage root")
        } else {
            navigateTo(parent)
        }
    }

    private fun refresh() {
        val writable = engine.canMutate(currentDir)
        pathLabel.text = "${currentDir.absolutePath}\n${if (writable) "writable" else "read-only (out of scope)"}"

        val entries = engine.list(currentDir)
        entryList.adapter = object : ArrayAdapter<File>(this, 0, entries) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val row = convertView
                    ?: layoutInflater.inflate(R.layout.row_file_entry, parent, false)
                val item = getItem(position)!!
                row.findViewById<TextView>(R.id.entry_name).text =
                    (if (item.isDirectory) "[dir]  " else "       ") + item.name
                row.findViewById<TextView>(R.id.entry_meta).text =
                    if (item.isDirectory) "${engine.list(item).size} items" else "${item.length()} bytes"
                return row
            }
        }
    }

    // --- create ----------------------------------------------------------------------------------

    private fun promptCreate(isDir: Boolean) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            hint = if (isDir) "folder name" else "file name"
        }
        AlertDialog.Builder(this)
            .setTitle(if (isDir) "New folder" else "New file")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) return@setPositiveButton
                val target = File(currentDir, name)
                if (target.exists()) {
                    toast("Already exists")
                    return@setPositiveButton
                }
                val result = if (isDir) engine.createDir(target) else engine.writeText(target, "")
                report(result)
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // --- per-file actions ------------------------------------------------------------------------

    private fun showFileActions(file: File) {
        val actions = arrayOf("View", "Share", "Rename", "Delete")
        AlertDialog.Builder(this)
            .setTitle(file.name)
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> viewFile(file)
                    1 -> shareFile(file)
                    2 -> promptRename(file)
                    3 -> confirmDelete(file)
                }
            }
            .show()
    }

    /**
     * v2 share-out: hand the file to the system share sheet (Quick Share / Bluetooth / anything),
     * so it can go to someone else's device. The agent assembles; the OS does the last hop on a tap.
     */
    private fun shareFile(file: File) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = contentResolver.getType(uri) ?: "application/octet-stream"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, "Share ${file.name}"))
        } catch (error: Exception) {
            toast("Can't share: ${error.message}")
        }
    }

    private fun viewFile(file: File) {
        val content = engine.readText(file)
        val body = TextView(this).apply {
            text = content?.take(20_000) ?: "(not readable as text)"
            setPadding(48, 32, 48, 32)
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 12f
        }
        AlertDialog.Builder(this)
            .setTitle(file.name)
            .setView(ScrollView(this).apply { addView(body) })
            .setPositiveButton("Close", null)
            .show()
    }

    private fun promptRename(file: File) {
        val input = EditText(this).apply { setText(file.name) }
        AlertDialog.Builder(this)
            .setTitle("Rename")
            .setView(input)
            .setPositiveButton("Rename") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty() && newName != file.name) {
                    report(engine.rename(file, newName))
                    refresh()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** The confirm gate: no destructive op runs without this tap. */
    private fun confirmDelete(file: File) {
        AlertDialog.Builder(this)
            .setTitle("Move to trash?")
            .setMessage(file.absolutePath)
            .setPositiveButton("Delete") { _, _ -> deleteWithUndo(file) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteWithUndo(file: File) {
        val result = engine.delete(file)
        refresh()
        if (result is FileOpResult.Success) {
            val trashId = engine.trashEntries().lastOrNull()?.trashId
            Snackbar.make(rootView, "Moved to trash", Snackbar.LENGTH_LONG)
                .setAction("UNDO") {
                    if (trashId != null) {
                        report(engine.restore(trashId))
                        refresh()
                    }
                }
                .show()
        } else {
            report(result)
        }
    }

    // --- trash + log -----------------------------------------------------------------------------

    private fun showTrash() {
        val entries = engine.trashEntries()
        if (entries.isEmpty()) {
            toast("Trash is empty")
            return
        }
        val labels = entries.map { File(it.originalPath).name + "  ←  " + it.trashId }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Trash — tap to restore")
            .setItems(labels) { _, which ->
                report(engine.restore(entries[which].trashId))
                refresh()
            }
            .setPositiveButton("Close", null)
            .show()
    }

    private fun showLog() {
        val body = TextView(this).apply {
            text = engine.actionLog().ifEmpty { "(no actions logged yet)" }
            setPadding(32, 24, 32, 24)
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 11f
        }
        AlertDialog.Builder(this)
            .setTitle("Action log")
            .setView(ScrollView(this).apply { addView(body) })
            .setPositiveButton("Close", null)
            .show()
    }

    // --- helpers ---------------------------------------------------------------------------------

    private fun report(result: FileOpResult) {
        val text = when (result) {
            is FileOpResult.Success -> result.message
            is FileOpResult.Blocked -> "Blocked: ${result.reason}"
            is FileOpResult.Failure -> "Failed: ${result.reason}"
        }
        toast(text)
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
}
