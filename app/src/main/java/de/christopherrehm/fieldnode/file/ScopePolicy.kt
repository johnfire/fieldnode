package de.christopherrehm.fieldnode.file

import java.io.File

/**
 * Decides which paths the agent may MUTATE. Reads are unrestricted (all-files access is granted);
 * create / write / move / delete are confined to an allowlist of roots that starts narrow and is
 * widened only as trust grows ("earned scope"). First line of the safety gate.
 *
 * Paths are compared canonically, so `allowedRoot/../somewhere-else` cannot escape the allowlist.
 */
class ScopePolicy(allowedRoots: List<File>) {

    private val rootPaths: List<String> = allowedRoots.map(::canonical)

    /** True if [target] sits at or below one of the writable roots. */
    fun canMutate(target: File): Boolean {
        val targetPath = canonical(target)
        return rootPaths.any { root ->
            targetPath == root || targetPath.startsWith(root + File.separator)
        }
    }

    fun describe(): String =
        if (rootPaths.isEmpty()) "(no writable roots)" else rootPaths.joinToString("\n")

    private companion object {
        /** Resolve `..`/symlinks; fall back to absolute path if the filesystem can't canonicalize. */
        fun canonical(file: File): String =
            try {
                file.canonicalPath
            } catch (error: Exception) {
                file.absoluteFile.normalize().path
            }
    }
}
