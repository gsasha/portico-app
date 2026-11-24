# Scanning the enrolment QR

Why the scanner is Camera2 plus ZXing core, what the camera permission does, how big the QR may be, and what only a real phone can prove.

Settings' **Scan the QR code** opens a full-screen viewfinder (`setup/ScanActivity`). The first code
it reads goes through exactly the paste field's path: `core/.../scan/ScanIntake` hands it to
`SetupPlan.evaluate` with the google-services field as it stands, so the messages and the save
are the same. The scanned text travels to Settings in process (`ScanHandoff`, never an Intent extra),
is never put in the paste field, never stored and never logged. An empty code, a cancelled scan,
no camera or a camera failure each get their own line; the paste field always remains.

**Why Camera2 + ZXing core, and not a library or Google's scanner:**

| Option | For | Against |
| :--- | :--- | :--- |
| **ZXing core 3.5.3 + Camera2 (chosen)** | The old intercom app's scanner, proved on real phones (2026-09-13), ported nearly line for line. One pure-Java jar, no transitive deps; the decoder runs in `:core`, so it is JVM-tested. Works without Google Play services and without any download | Needs the `CAMERA` permission; ~300 lines of Camera2 glue to own |
| `zxing-android-embedded` | Ready-made capture activity | The same ZXing core plus a library activity, its own manifest entries and themes; last release 4.3.0 in 2021 (from memory, not re-checked); still needs `CAMERA` |
| `play-services-code-scanner` | No `CAMERA` permission, Google's UI | Needs Google Play services and downloads its scanner module on first use (a network call to Google at Settings). Without Play services or offline it fails, and the app would need this paste fallback anyway |

Portico's push ring notification needs Play services, but its socket ring does not, and the repo goes public:
a scanner that works on any phone with a camera, offline, is worth one runtime permission.

**Permission:** `CAMERA` is asked only when the button is tapped, after a short explanation that
also says pasting works. Refused: a line says so and the next tap explains and asks again.
"Don't ask again": Android answers without a dialog, and Settings offers the app's settings screen.
The logic (`CameraPermissionPlan`) is in `:core` with tests; "never asked" is a stored flag so it
is not mistaken for "blocked". The manifest declares `android.hardware.camera`, `.camera.any` and
`.camera.autofocus` as `required="false"`, so the app still installs on a device without a camera
(the button then says so). `MergedManifestTest` asserts these, that `ScanActivity` is not
exported, and that no ML Kit or CameraX component is merged in.

**QR size (sender spec §5.4), measured by `QrSizeAndDecodeTest`** at the server's level
(`_build_qr_data_uri`: python-qrcode, ECC M, compact JSON):

| Payload | Bytes | QR version at M |
| :--- | ---: | ---: |
| marker, Nabu Casa URL, 183-char long-lived token, phone name, Tailscale key, `fcm` block | 579 | 19 (93 modules) |
| the same without `tailscale_authkey` (manual mode) | 493 | 17 |
| a 24-letter Hebrew phone name, escaped as `json.dumps` does by default | 680 | **21** |

Version 21 is just past the spec's "about version 20" ceiling. The fix, if a phone finds it too
dense on a laptop screen, is the server's (`ensure_ascii=False` gives 604 bytes, version 19, or
shorter keys).

The same test decodes synthetic camera frames through the app's own decoder (`QrFrameDecoder`,
`LumaPlane`): the real payload from a noisy, low-contrast 1280x720 frame at 3 px a module, with a
padded row stride, a pixel stride of 2 and a quarter turn; and the densest QR there is (version
40, 2,331 bytes at M) clean at 3 px a module in 720p and noisy at 4 px in 1080p. No single QR holds
more than 2,953 bytes (version 40, ECC L), so a 4-6 kB payload could never be one code.

The scanner screen's arithmetic (`core/.../scan/Viewfinder`) was rendered to a PNG at 1080x2400
(density 3, font scale 1.0 and 1.3): the aiming square (y 680-1480) clears the top bar by 418-450 px
and the bottom bar by 455-540 px. That checks composition only.

**Only a real phone can prove:** the camera preview (orientation, centre-crop, focus), the
permission dialog and its "don't ask again" path, and a real scan of a QR on a laptop or phone
screen (glare, moiré, focus distance). Both ends of the host tests are ZXing, so they prove the
format and the decoder's reach, never the optics.

The debug APK grows by about 298 KB (6,921,985 to 7,226,935 bytes, clean builds), almost all ZXing
core's dex; the build does not minify.
