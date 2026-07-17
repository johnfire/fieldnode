package de.christopherrehm.fieldnode.dispatch

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FleetUrlPolicyTest {

    private val endpoint = "https://fieldnode.example.com/capture"

    @Test
    fun `same host over https is trusted even on a different path`() {
        assertTrue(FleetUrlPolicy.isTrustedFleetUrl("https://fieldnode.example.com/ack", endpoint))
    }

    @Test
    fun `host match is case-insensitive`() {
        assertTrue(FleetUrlPolicy.isTrustedFleetUrl("https://FIELDNODE.Example.com/ack", endpoint))
    }

    @Test
    fun `a different host is refused`() {
        assertFalse(FleetUrlPolicy.isTrustedFleetUrl("https://evil.example.com/steal", endpoint))
    }

    @Test
    fun `a look-alike subdomain is refused`() {
        assertFalse(FleetUrlPolicy.isTrustedFleetUrl("https://fieldnode.example.com.evil.test/x", endpoint))
    }

    @Test
    fun `http (cleartext) to the fleet host is refused`() {
        assertFalse(FleetUrlPolicy.isTrustedFleetUrl("http://fieldnode.example.com/ack", endpoint))
    }

    @Test
    fun `a malformed action url is refused`() {
        assertFalse(FleetUrlPolicy.isTrustedFleetUrl("not a url", endpoint))
    }

    @Test
    fun `a malformed endpoint refuses everything`() {
        assertFalse(FleetUrlPolicy.isTrustedFleetUrl("https://fieldnode.example.com/ack", ""))
    }
}
