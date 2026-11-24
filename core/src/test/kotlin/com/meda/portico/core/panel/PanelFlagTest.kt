package com.meda.portico.core.panel

import com.meda.portico.core.ha.ContentProfile
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** DEV-807 (D-20261121-73ff): the server's flag, read from what a content result carries. */
class PanelFlagTest {
    private fun profile(phoneEndpoint: String? = null, screens: List<String> = emptyList(), houseCalls: Boolean? = null) =
        ContentProfile("", "r", emptyList(), emptyList(), phoneEndpoint = phoneEndpoint, screens = screens, houseCalls = houseCalls)

    @Test fun `a result with neither field is a phone's`() = assertFalse(PanelFlag.of(profile()))

    @Test fun `a phone_endpoint makes it a panel`() = assertTrue(PanelFlag.of(profile(phoneEndpoint = "phone-1")))

    @Test fun `a screens list makes it a panel even with no phone endpoint`() = assertTrue(PanelFlag.of(profile(screens = listOf("kitchen-panel"))))

    @Test fun `house_calls alone says nothing`() = assertFalse(PanelFlag.of(profile(houseCalls = true)))

    @Test fun `only the not_a_panel refusal says the flag is off`() {
        assertTrue(PanelFlag.isNotAPanel("not_a_panel"))
        assertTrue(PanelFlag.isNotAPanel("NOT_A_PANEL"))
        assertFalse(PanelFlag.isNotAPanel("app_too_old"))
        assertFalse(PanelFlag.isNotAPanel("invalid_format"))
    }
}
