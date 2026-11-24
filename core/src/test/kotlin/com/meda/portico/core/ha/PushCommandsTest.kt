package com.meda.portico.core.ha

import com.meda.portico.core.ha.PushCommands.ErrorKind
import com.meda.portico.core.ha.PushCommands.Outcome
import com.meda.portico.core.ha.WsContract.INSTALLATION
import com.meda.portico.core.ha.WsContract.PROJECT
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * [PushCommands] on what the recorded contract does NOT contain: the local refusals, HA core's
 * other error codes, malformed successes. Every request and result the contract does contain is
 * replayed by [WsContractReplayTest].
 */
class PushCommandsTest {

    private fun result(text: String) = HaProtocol.parse(text).single() as HaInbound.Result

    private fun ok(id: Int, result: String) = """{"id":$id,"type":"result","success":true,"result":$result}"""
    private fun err(id: Int, code: String, message: String) =
        """{"id":$id,"type":"result","success":false,"error":{"code":"$code","message":"$message"}}"""

    @ParameterizedTest
    @ValueSource(strings = ["", "   ", "unknown", "UNKNOWN", "unavailable", "none", "null", "undefined", " Unknown "])
    fun `blank and placeholder registrations are refused before the wire`(value: String) {
        assertFalse(PushCommands.isUsableRegistration(value))
        assertThrows<IllegalArgumentException> {
            PushCommands.register(INSTALLATION, "fid", value, PROJECT, "0.1.0")
        }
    }

    @Test
    fun `schema limits are refused locally`() {
        assertFalse(PushCommands.isUsableRegistration("x".repeat(4097)))
        assertTrue(PushCommands.isUsableRegistration("x".repeat(4096)))
        assertThrows<IllegalArgumentException> { PushCommands.register("", "fid", "r", "p", "v") }
        assertThrows<IllegalArgumentException> { PushCommands.register("i", "topic", "r", "p", "v") }
        assertThrows<IllegalArgumentException> { PushCommands.register("i", "fid", "r", "", "v") }
        val long = PushCommands.register("i", "fid", "r", "p", "v".repeat(100))
        assertEquals(64, (long["app_version"] as kotlinx.serialization.json.JsonPrimitive).content.length)
    }

    @Test
    fun `codes the contract does not record - HA core's unknown_command, any other code`() {
        assertEquals(
            Outcome.Failed(ErrorKind.UNKNOWN_COMMAND, "unknown_command", "Unknown command."),
            PushCommands.parseRegister(result(err(4, "unknown_command", "Unknown command."))),
        )
        assertEquals(ErrorKind.OTHER, (PushCommands.parseRegister(result(err(4, "home_assistant_error", "x"))) as Outcome.Failed).kind)
        assertEquals(ErrorKind.OTHER, (PushCommands.parseRegister(result("""{"id":4,"type":"result","success":false}""")) as Outcome.Failed).kind)
    }

    @Test
    fun `every wire code the contract records has its own kind`() {
        @Suppress("UNCHECKED_CAST")
        val codes = WsContract.cases.filter { it.type.startsWith("portico/push/") }.map { it.receive.first() }.filter { it["success"] == false }
            .map { (it["error"] as Map<String, Any?>)["code"] as String }.toSet()
        assertEquals(
            setOf("push_not_configured", "unauthorized", "invalid_registration", "project_mismatch", "package_mismatch", "invalid_format", "not_found", "app_too_old"),
            codes,
        )
        for (code in codes) assertEquals(code, ErrorKind.of(code).wire, code)
    }

    @Test
    fun `a success without reg_id is a failure, not a crash`() {
        assertTrue(PushCommands.parseRegister(result(ok(1, "null"))) is Outcome.Failed)
        assertTrue(PushCommands.parseRegister(result(ok(1, "{}"))) is Outcome.Failed)
    }

    @Test
    fun `unregister - a removed flag missing from a success still counts as removed`() {
        assertEquals(Outcome.Unregistered(true), PushCommands.parseUnregister(result(ok(3, "{}"))))
        assertEquals(Outcome.Unregistered(false), PushCommands.parseUnregister(result(WsContract.reply("push unregister: not_found", 3))))
    }

    @Test
    fun `app_too_old has its own kind, and is not a retry-next-connect`() {
        assertEquals(ErrorKind.APP_TOO_OLD, ErrorKind.of("app_too_old"))
        val f = PushCommands.parseRegister(result(err(4, "app_too_old", "Portico app 0.5.0 or newer is required (contract 3)"))) as Outcome.Failed
        assertEquals(ErrorKind.APP_TOO_OLD, f.kind)
        assertEquals("app_too_old", f.code)
    }
}
