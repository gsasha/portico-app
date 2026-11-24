# Live video

Live camera video on Home at home (WebRTC), away from home (RTSP over the tailnet), and on the ring and in-call screens, with the thermal and decoder guards.

## Live video on Home (DEV-713, stage S1)

Camera tiles on Home play live video (WebRTC, **video only**: the offer has one receive-only video line, no microphone is opened and
no permission is added) when the phone is **at home**. Away from home (the socket on the tailnet) nothing is attempted: the tile keeps
its stills (since stage S1b it plays over RTSP instead: see "Live video away from home" below). Settings -> "Live video in this
app" is **on by default**; off, no live command is ever sent and Home is what it was in 0.6.15 (no chip, no notice).

- **The decision is `:core`'s** (`core/.../live/`, JVM-tested): `LivePlan` (which tiles stream: the switch, Home started, not paused,
  link up, path `DIRECT`, the tile wants live and is not completely off screen), `LiveMemory` (the chip's choice, per camera, kept only
  while it differs from the document's `live`; an admin changing `live` reaches a phone that chose against the old value),
  `LiveSession` (one stream: offer, answer, candidates, first frame; the 6 s first-frame deadline; retries after 2 s, 5 s and 15 s,
  counted again after a good run, then the still until a tap on the chip or the tile coming back into view; an error answer to the
  command is final; exactly one `live_close` per session id the server gave, also when the id arrives after the teardown),
  `LiveManager` (the sessions, keyed by camera and size, held outside the view tree: a re-layout of the grid reconciles to the same set
  and reopens nothing), `FpsMeter` (`--`, `N fps` about once a second, `0 fps` after 2 s without a frame). A Big tile asks for `big`
  (the main stream), a Small one for `small`; a size change is a new session.
- **How "at home" is known:** `AppGraph.linkPath` (`LinkPath.DIRECT` / `TAILNET`, set by the connector when the socket opens), read by
  `HomeActivity.syncLive`. `FallbackConnector`, `ConnectTimeline` and the tailnet code are not touched. A direct URL that is really a public
  address (mobile data) fails ICE and falls back to the still after 6 s with "Live unavailable here: could not connect".
- **On the tile:** a small chip top-left is the live/still switch (a tap on the tile stays Big/Small): red dot and `LIVE` while real
  video renders, a dim `Live` (play glyph) on a still that can go live or while it connects. The fps label (bottom-right) shows only on a
  playing tile and only when the document's `show_fps` is true. The notice (bottom strip) names the class when live is not running:
  away, "this camera has no live video", "too many live streams", "this phone could not show the video", "could not connect", "Home
  Assistant does not offer it". The still stays under the video as the poster and the fallback; a pinch shows the still while zoomed.
- **Offscreen tiles do not stream** (`getGlobalVisibleRect` per tile, every second and 300 ms after a scroll; nothing streams before
  the first report). Home stopped (another app, screen off, a call screen) or the pause feature on closes every stream; there is no idle drop.
- **The renderer is a `SurfaceViewRenderer`** per streaming tile, above the still (index 1 of the tile's frame) and below the words,
  invisible until it has drawn a frame. No `TextureView` renderer ships in the libwebrtc artifact; it would be the fallback if the
  SurfaceView misbehaves inside Home's `ScrollView` (clipping, flicker on a rebuild), which only a phone can show.
- **libwebrtc:** one shared `PeerConnectionFactory` per Home visit (`LiveVideoEngine`), hardware-preferring decoders, no encoder,
  the same `JavaAudioDeviceModule` the call path uses, muted and never started. Peer connections are disposed on their own thread.
- **Journal:** `live start cam=.. size=big stream=main ttff=840ms`, `live fail cam=.. size=big why=connect retry=1 next=yes`,
  `live stop cam=.. size=big why=hidden secs=12`. Camera ids shortened; no SDP, candidate, address or token.
- **Not proven (no phone here):** time to first frame, whether the phone's Wi-Fi reaches go2rtc's UDP port, the decoder's behaviour and
  instance limits, battery and heat, the look of the chip and the label, the SurfaceView inside the `ScrollView`.

## Live video away from home (DEV-713, stage S1b)

When the socket is on the tailnet, a live tile pulls **H.264 over RTSP from go2rtc through the app's own tailnet node** and decodes it with
the phone's hardware `MediaCodec` into a plain `SurfaceView`: the old intercom app's route (since its 0.30.1-alpha), not WebRTC (UDP cannot
cross the embedded node, which carries TCP only). **The path picks the route** (`LivePlan`): `DIRECT` is WebRTC as in S1; `TAILNET` is RTSP when
the tile has an address; an unknown path attempts nothing. **The tile size picks the stream** (decision Q8): a Big tile pulls the main stream, a
Small tile the sub stream (the main one when the server listed no sub). Same Settings switch, chip, fps label, remembered choice, offscreen /
background / pause rules and Journal as at home; there is no stream cap. During an away call the video keeps running (a separate TCP connection).

- **The address** is the camera tile's additive `live_rtsp` `{main, sub?}`, sent only on a live Frigate-backed tile once the admin has set
  `live_rtsp_base` (and, for Small tiles, `live_sub_streams`) in the Settings tab. `LiveRtsp` reads it **strictly**, because it is handed to the
  tailnet dialer: only `rtsp://host[:port]/name` (IPv4 or host name, no user info, no IPv6, no query or fragment, `name` of `[A-Za-z0-9_-]{1,64}`,
  200 characters at most, `sub` on the same host and port as `main`); anything else makes the whole object absent and never drops the page.
  The cache (`ContentCodec`) writes it only when present; the codec version stays 3. With no address the tile says "Live video away from home
  is not set up for this camera" and keeps its stills. In a build without `gocore.aar` the old "not available yet" notice stays.
- **Needs** the Tailscale grant `tag:portico-phone` -> `192.168.2.6:tcp:8554` (go2rtc's RTSP port; NOT `192.168.2.5`, where nothing listens, and not
  8555). The stream bypasses the per-camera grant of `content/snapshot` (decision Q7): an enrolled phone's tag can pull any go2rtc stream.
- **Go client** (`gocore/internal/rtsp`, stdlib only, gomobile surface `tailnet.StartRtspVideo` / `RtspVideo.Stop`): the old client plus a 5 s
  watchdog, a clean Stop (a best-effort TEARDOWN, the connection closed, every goroutine gone), a bounded queue to the sink (non-reference
  pictures dropped first; when a picture others depend on does not fit it empties and waits for the next IDR) and a 1 MB cap per NAL unit.
  It dials **`tcp` through the node's `Dial`**, not through the forwarder (that bridges one fixed target, token first). One session is one
  connection attempt: it never reconnects by itself and says why it ended (`dial`, `rtsp`, `protocol`, `stalled`, `lost`). No state word,
  stat or error carries the address, a host or a credential.
- **Kotlin** (`:core`'s `RtspLiveSession`): the same states and notices as the WebRTC session; nothing reaches the decoder before the
  parameter sets and an IDR (again after a reconnect); no first frame in **10 s** (`FIRST_FRAME_MS`, TCP over the tailnet starts slower) or
  no NAL unit for 6 s after some came is a failure; retries after 2 s, 5 s and 15 s (counted again after a good run), then the still until a
  tap on the chip or the tile coming back into view. A phone with no H.264 decoder keeps its still with "this phone could not show the video".
  `NalQueue` bounds the hand-off to the decoder thread. The app side is `MediaCodecRtspDecoder` (one thread, configured from the SPS/PPS, started
  at an IDR, released on stop) and `RtspTileSurface`.
- **The `SurfaceView` is never INVISIBLE** (a SurfaceView that is not visible has no surface, so the decoder could never draw its first frame):
  "not shown" is a 1x1 view in the corner with the still visible; "shown" fits the video inside the tile, keeping its aspect (the frame is black, a
  4:3 sub stream gets bars), with the still hidden behind it. It sits above the still and below the words, like the WebRTC renderer.
- **Journal:** `live start cam=.. size=small route=rtsp stream=gate_intercom_sub ttff=2100ms`, `live fail ... route=rtsp stream=.. why=connect
  retry=0 next=yes`, `live stop ... route=rtsp stream=.. why=hidden secs=12`: the stream **name** only.
- **Not proven (no phone here, and `gocore.aar` is not built here):** the Go-to-Kotlin binding (`gomobile bind`), the time to the first IDR over
  mobile data, DERP versus a direct tailnet path, the decoder (instance limits, the surface swap on a rebuild, heat), the look of the 4:3 bars.

## Live video on the ring and in-call screens, and the thermal and decoder guards (DEV-713, stage S2)

The caller's **first available camera is live by default** on the ring screen and the in-call screen (decision Q4); the caller's other cameras stay
stills, each with the same LIVE chip (a tap flips it, remembered by camera id only while it differs from that default). The tiles are full width,
so the stream is the **main** one. The route is Home's (`LivePlan`): `DIRECT` is WebRTC with the caller camera's `c-...` id through
`portico/content/live`; `TAILNET` is RTSP when the caller's camera has a `live_rtsp` address (the server now sends it there too, portico-ha #189;
a caller's camera never carries `live`), else the "not set up" notice; an unknown path attempts nothing. The fps label shows with the document's
`show_fps`, the notice strip says why live is not running. A stream plays only while the call is on: the ring screen while the call rings, the
in-call screen while the call is live.

**The call comes first** (hard rules, all in `CallerLiveController`; `InCallActivity` holds one line each in `onCreate`,
`onResume`, `onPause` and the stills filter, and nothing on the Answer, Decline, hang-up or mute path):

- **Nothing before the screen is up.** `onResume` only takes the shared live code and arms a timer; no plan, no stream, no renderer for
  `ARM_DELAY_MS` (800 ms). A tile must also have its **still drawn** (`PictureState.PICTURE`) and be on screen. The streams run on `LiveHome`'s
  own thread; the screen's ticks do not wait for them.
- **Nothing it throws reaches the screen.** Every entry point runs under `LiveFaults.run` (a Journal line `live fault at=sync err=...`, once per
  place and exception class); `LiveManager` fences each session's create, start and stop; the activity builds the controller in a `try`
  and goes on without it if that throws. The screen's own `renderCaller` was already fenced.
- **One session across ring -> in-call.** Both screens use ONE `LiveHome` held by `AppGraph.callerLive` through a `LiveLease`: Android resumes the
  in-call screen before it stops the ring screen, so two separate holders would double the stream. The in-call screen binds its own renderer to
  the stream the ring screen started; nothing is renegotiated. The lease tears the live code down **3 s after the last screen lets go** (screen
  off, Home pressed, call over), closing every stream once (`live_close`). Home keeps its own `LiveHome`; it stops in `onStop`, before a call
  screen's 800 ms arming ends.
- **Independent of the call.** The call's audio is `AnswerCoordinator`'s separate audio-only peer connection; the live code never reads or
  writes it, the microphone, the audio mode, `FallbackConnector`, the connection timeline or the tailnet internals. The lock-screen configuration
  of both activities (`showWhenLocked`, `turnScreenOn`, the window flags) is unchanged and pinned by a source guard.

**Thermal guard** (`ThermalGuard`, `PowerManager.addThermalStatusListener`, API 29; below it, or on a throw, the phone is never "hot"). At
`THERMAL_STATUS_SEVERE` or worse every live tile on **Home, ring and in-call** drops to its still with "Live paused: the phone is hot", and stays
there until the status is back to `NONE` or `LIGHT`; `MODERATE` changes nothing (no flapping). One Journal line per transition:
`live thermal paused status=severe` / `live thermal resumed status=light`; the streams' own `live stop ... why=thermal` lines.

**Decoder-instance guard** (`DecoderCap`): the H.264 decoder's `getMaxSupportedInstances()` (API 23, read once on the live thread) minus one, at
least one (3 when the phone does not say). Every live stream counts, RTSP and WebRTC alike (libwebrtc's own decoders come from the same pool, and
a software one only makes the cap safer). Tiles are served in order; one over the cap stays a still with "too many live videos for this phone".
There is no cap beyond what the phone can decode.

**Not proven (no phone here):** that a `SurfaceView` over the lock screen draws (a normal window, so it should), that a `SurfaceView` inside the
ring layout's `ScrollView` behaves, the decoder instance numbers real phones report, the time to the first frame on a ringing phone, and the
thermal listener on a real hot phone.
