package com.meda.portico.signaling

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * The app module's reader for core's vendored `ws_v3.json` (the recorded server frames, contract 3). The
 * file lives once, in `core/src/test/resources` (sha256-pinned by core's `WsContractTest`), not
 * vendored twice: `app/build.gradle.kts` declares it as the unit-test task's input (so a changed
 * contract re-runs these tests) and passes its path as `portico.wsContract`; without that
 * property (an IDE run) it is found from the working directory. kotlinx-serialization is already
 * on the app's classpath.
 */
object WsContractFile {
    private const val RELATIVE = "core/src/test/resources/ws_v3.json"

    val file: File by lazy {
        System.getProperty("portico.wsContract")?.let { File(it) }?.takeIf { it.isFile }?.let { return@lazy it }
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val f = File(dir, RELATIVE)
            if (f.isFile) return@lazy f
            dir = dir.parentFile
        }
        throw AssertionError("$RELATIVE not found above ${System.getProperty("user.dir")}")
    }

    private val cases: List<JsonObject> by lazy {
        Json.parseToJsonElement(file.readText()).jsonObject["cases"]!!.jsonArray.map { it.jsonObject }
    }

    private fun case(name: String): JsonObject =
        cases.firstOrNull { it["name"]!!.jsonPrimitive.content == name } ?: throw AssertionError("no case '$name' in ws_v3.json")

    /** [name]'s request without its connection-local `id` (the file's `_meta`: ids are per connection). */
    fun request(name: String): JsonObject = JsonObject(case(name)["send"]!!.jsonObject - "id")

    /** [name]'s server frames renumbered to [id], as wire text. */
    fun frames(name: String, id: Int): List<String> =
        case(name)["receive"]!!.jsonArray.map { JsonObject(it.jsonObject + ("id" to JsonPrimitive(id))).toString() }

    /** The doors the enrolled phone's `portico/doors` result lists (`door_id`s). Contract 3 carries no secrets. */
    val doorIds: List<String> by lazy {
        val doors = case("doors: enrolled phone")["receive"]!!.jsonArray[0].jsonObject["result"]!!.jsonObject["doors"] as JsonArray
        doors.map { it.jsonObject["door_id"]!!.jsonPrimitive.content }
    }
}
