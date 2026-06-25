package de.christopherrehm.fieldnode.session

/** Derives a session title from the first user message — a flattened, length-capped snippet. */
object SessionTitle {
    const val MAX = 40

    fun fromMessage(text: String): String {
        val flat = text.replace(Regex("\\s+"), " ").trim()
        if (flat.isEmpty()) return "New session"
        return if (flat.length <= MAX) flat else flat.take(MAX).trimEnd() + "…"
    }
}
