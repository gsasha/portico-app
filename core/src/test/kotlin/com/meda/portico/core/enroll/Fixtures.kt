package com.meda.portico.core.enroll

/** Shared fixture strings. The "secret" values are distinctive so a leak test can grep for them. */
internal object Fixtures {
    const val TOKEN = "eyJSECRET.TOKEN.value-7f3a9c"
    const val TS_KEY = "${"tskey"}-auth-SECRETKEY-123456"
    const val API_KEY = "${"AIza"}SyFAKEICOapiKEY0123456789abcdefgh"
    const val OTHER_API_KEY = "${"AIza"}SyFAKERapiKEY00000000000000000000"
    const val PROJECT_ID = "portico-test"
    const val SENDER_ID = "123456789012"

    /** A distinctive enroll_id (43 chars of `[A-Za-z0-9_-]`, like `secrets.token_urlsafe(32)`), grepped for by the leak tests. */
    const val ENROLL_ID = "ENROLLidFixture_0123456789-abcdefghijKLMNOP"

    /** Today's payload plus the HA side's new `enroll_id` field. */
    const val ENROLLMENT_WITH_ENROLL_ID =
        """{"ha_url":"https://ha.example.net","ha_token":"$TOKEN","phone_name":"Pixel","enroll_id":"$ENROLL_ID"}"""
    const val APP_ID = "1:123456789012:android:bbbb2222cccc3333"

    /** Exactly what `enrollment_flow.py` emits today: compact JSON, no marker, no fcm. */
    const val ENROLLMENT_TODAY =
        """{"ha_url":"https://ha.example.net:8123","ha_token":"$TOKEN","phone_name":"Bob's Pixel","tailscale_authkey":"$TS_KEY"}"""

    /** `tailscale_mode: manual`: the key is absent from the payload. */
    const val ENROLLMENT_MANUAL_TS =
        """{"ha_url":"http://homeassistant.local:8123","ha_token":"$TOKEN","phone_name":"Test phone"}"""

    /** Spec §5.1: the optional fcm block, as the server will emit it in P3, plus the G4 marker. */
    const val ENROLLMENT_WITH_FCM = """
        {"portico":1,"ha_url":"https://ha.example.net","ha_token":"$TOKEN","phone_name":"Pixel",
         "tailscale_authkey":"$TS_KEY",
         "fcm":{"v":1,"project_id":"$PROJECT_ID","app_id":"$APP_ID","api_key":"$API_KEY","sender_id":"$SENDER_ID"}}
    """

    /** A non-JSON key=value QR text of a retired app (it is refused as not JSON, never quoted back). */
    const val OLD_APP_TRANSFER_QR = "legacy-transfer/1\napp=0.51.1-alpha\nha=https%3A%2F%2Fha\ntok=$TOKEN\n"

    /** The same retired app's FCM settings QR (also non-JSON key=value text). */
    const val OLD_APP_FCM_QR = "legacy-transfer-fcm/1\nfak=$API_KEY\nfai=$APP_ID\nfpi=$PROJECT_ID\nfsi=$SENDER_ID\n"

    /** A google-services.json with two Android clients: the old app's first, Portico's second. */
    val GOOGLE_SERVICES_TWO_CLIENTS = """
        {
          "project_info": {
            "project_number": "$SENDER_ID",
            "project_id": "$PROJECT_ID",
            "storage_bucket": "$PROJECT_ID.appspot.com"
          },
          "client": [
            {
              "client_info": {
                "mobilesdk_app_id": "1:$SENDER_ID:android:aaaa1111dddd4444",
                "android_client_info": { "package_name": "com.example.otherapp" }
              },
              "oauth_client": [],
              "api_key": [ { "current_key": "$OTHER_API_KEY" } ],
              "services": { "appinvite_service": { "other_platform_oauth_client": [] } }
            },
            {
              "client_info": {
                "mobilesdk_app_id": "$APP_ID",
                "android_client_info": { "package_name": "com.meda.portico" }
              },
              "oauth_client": [],
              "api_key": [ { "current_key": "$API_KEY" } ],
              "services": { "appinvite_service": { "other_platform_oauth_client": [] } }
            }
          ],
          "configuration_version": "1"
        }
    """.trimIndent()

    fun googleServicesOnly(pkg: String, appId: String = APP_ID, apiKey: String = API_KEY) = """
        {"project_info":{"project_number":"$SENDER_ID","project_id":"$PROJECT_ID"},
         "client":[{"client_info":{"mobilesdk_app_id":"$appId","android_client_info":{"package_name":"$pkg"}},
                    "api_key":[{"current_key":"$apiKey"}]}]}
    """.trimIndent()
}
