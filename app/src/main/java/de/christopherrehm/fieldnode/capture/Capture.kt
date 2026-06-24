package de.christopherrehm.fieldnode.capture

/**
 * One captured item — a thought, a shared snippet, a photo — waiting to go to the fleet. The offline
 * queue is simply the set of captures in [Status.PENDING]: capture writes locally first and never
 * blocks on the network, so nothing is lost when there's no signal.
 */
data class Capture(
    val id: String,
    val kind: Kind,
    val text: String,
    val attachment: String?, // filename within the captures dir, or null
    val createdAt: Long,
    val status: Status,
) {
    enum class Kind { TEXT, IMAGE, VOICE }
    enum class Status { PENDING, SENT, FAILED }
}
