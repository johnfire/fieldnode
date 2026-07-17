package de.christopherrehm.fieldnode.file

import android.os.Environment
import java.io.File

/**
 * On-device smoke test that exercises the safe engine against REAL storage with the REAL granted
 * permission, and proves the safety gate holds. Temporary verification surface for 0.4+0.5 — the
 * real debug UI (0.6) supersedes it. Every check is non-destructive to anything but its own scratch
 * files under `Fieldnode/selftest/`; the out-of-scope check targets a path it never touches.
 */
object FileEngineSelfTest {

    fun run(): String {
        val engine = FileEngineFactory.create(Actor.USER) // a human tapping the self-test button
        val external = Environment.getExternalStorageDirectory()
        val scratch = File(external, "${FileEngineFactory.MANAGED_DIR_NAME}/selftest")
        val checks = mutableListOf<String>()
        fun check(name: String, passed: Boolean, note: String = "") =
            checks.add("${if (passed) "PASS" else "FAIL"}  $name${if (note.isNotEmpty()) " — $note" else ""}")

        // 1. create a scratch dir within scope
        check("create dir in scope", engine.createDir(scratch).ok)

        // 2. write + read back
        val note = File(scratch, "note.txt")
        val wrote = engine.writeText(note, "hello fieldnode").ok
        check("write file in scope", wrote)
        check("read back content", engine.readText(note) == "hello fieldnode")

        // 3. delete -> goes to trash, original gone, recoverable
        val trashBefore = engine.trashEntries().size
        val deleted = engine.delete(note)
        check("delete -> trashed (not gone)", deleted.ok && !note.exists())
        check("trash entry recorded", engine.trashEntries().size == trashBefore + 1)

        // 4. restore brings it back with content intact
        val trashId = engine.trashEntries().lastOrNull()?.trashId
        val restored = trashId != null && engine.restore(trashId).ok
        check("restore from trash", restored && note.exists() && engine.readText(note) == "hello fieldnode")

        // 5. THE important one: a delete outside the allowlist must be BLOCKED, file untouched
        val offLimits = File(external, "DCIM/__fieldnode_must_never_touch_this__")
        val blocked = engine.delete(offLimits)
        check("out-of-scope delete BLOCKED", blocked is FileOpResult.Blocked)

        // cleanup: trash the scratch dir (itself recoverable)
        engine.delete(scratch)

        val passed = checks.count { it.startsWith("PASS") }
        return buildString {
            appendLine("File-engine self-test: $passed/${checks.size} passed")
            appendLine()
            checks.forEach { appendLine(it) }
            appendLine()
            appendLine("Writable scope:")
            appendLine(engine.writableScope())
        }
    }
}
