package com.meda.portico.core.scan

import com.meda.portico.core.enroll.Fixtures
import java.util.Base64

/** Scan fixtures. The secrets are fake, but shaped like the real ones, so a leak test can grep. */
internal object ScanFixtures {
    const val TOKEN = Fixtures.TOKEN
    const val ENROLLMENT_TODAY = Fixtures.ENROLLMENT_TODAY
    const val OLD_APP_TRANSFER_QR = Fixtures.OLD_APP_TRANSFER_QR
    const val OLD_APP_FCM_QR = Fixtures.OLD_APP_FCM_QR
    val GOOGLE_SERVICES = Fixtures.googleServicesOnly("com.meda.portico")

    /**
     * A long-lived HA access token as HA mints it: an HS256 JWT whose claims are
     * `{"iss":"<32 hex refresh-token id>","iat":…,"exp":…}` (iat + 10 years). That makes it
     * 36 + 1 + 102 + 1 + 43 = 183 characters.
     */
    val HA_LONG_LIVED_TOKEN: String = run {
        val b64 = Base64.getUrlEncoder().withoutPadding()
        val header = b64.encodeToString("""{"alg":"HS256","typ":"JWT"}""".toByteArray())
        val claims = b64.encodeToString("""{"iss":"5f0c6d1e9a2b4c7d8e1f2a3b4c5d6e7f","iat":1759737600,"exp":2075097600}""".toByteArray())
        val sig = b64.encodeToString(ByteArray(32) { (it * 37 + 11).toByte() })
        "$header.$claims.$sig"
    }

    const val API_KEY = "AIzaSyFAKE9kQ2mP7rT1wL5nB8cJ4hF6gS0aZyE" // 39 chars, the AIza… shape
    const val TS_KEY = "tskey-auth-FAKEFAKEFAK1CNTRL-a8Bc3De6Fg9Hj2Kl5Mn8Pq1Rs4Tu7VwXyZ" // ~62 chars

    /**
     * What `portico-ha` `enrollment_flow.py` will put in the QR once the server adds the `fcm`
     * block (spec §5.1) and the G4 marker: compact JSON (`separators=(",", ":")`), a Nabu Casa
     * remote URL, a 183-char token, a phone name, a Tailscale auth key and four Firebase values.
     */
    fun realisticPayload(phoneName: String = "Dana's Pixel 8 Pro"): String =
        """{"portico":1,"ha_url":"https://abcdefghijklmnopqrstuvwxyz012345.ui.nabu.casa",""" +
            """"ha_token":"$HA_LONG_LIVED_TOKEN","phone_name":"$phoneName","tailscale_authkey":"$TS_KEY",""" +
            """"fcm":{"v":1,"project_id":"portico-home-4f2a1","app_id":"1:812345678901:android:9c1f0e2d3b4a59687a6b5c",""" +
            """"api_key":"$API_KEY","sender_id":"812345678901"}}"""

    /** Python's `json.dumps` default (`ensure_ascii=True`): a Hebrew name becomes `\uXXXX` escapes, 6 bytes a letter. */
    fun hebrewNameEscaped(): String = "יאנה - טלפון פיקסל שמונה".map { c ->
        if (c.code < 0x80) c.toString() else "\\u%04x".format(c.code)
    }.joinToString("")
}
