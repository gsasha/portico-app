package com.meda.portico.core.content

/**
 * Which icon a Home button draws next to its text. Pure: the app turns the returned key into a bundled
 * drawable (`ic_mdi_<key with - as _>`), so a key that is not in [CURATED] can never be asked for.
 *
 * Source: Material Design Icons (Apache-2.0, third_party/mdi/NOTICE). Home Assistant writes the icon as
 * `mdi:door-open`; the plugin (portico-ha) offers exactly this list in its picker. The list is
 * `contract/icons_v1.json` in portico-ha (118 names since blocks), copied to core/src/test/resources/icons_v1.json,
 * and a test fails if this constant and that file disagree. To add an icon: add it to the JSON in both repos,
 * here, and run tools/gen_mdi_drawables.py.
 *
 * Rules: the icon is optional and extra, the text always stays. An icon the app does not bundle (any
 * other `mdi:` name, an `hass:` or `phu:` name, junk) gives NO icon from the stored string and falls
 * back to one by the button's kind; an unknown kind gives none.
 */
object HomeIcons {
    val CURATED: List<String> = listOf(
        "door", "door-open", "door-closed", "door-closed-lock", "gate", "gate-open", "garage", "garage-open", "fence",
        "lock", "lock-open", "lock-open-variant", "lock-outline", "lock-open-outline", "lock-open-variant-outline",
        "key", "key-variant", "shield-home", "shield-lock", "home", "home-outline", "home-lock", "home-export-outline",
        "bell", "bell-ring", "alarm-light", "doorbell",
        "lightbulb", "lightbulb-on", "lightbulb-outline", "lightbulb-off", "ceiling-light", "lamp", "floor-lamp",
        "wall-sconce", "led-strip-variant", "light-switch", "toggle-switch", "toggle-switch-off", "power", "power-plug", "flash",
        "thermometer", "thermostat", "fan", "air-conditioner", "radiator", "fire", "snowflake", "water-boiler",
        "weather-sunny", "weather-night",
        "blinds", "blinds-open", "window-shutter", "window-shutter-open", "window-open", "window-closed", "curtains",
        "curtains-closed", "roller-shade",
        "cctv", "camera", "video", "motion-sensor",
        "phone", "phone-ring", "phone-hangup", "phone-in-talk", "phone-voip", "volume-high", "volume-off", "microphone",
        "script-text", "play", "play-circle", "palette", "movie-open", "sofa", "bed", "sleep", "car", "car-electric", "walk", "exit-run",
        "water", "water-pump", "sprinkler", "sprinkler-variant", "flower", "washing-machine", "television", "speaker",
        "robot-vacuum", "coffee",
        "gesture-tap-button", "gesture-tap", "checkbox-marked-circle", "cog",
        // Blocks (DEV-658): +19, doors, gates, garages, locks, talk.
        "door-sliding", "door-sliding-lock", "door-sliding-open", "gate-arrow-left", "gate-arrow-right", "boom-gate", "boom-gate-up",
        "garage-variant", "garage-open-variant", "garage-lock", "lock-smart", "lock-check", "lock-clock", "lock-alert", "lock-open-check",
        "account-voice", "microphone-off", "phone-cancel", "doorbell-video",
    )

    private val CURATED_SET: Set<String> = CURATED.toSet()

    /** The icon a button of this [kind] gets when it has none of its own (or an unusable one). */
    val KIND_FALLBACK: Map<String, String> = mapOf(
        "portico_open" to "door-open",
        "lock_open" to "lock-open-variant",
        "button_press" to "gesture-tap-button",
        "script_run" to "script-text",
        "scene_activate" to "palette",
        "call" to "phone-in-talk",
        // Blocks (design 3.3): the five new kinds. An older app has none of these kinds and draws a plain button.
        "lock_lock" to "lock",
        "lock_unlock" to "lock-open-variant",
        "cover_open" to "garage-open",
        "cover_close" to "garage",
        "talk" to "account-voice",
    )

    /** The curated key a stored icon string names, or null: `mdi:Door-Open `, `door-open`, `door_open` all give "door-open". */
    fun keyOf(icon: String?): String? {
        if (icon == null) return null
        var s = icon.trim().lowercase()
        if (s.startsWith("mdi:")) s = s.substring(4).trim()
        s = s.replace('_', '-')
        return s.takeIf { it in CURATED_SET }
    }

    /** The icon to draw for a button: its own if usable, else the kind's, else none. */
    fun resolve(icon: String?, kind: String): String? = keyOf(icon) ?: KIND_FALLBACK[kind]

    /**
     * APP-02: a Talk or call button is always a phone: `phone` at rest, `phone-in-talk` while its call is under way, whatever
     * icon the document names. Null for every other kind (they use [resolve]).
     */
    fun callIcon(kind: String, active: Boolean): String? =
        if (kind == "talk" || kind == "call") (if (active) "phone-in-talk" else "phone") else null

    /** The drawable resource name for a key (the generator uses the same rule). */
    fun drawableName(key: String): String = "ic_mdi_" + key.replace('-', '_')
}
