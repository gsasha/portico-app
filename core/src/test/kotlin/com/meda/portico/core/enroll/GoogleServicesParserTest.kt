package com.meda.portico.core.enroll

import com.meda.portico.core.enroll.Fixtures.API_KEY
import com.meda.portico.core.enroll.Fixtures.APP_ID
import com.meda.portico.core.enroll.Fixtures.PROJECT_ID
import com.meda.portico.core.enroll.Fixtures.SENDER_ID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * Mirrors `portico-ha` `push/credentials.py` `parse_google_services` (codes `invalid_json`,
 * `missing_fields`, `package_not_found`), then the app's own value validation on top.
 */
class GoogleServicesParserTest {

    private fun ok(text: String): FirebaseConfig = when (val r = GoogleServicesParser.extract(text)) {
        is GoogleServicesResult.Ok -> r.config
        is GoogleServicesResult.Error -> fail("expected Ok, got ${r.code}")
    }

    private fun code(text: String): GoogleServicesError = when (val r = GoogleServicesParser.extract(text)) {
        is GoogleServicesResult.Ok -> fail("expected an error, got Ok")
        is GoogleServicesResult.Error -> r.code
    }

    @Test
    fun picksPorticosClientOutOfSeveral() {
        val c = ok(Fixtures.GOOGLE_SERVICES_TWO_CLIENTS)
        assertEquals(PROJECT_ID, c.projectId)
        assertEquals(SENDER_ID, c.senderId)
        assertEquals(APP_ID, c.appId)
        assertEquals(API_KEY, c.apiKey)
    }

    @Test
    fun packageIsComDotMedaDotPortico() {
        assertEquals("com.meda.portico", GoogleServicesParser.PORTICO_PACKAGE)
    }

    @Test
    fun packageNotFound() {
        assertEquals(GoogleServicesError.PACKAGE_NOT_FOUND, code(Fixtures.googleServicesOnly("com.example.otherapp")))
        assertEquals(GoogleServicesError.PACKAGE_NOT_FOUND,
            code("""{"project_info":{"project_id":"$PROJECT_ID","project_number":"$SENDER_ID"}}"""))
        assertEquals(GoogleServicesError.PACKAGE_NOT_FOUND,
            code("""{"project_info":{"project_id":"$PROJECT_ID","project_number":"$SENDER_ID"},"client":[]}"""))
        assertEquals(GoogleServicesError.PACKAGE_NOT_FOUND,
            code("""{"project_info":{"project_id":"$PROJECT_ID","project_number":"$SENDER_ID"},"client":null}"""))
    }

