package de.christopherrehm.fieldnode.file

/**
 * Who performed a logged action (coding-standards 7.6): a human via the debug UI, or the on-phone AI
 * agent acting through its tools. Every [ActionLog] entry must name one of these explicitly — the
 * standard is explicit that an AI action must never be silently attributed to "system" or omitted.
 */
object Actor {
    const val USER = "user"
    const val AI_AGENT = "ai-agent:fieldnode"
}
