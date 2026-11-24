package com.meda.portico.core.reg

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RegValuesTest {
    // A real-shaped FID: 22 base64url chars, first char c-f (RandomFidGenerator: 0111 high bits).
    private val fid = "cPYZbX0nQ0mJ9mVdK3x_-A"
    // A real-shaped FCM registration token: "<instance>:APA91b<...>", ~160 chars.
    private val token = "cPYZbX0nQ0mJ9mVdK3x_-A:APA91bH" + "x".repeat(140)

    @Test
    fun `onRegistered with an FID-shaped value is kind fid`() {
        assertEquals(RegValue(RegKind.FID, fid), RegValues.fromOnRegistered(fid))
    }

    @Test
    fun `onRegistered with a legacy token (Play services older than 26-12) is kind token`() {
        // firebase-messaging 25.1.3 in FID mode falls back to the legacy RPC on old Play services
        // and still delivers through onRegistered: the server must get kind "token" for it.
        assertEquals(RegValue(RegKind.TOKEN, token), RegValues.fromOnRegistered(token))
    }

    @Test
    fun `22 chars that cannot be an FID are a token`() {
        assertEquals(RegKind.TOKEN, RegValues.fromOnRegistered("APYZbX0nQ0mJ9mVdK3x_-A").kind) // first char A
        assertEquals(RegKind.TOKEN, RegValues.fromOnRegistered("cPYZbX0nQ0mJ9mVdK3x/+A").kind) // not url-safe
        assertEquals(RegKind.TOKEN, RegValues.fromOnRegistered(fid + "A").kind)
    }

    @Test
    fun `onNewToken is always kind token`() {
        assertEquals(RegValue(RegKind.TOKEN, fid), RegValues.fromOnNewToken(fid))
        assertEquals(RegValue(RegKind.TOKEN, token), RegValues.fromOnNewToken(token))
    }
}
