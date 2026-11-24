# Icon

The launcher and notification icons, how they are built and where the master and generator live.

A white home outline (pitched roof, round stroke ends) with an amber phone handset and two call
arcs inside, on deep blue (#1F4E8C), all within the 66dp adaptive-icon safe zone. The launcher icon is an
adaptive icon (`mipmap-anydpi-v26/ic_launcher.xml` and `ic_launcher_round.xml`, same content)
built from vector drawables in `app/src/main/res/drawable/`: `ic_launcher_foreground`,
`ic_launcher_background` and `ic_launcher_monochrome`. minSdk is 26, so there are no legacy PNG
mipmaps. The monochrome layer is what Android 13+ themed icons tint with the wallpaper colours.

The notification glyph, `ic_stat_portico`, is single-colour by design: the system draws status-bar
and notification small icons as an alpha mask, so any colour in them would be lost. `RingNotifier`
uses it for both the ring and the status notifications. Since 0.6.68-alpha it is the launcher glyph
(home outline plus phone handset, the two call arcs dropped as too fine at 24dp) in white, strokes 1.6,
every point within 2..22 of the 24 viewport; `IconResourcesTest` pins that.

The master (`portico.svg`), the generator (`make_icon.py`) and how to regenerate are in
`design/icon/`. The on-device look is not verified here (no emulator).
