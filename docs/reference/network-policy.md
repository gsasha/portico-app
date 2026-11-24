# Network policy

Which Home Assistant addresses the app accepts (https always, plain http only on private networks), where the rule is checked, and the cleartext trade-off.

**The rule.** An `https://` Home Assistant address is always allowed. A plain `http://` address
is allowed only when its host is on a private network, and Settings then shows "Unencrypted
connection (plain http) on your own network" for as long as that address is stored. Anything else
is refused, and the save stops with the reason. This matches the server's QR, which prefers an
https URL and falls back to plain http only when the installer allows it (the server decides "on the basis
of whether https is configured").

Private, for plain http, means: IPv4 `10/8`, `172.16/12`, `192.168/16`, `169.254/16`
(link-local), `100.64/10` (CGNAT, which covers a tailnet) and `127/8`; IPv6 `fc00::/7`,
`fe80::/10`, `::1` and the IPv4-mapped forms of the IPv4 ranges; host names ending in `.local`,
`.lan`, `.home.arpa`, `.internal` or `.localdomain`; and single-label names such as
`homeassistant`. A public-looking name like `ha.example.com` over http is refused. So are a URL
with a user name or password in it, other schemes, an empty host, a bad port, an IPv6 zone id,
and any IPv4 address that is not four plain numbers (`010.0.0.1`, `0x7f.1`, `127.1`,
`2130706433`), because different parsers disagree about what those mean. A name that starts like
a private address (`10.0.0.1.evil.com`) is a public name. The code is
`core/src/main/kotlin/com/meda/portico/core/net/UrlPolicy.kt`, and `UrlPolicyTest` holds
the table of cases.

**Where it is checked.** On a scanned or pasted enrollment (`EnrollmentPayload`, so a refused
URL is never saved); in `SignalingClient` before every connect (a refused stored URL is never
opened, the state is `URL_REFUSED`, and the log line names only the reason); and again in
`OkHttpConnector`, as a backstop.

**The trade-off.** Android blocks cleartext at this app's target SDK unless the platform policy
allows it, and that policy (`app/src/main/res/xml/network_security_config.xml`, named by the
manifest's `android:networkSecurityConfig`) is fixed when the APK is built. It cannot express
"http only to an address on the LAN" for an address the user enters later. So the config permits
cleartext for the whole app, and the restriction is the app's own code. If a future code path
opened a plain-http connection to a public host without calling `UrlPolicy`, Android would not
stop it. Every new connect path has to call `UrlPolicy.check`. The app's other traffic (Firebase
and Google Play services) is https, set by those libraries, and the config trusts system CAs only.

**Why allow http at all.** A Home Assistant that answers only on plain http inside a trusted LAN
is common, and refusing it would leave those households without the app. The persistent warning
keeps the choice visible.
