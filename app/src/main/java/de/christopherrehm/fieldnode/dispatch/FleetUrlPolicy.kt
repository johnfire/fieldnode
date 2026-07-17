package de.christopherrehm.fieldnode.dispatch

import java.net.URL

/**
 * Decides whether a notification-action URL is allowed to receive the device token and be called.
 *
 * Notification actions arrive over the fleet message channel (ntfy) — i.e. they are external data,
 * not app-authored. Without this gate, a spoofed message (or a leaked ntfy topic token) could hand
 * [de.christopherrehm.fieldnode.ActionReceiver] a URL pointing anywhere, and the receiver would POST
 * the device token straight to it. So an action may target ONLY our own fleet host, and only over
 * https — anything else is refused outright, so the token can't be exfiltrated and the phone can't be
 * used to fire requests at arbitrary hosts.
 *
 * Pure (java.net.URL only) so it's unit-testable on the JVM.
 */
object FleetUrlPolicy {

    /** True only if [actionUrl] is https and shares a host with the configured [fleetEndpoint]. */
    fun isTrustedFleetUrl(actionUrl: String, fleetEndpoint: String): Boolean {
        val target = runCatching { URL(actionUrl) }.getOrNull() ?: return false
        val fleet = runCatching { URL(fleetEndpoint) }.getOrNull() ?: return false
        val isHttps = target.protocol.equals("https", ignoreCase = true)
        return isHttps && target.host.isNotBlank() && target.host.equals(fleet.host, ignoreCase = true)
    }
}
