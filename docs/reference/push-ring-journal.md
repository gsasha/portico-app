# Push and ring journal

The on-phone journal of what the app decided about each ring: its fields, storage, redaction and its write-only rule.

Settings has a **Push and ring journal** row (Copy, Clear, and the last 30 lines) so what the app DECIDED about a ring can be read
on the phone instead of out of `adb logcat`. One line per push received, per socket call event, and per ring / answer / dismiss
outcome the handler applied: the time (phone local, `HH:mm:ss.SSS`), the kind, the call id and door ref **shortened** (first six
characters and the length, `gso-17...(33)`), the outcome (`Ring`, `Dismiss`, `Missed(reason)`, `Silent(reason)`, `Quiet`), the
`seq`, the call time and its age, how many door refs were known, whether a Portico screen was open (`fg`/`bg`), and what the phone
allowed (full-screen intent, ring channel state and importance, notifications, Do Not Disturb).

- **Durable.** One string in its own prefs file (`portico_push_journal`), rewritten with a synchronous commit, so a push that
  wakes a fresh process is still there afterwards. At most 200 lines and 32 KB; the oldest go first.
- **Redacted.** `PushJournalFormat` shortens every id with `Redact.prefix` and prints enum names, numbers and a few whitelisted words
  only; no token, URL, enrolment code, registration, name or full id can reach a line, and a second scrub catches anything that
  still looks like one.
- **Write-only.** The decision path never reads it, every call is wrapped, and a journal that throws changes nothing (the golden
  test runs the same script with the journal off, on and broken and compares every notification and store).
- Home's menu "Copy layout log" is unchanged.