    @Test
    fun anotherPackageCanBeAskedFor() {
        val r = GoogleServicesParser.extract(Fixtures.GOOGLE_SERVICES_TWO_CLIENTS, "com.example.otherapp")
        assertEquals("1:$SENDER_ID:android:aaaa1111dddd4444", (r as GoogleServicesResult.Ok).config.appId)
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "not json", "[1]", "\"s\"", "{", "null"])
    fun invalidJson(text: String) {
        assertEquals(GoogleServicesError.INVALID_JSON, code(text))
    }

    @ParameterizedTest
    @ValueSource(strings = [
        // project_info missing, not an object, or without a truthy project_id / project_number
        """{"client":[]}""",
        """{"project_info":"x","client":[]}""",
        """{"project_info":{"project_number":"1"},"client":[]}""",
        """{"project_info":{"project_id":"p","project_number":""},"client":[]}""",
        """{"project_info":{"project_id":"","project_number":"1"},"client":[]}""",
        """{"project_info":{"project_id":"p","project_number":0},"client":[]}""",
        // the Portico client is missing mobilesdk_app_id, api_key, or api_key is empty
        """{"project_info":{"project_id":"p","project_number":"1"},"client":[{"client_info":{"android_client_info":{"package_name":"com.meda.portico"}},"api_key":[{"current_key":"k"}]}]}""",
        """{"project_info":{"project_id":"p","project_number":"1"},"client":[{"client_info":{"mobilesdk_app_id":"a","android_client_info":{"package_name":"com.meda.portico"}}}]}""",
        """{"project_info":{"project_id":"p","project_number":"1"},"client":[{"client_info":{"mobilesdk_app_id":"a","android_client_info":{"package_name":"com.meda.portico"}},"api_key":[]}]}""",
        """{"project_info":{"project_id":"p","project_number":"1"},"client":[{"client_info":{"mobilesdk_app_id":"a","android_client_info":{"package_name":"com.meda.portico"}},"api_key":[{}]}]}""",
        // a malformed client BEFORE Portico's aborts the scan, exactly as the server's try/except does
        """{"project_info":{"project_id":"p","project_number":"1"},"client":[{"oauth_client":[]},{"client_info":{"mobilesdk_app_id":"a","android_client_info":{"package_name":"com.meda.portico"}},"api_key":[{"current_key":"k"}]}]}""",
        """{"project_info":{"project_id":"p","project_number":"1"},"client":[{"client_info":{"android_client_info":{}}}]}""",
        """{"project_info":{"project_id":"p","project_number":"1"},"client":["str"]}""",
        // a truthy client that is not a list (Python iterates it and fails inside the try)
        """{"project_info":{"project_id":"p","project_number":"1"},"client":{"a":1}}""",
    ])
    fun missingFields(text: String) {
        assertEquals(GoogleServicesError.MISSING_FIELDS, code(text))
    }

    @Test
    fun malformedClientAfterPorticosIsNeverReached() {
        // The server returns at the first match, so a broken entry after it does not matter.
        val text = """{"project_info":{"project_id":"$PROJECT_ID","project_number":"$SENDER_ID"},"client":[
            {"client_info":{"mobilesdk_app_id":"$APP_ID","android_client_info":{"package_name":"com.meda.portico"}},"api_key":[{"current_key":"$API_KEY"}]},
            {"broken":true}]}"""
        assertEquals(APP_ID, ok(text).appId)
    }

    @Test
    fun numericProjectNumberIsStringifiedLikePythonStr() {
        val text = """{"project_info":{"project_id":"$PROJECT_ID","project_number":$SENDER_ID},
            "client":[{"client_info":{"mobilesdk_app_id":"$APP_ID","android_client_info":{"package_name":"com.meda.portico"}},"api_key":[{"current_key":"$API_KEY"}]}]}"""
        assertEquals(SENDER_ID, ok(text).senderId)
    }

    @Test
    fun serverAcceptsButAppRefusesUnusableValues() {
        // parse_google_services returns these (it does not validate values); the app refuses them
        // before storing, because an app_id from another project or a placeholder can never register.
        val r1 = GoogleServicesParser.extract(Fixtures.googleServicesOnly("com.meda.portico", appId = "1:999:android:abc"))
        r1 as GoogleServicesResult.Error
        assertEquals(GoogleServicesError.INVALID_VALUES, r1.code)
        assertEquals(FirebaseField.APP_ID, r1.firebase?.field)
        val r2 = GoogleServicesParser.extract(Fixtures.googleServicesOnly("com.meda.portico", apiKey = ""))
        assertEquals(GoogleServicesError.INVALID_VALUES, (r2 as GoogleServicesResult.Error).code)
        // ...while the raw mirror of the server returns them unvalidated.
        val raw = GoogleServicesParser.extractValues(Fixtures.googleServicesOnly("com.meda.portico", apiKey = ""), "com.meda.portico")
        assertEquals("", (raw as RawExtract.Values).apiKey)
    }

    @Test
    fun oversizedInputIsRefused() {
        val big = Fixtures.GOOGLE_SERVICES_TWO_CLIENTS + " ".repeat(GoogleServicesParser.MAX_CHARS)
        assertEquals(GoogleServicesError.TOO_LARGE, code(big))
    }

    @Test
    fun errorCodesMatchTheServersStrings() {
        assertEquals("invalid_json", GoogleServicesError.INVALID_JSON.code)
        assertEquals("missing_fields", GoogleServicesError.MISSING_FIELDS.code)
        assertEquals("package_not_found", GoogleServicesError.PACKAGE_NOT_FOUND.code)
    }
}
