package com.meda.portico.core.enroll

import com.meda.portico.core.enroll.Fixtures.API_KEY
import com.meda.portico.core.enroll.Fixtures.APP_ID
import com.meda.portico.core.enroll.Fixtures.PROJECT_ID
import com.meda.portico.core.enroll.Fixtures.SENDER_ID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FirebaseConfigTest {

    private fun invalid(projectId: String = PROJECT_ID, senderId: String = SENDER_ID, appId: String = APP_ID, apiKey: String = API_KEY) =
        FirebaseConfig.create(projectId, senderId, appId, apiKey) as FirebaseConfigResult.Invalid

    @Test
    fun validValuesMakeAConfig() {
        val r = FirebaseConfig.create(PROJECT_ID, SENDER_ID, APP_ID, API_KEY) as FirebaseConfigResult.Ok
        assertEquals(PROJECT_ID, r.config.projectId)
        assertEquals(r.config, (FirebaseConfig.create(PROJECT_ID, SENDER_ID, APP_ID, API_KEY) as FirebaseConfigResult.Ok).config)
        assertNotEquals(r.config, (FirebaseConfig.create(PROJECT_ID, SENDER_ID, APP_ID, API_KEY + "x") as FirebaseConfigResult.Ok).config)
    }

    @Test
    fun blankAndPlaceholderValuesAreRefused() {
        assertEquals(FirebaseConfigResult.Invalid(FirebaseField.PROJECT_ID, FirebaseProblem.BLANK), invalid(projectId = " "))
        assertEquals(FirebaseConfigResult.Invalid(FirebaseField.API_KEY, FirebaseProblem.BLANK), invalid(apiKey = ""))
        for (p in listOf("unknown", "Unavailable", "none", "NULL", "undefined")) {
            assertEquals(FirebaseConfigResult.Invalid(FirebaseField.API_KEY, FirebaseProblem.PLACEHOLDER), invalid(apiKey = p))
        }
    }

    @Test
    fun malformedValuesAreRefused() {
        assertEquals(FirebaseField.PROJECT_ID, invalid(projectId = "Portico Test").field)
        assertEquals(FirebaseField.PROJECT_ID, invalid(projectId = "x".repeat(200)).field)
        assertEquals(FirebaseField.SENDER_ID, invalid(senderId = "12ab").field)
        assertEquals(FirebaseField.APP_ID, invalid(appId = "1:$SENDER_ID:ios:abc").field)
        assertEquals(FirebaseField.API_KEY, invalid(apiKey = "has space").field)
    }

    @Test
    fun appIdMustBelongToTheSameProjectNumber() {
        assertEquals(FirebaseConfigResult.Invalid(FirebaseField.APP_ID, FirebaseProblem.OTHER_PROJECT), invalid(appId = "1:555:android:abc"))
    }

    @Test
    fun messagesNameTheFieldAndNeverTheValue() {
        val r = invalid(apiKey = "has space")
        assertTrue("api_key" in r.message, r.message)
        assertTrue("has space" !in r.message)
    }
}
