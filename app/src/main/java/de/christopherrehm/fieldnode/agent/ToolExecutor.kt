package de.christopherrehm.fieldnode.agent

import android.content.Context
import android.location.Location
import android.location.LocationManager
import de.christopherrehm.fieldnode.capture.Capture
import de.christopherrehm.fieldnode.capture.CaptureStoreFactory
import de.christopherrehm.fieldnode.dispatch.FleetConfig
import de.christopherrehm.fieldnode.file.Actor
import de.christopherrehm.fieldnode.file.FileEngineFactory
import de.christopherrehm.fieldnode.file.FileOpResult
import de.christopherrehm.fieldnode.nearby.NearbyClient
import java.io.File
import org.json.JSONObject

/**
 * Executes the agent's tool calls on the phone. File mutations go through the SAME caged FileEngine
 * (scope-gated, trash-not-rm, logged) — so the on-device agent inherits the safety layer built in v0.
 * The engine is constructed with [Actor.AI_AGENT], so every entry it writes — file mutation or, via
 * [de.christopherrehm.fieldnode.file.FileEngine.recordAction], a non-file tool call — is attributed to
 * the agent, never silently to "system" (coding-standards 7.6).
 */
class ToolExecutor(private val context: Context) : ToolRunner {

    private val engine = FileEngineFactory.create(Actor.AI_AGENT)
    private val captures = CaptureStoreFactory.create()

    override fun run(name: String, args: JSONObject): String = try {
        when (name) {
            "writable_scope" -> engine.writableScope()
            "list_files" -> engine.list(File(args.getString("path")))
                .joinToString("\n") { (if (it.isDirectory) "[dir] " else "      ") + it.name }
                .ifBlank { "(empty)" }
            "read_file" -> engine.readText(File(args.getString("path")))?.take(4000) ?: "(not a readable text file)"
            "write_file" -> describe(engine.writeText(File(args.getString("path")), args.optString("content")))
            "create_dir" -> describe(engine.createDir(File(args.getString("path"))))
            "move_file" -> describe(engine.move(File(args.getString("source")), File(args.getString("destination"))))
            "delete_file" -> describe(engine.delete(File(args.getString("path"))))
            "capture_note" -> captureNote(args.getString("text"))
            "find_nearby_leads" -> findNearby()
            else -> "unknown tool: $name"
        }
    } catch (error: Exception) {
        "error: ${error.message}"
    }

    private fun describe(result: FileOpResult): String = when (result) {
        is FileOpResult.Success -> "ok — ${result.message}"
        is FileOpResult.Blocked -> "blocked — ${result.reason}"
        is FileOpResult.Failure -> "failed — ${result.reason}"
    }

    private fun captureNote(text: String): String {
        val id = System.currentTimeMillis().toString()
        captures.save(Capture(id, Capture.Kind.TEXT, text, null, id.toLong(), Capture.Status.PENDING))
        engine.recordAction("capture_note", id, "OK", text.take(80))
        return "captured (pending dispatch)"
    }

    private fun findNearby(): String {
        val config = FleetConfig.load() ?: return notFound("no fleet.config set")
        val location = lastKnownLocation() ?: return notFound("no location fix available")
        val leads = NearbyClient(config).fetch(location.latitude, location.longitude, limit = 8)
        engine.recordAction("find_nearby_leads", "${location.latitude},${location.longitude}", "OK", "${leads.size} leads")
        if (leads.isEmpty()) return "no leads nearby"
        return leads.joinToString("\n") { "${it.name} — ${it.distanceM} m (${it.type}, ${it.city})" }
    }

    private fun notFound(reason: String): String {
        engine.recordAction("find_nearby_leads", "-", "FAILURE", reason)
        return reason
    }

    private fun lastKnownLocation(): Location? {
        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        return listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { provider -> try { manager.getLastKnownLocation(provider) } catch (error: SecurityException) { null } }
            .maxByOrNull { it.time }
    }
}
