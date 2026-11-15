package org.simpmusic.loginsync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Rebrand wire compatibility (2026-11-15): endpoints seal in the CURRENT (duomusic) dialect and
 * open both. These tests pin the two directions an old↔new pair needs — a legacy-sealed body
 * still opens (and is reported LEGACY, so a server can echo it in the reply) and a current-sealed
 * body reports CURRENT — plus that QR invites parse under both prefixes and the minted URI uses
 * the rebranded one.
 */
class WireDialectTest {
    @Test
    fun `login sync opens both dialects and reports which`() {
        val key = LoginSyncCipher.newKey()
        val plain = "cookie".encodeToByteArray()

        val current =
            LoginSyncCipher.seal(key, LoginSyncWire.aad(LoginSyncWire.Dialect.CURRENT, LoginSyncWire.PAYLOAD, reply = false), plain)
        val openedCurrent = LoginSyncWire.openSealed(key, LoginSyncWire.PAYLOAD, reply = false, sealed = current)
        assertNotNull(openedCurrent)
        assertEquals("cookie", openedCurrent.first.decodeToString())
        assertEquals(LoginSyncWire.Dialect.CURRENT, openedCurrent.second)

        val legacy =
            LoginSyncCipher.seal(key, LoginSyncWire.aad(LoginSyncWire.Dialect.LEGACY, LoginSyncWire.PAYLOAD, reply = false), plain)
        val openedLegacy = LoginSyncWire.openSealed(key, LoginSyncWire.PAYLOAD, reply = false, sealed = legacy)
        assertNotNull(openedLegacy)
        assertEquals("cookie", openedLegacy.first.decodeToString())
        assertEquals(LoginSyncWire.Dialect.LEGACY, openedLegacy.second)

        assertNull(LoginSyncWire.openSealed(LoginSyncCipher.newKey(), LoginSyncWire.PAYLOAD, reply = false, sealed = current))
    }

    @Test
    fun `paired sync opens both dialects and reports which`() {
        val key = LoginSyncCipher.newKey()
        val plain = "plays".encodeToByteArray()

        val current =
            LoginSyncCipher.seal(key, PairedSyncWire.aad(PairedSyncWire.Dialect.CURRENT, PairedSyncWire.SYNC, reply = false, phoneId = "phone-a"), plain)
        val openedCurrent = PairedSyncWire.openSealed(key, PairedSyncWire.SYNC, reply = false, phoneId = "phone-a", sealed = current)
        assertNotNull(openedCurrent)
        assertEquals("plays", openedCurrent.first.decodeToString())
        assertEquals(PairedSyncWire.Dialect.CURRENT, openedCurrent.second)

        val legacy =
            LoginSyncCipher.seal(key, PairedSyncWire.aad(PairedSyncWire.Dialect.LEGACY, PairedSyncWire.SYNC, reply = false, phoneId = "phone-a"), plain)
        val openedLegacy = PairedSyncWire.openSealed(key, PairedSyncWire.SYNC, reply = false, phoneId = "phone-a", sealed = legacy)
        assertNotNull(openedLegacy)
        assertEquals("plays", openedLegacy.first.decodeToString())
        assertEquals(PairedSyncWire.Dialect.LEGACY, openedLegacy.second)
    }

    /** Pins the rebrand itself: current AAD says duomusic, legacy says simpmusic. */
    @Test
    fun `aad strings carry their brand`() {
        assertTrue(
            LoginSyncWire.aad(LoginSyncWire.Dialect.CURRENT, LoginSyncWire.PAYLOAD, reply = false).decodeToString().startsWith("duomusic-login-sync/v1/"),
        )
        assertTrue(
            LoginSyncWire.aad(LoginSyncWire.Dialect.LEGACY, LoginSyncWire.PAYLOAD, reply = false).decodeToString().startsWith("simpmusic-login-sync/v1/"),
        )
        assertTrue(
            PairedSyncWire.aad(PairedSyncWire.Dialect.CURRENT, PairedSyncWire.SYNC, reply = false, phoneId = "p").decodeToString().startsWith("duomusic-library-sync/v1/"),
        )
        assertTrue(
            PairedSyncWire.aad(PairedSyncWire.Dialect.LEGACY, PairedSyncWire.SYNC, reply = false, phoneId = "p").decodeToString().startsWith("simpmusic-library-sync/v1/"),
        )
    }

    @Test
    fun `login invite parses under both prefixes`() {
        val invite = LoginSyncInvite(listOf("1.2.3.4", "5.6.7.8"), 48517, ByteArray(LoginSyncWire.KEY_BYTES) { it.toByte() })
        val uri = invite.toUri()
        assertTrue(uri.startsWith("duomusic://login-sync?"), uri)

        val parsedCurrent = LoginSyncInvite.parse(uri)
        assertNotNull(parsedCurrent)
        assertEquals(invite.hosts, parsedCurrent.hosts)
        assertEquals(invite.port, parsedCurrent.port)

        val parsedLegacy = LoginSyncInvite.parse(uri.replaceFirst("duomusic://", "simpmusic://"))
        assertNotNull(parsedLegacy)
        assertEquals(invite.hosts, parsedLegacy.hosts)
        assertEquals(invite.port, parsedLegacy.port)

        assertNull(LoginSyncInvite.parse("https://example.com/whatever?h=1.2.3.4&p=1&k=AAAA"))
    }

    @Test
    fun `pair invite parses under both prefixes`() {
        val invite = SyncPairInvite(listOf("1.2.3.4"), 48517, ByteArray(LoginSyncWire.KEY_BYTES) { it.toByte() })
        val uri = invite.toUri()
        assertTrue(uri.startsWith("duomusic://sync-pair?"), uri)

        val parsedCurrent = SyncPairInvite.parse(uri)
        assertNotNull(parsedCurrent)
        assertEquals(invite.hosts, parsedCurrent.hosts)

        val parsedLegacy = SyncPairInvite.parse(uri.replaceFirst("duomusic://", "simpmusic://"))
        assertNotNull(parsedLegacy)
        assertEquals(invite.hosts, parsedLegacy.hosts)

        assertNull(SyncPairInvite.parse("duomusic://login-sync?h=1.2.3.4&p=48517&k=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"))
    }
}
