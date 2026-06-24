package de.christopherrehm.fieldnode.file

/**
 * Outcome of a file operation. Expected failures never throw (anti-fragility): a refused or failed
 * operation returns a value the caller can surface, so one bad op can't unwind the whole flow.
 *
 *  - [Success]  the operation happened.
 *  - [Blocked]  the safety gate refused it (out of writable scope) — nothing on disk changed.
 *  - [Failure]  it was attempted within scope but failed (missing file, IO error).
 */
sealed interface FileOpResult {
    data class Success(val message: String) : FileOpResult
    data class Blocked(val reason: String) : FileOpResult
    data class Failure(val reason: String) : FileOpResult

    val ok: Boolean get() = this is Success
}
