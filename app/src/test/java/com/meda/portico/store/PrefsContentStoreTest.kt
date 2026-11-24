package com.meda.portico.store

import com.meda.portico.core.content.ContentRepository
import com.meda.portico.core.ha.ContentAction
import com.meda.portico.core.ha.ContentCaller
import com.meda.portico.core.ha.ContentCamera
import com.meda.portico.core.ha.ContentPage
import com.meda.portico.core.ha.ContentCommands.Outcome
import com.meda.portico.core.ha.ContentProfile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PrefsContentStoreTest {
    private val prefs = FakeSharedPreferences()
    private val cams = listOf(ContentCamera("gate", "Gate", "Gate camera", true))
    private val acts = listOf(ContentAction("open", "Front door", "lock_open", null, true, "Front door lock", true))
    private val profile = ContentProfile(
        "", "r1", cams, acts,
        pages = listOf(ContentPage("main", "Main", cams, acts)),
        callers = mapOf("*" to ContentCaller("Gate", "door", cams, acts, acts)),
    )

    @Test
    fun `the profile survives a new process and is read at construction`() {
        PrefsContentStore(prefs).save(profile)
        assertEquals(profile, PrefsContentStore(prefs).load())
        assertEquals(1, prefs.commits, "a synchronous commit")
    }

    @Test
    fun `nothing stored is null, and clear removes it`() {
        val s = PrefsContentStore(prefs)
        assertNull(s.load())
        s.save(profile)
        s.clear()
        assertNull(s.load())
    }

    @Test
    fun `a damaged value is nothing cached, not a crash`() {
        prefs.edit().putString(PrefsContentStore.KEY, "{not json").commit()
        assertNull(PrefsContentStore(prefs).load())
        assertEquals(ContentProfile.EMPTY, ContentRepository(PrefsContentStore(prefs)).profile)
    }

    @Test
    fun `the repository on top of it shows the last profile after a restart`() {
        ContentRepository(PrefsContentStore(prefs)).onContent(Outcome.Profile(profile))
        assertEquals(profile, ContentRepository(PrefsContentStore(prefs)).profile)
    }

    @Test
    fun `the stored text holds names and ids only`() {
        PrefsContentStore(prefs).save(profile)
        val text = prefs.getString(PrefsContentStore.KEY, null)!!
        assertTrue(listOf("entity_id", "device_id", "token", "password").none { text.contains(it) })
    }

    @Test
    fun `the cache from before blocks (version 2) is dropped on the first read`() {
        prefs.edit().putString(PrefsContentStore.KEY, """{"v":2,"rev":"r1","pages":[{"id":"p","label":"P","cameras":[{"id":"gate","label":"Gate","available":true}],"actions":[]}],"callers":{},"roster":[]}""").commit()
        assertNull(PrefsContentStore(prefs).load())
        assertEquals(ContentProfile.EMPTY, ContentRepository(PrefsContentStore(prefs)).profile)
    }

    @Test
    fun `a profile with blocks is stored and read back from the prefs`() {
        val block = com.meda.portico.core.ha.ContentBlock(
            "b", "Entrance", listOf(ContentCamera("gate", "Gate", null, true, com.meda.portico.core.ha.CameraSize.SMALL)),
            listOf(ContentAction("open", "Open", "cover_open", "mdi:gate", true, null, true, color = "green", showLabel = false)),
        )
        val withBlocks = ContentProfile("", "r2", block.cameras, block.actions, pages = listOf(com.meda.portico.core.ha.ContentPage.ofBlocks("p", "P", listOf(block))))
        PrefsContentStore(prefs).save(withBlocks)
        assertEquals(withBlocks, PrefsContentStore(prefs).load())
    }

    @Test
    fun `the cache from the older flat form is dropped on the first read`() {
        prefs.edit().putString(PrefsContentStore.KEY, """{"v":1,"phone":"P","rev":"r1","cameras":[],"actions":[]}""").commit()
        assertNull(PrefsContentStore(prefs).load())
        assertEquals(ContentProfile.EMPTY, ContentRepository(PrefsContentStore(prefs)).profile)
    }
}
