# SBplay 0.2.26 — upcoming release notes

Earlier upstream DiPlay changes through 0.2.12 are documented in [0.2.12 release notes](RELEASE-NOTES-0.2.12.md). Measured checks are in [VALIDATION.md](VALIDATION.md). Document new CS11-specific changes for SBplay 0.2.26 here.

## Crash diagnostics on Android 4.4.3

- Failure details no longer allow long-press text selection, avoiding a system ROM crash
  when its selection popup loads invalid XML string resources. Use "Copy page" for a
  single screen of diagnostic text; full logs remain available via report export.
- Keep the five most recent new Java/Kotlin crash stacks plus the pre-update legacy
  crash record. Never erase a past crash merely because another connection starts.
- Show crashes matching the interrupted connection's PID before other process crashes;
  unrelated crashes are labeled explicitly, avoiding mistaken root-cause attribution.
- **必须上机复验**: APK compilation/CI cannot establish runtime behavior on CS11.

## CS11 manual hotspot startup

- The host now takes the wireless hotspot SSID, passphrase and open/WPA2 security
  from the car firmware's stored SoftAP configuration (same source as the settings UI).
  When the firmware does not expose a usable pair, the previously saved manual values
  remain a fallback. A firmware profile with an unsupported raw 64-digit PSK is
  rejected with an actionable message rather than silently using a wrong credential.
- For firmware-provided AP credentials, reset stale saved manual band/channel hints
  to automatic discovery rather than conflicting with the active profile.
- Hotspot configuration errors are shown in the app's existing connection-failure UI
  instead of crashing in CarPlayRuntimeConfig's constructor. The session isn't started.
- **必须上机复验**：CI build/tests do not prove that this firmware's AP exposes a
  readable, correctly quoted password or that wireless CarPlay pairs successfully.

## Microphone uplink (CS11)

- The microphone Opus encoder is now chosen in three steps: Android's MediaCodec Opus encoder (API 29
  and later), the bundled `libdiplay_opus.so`, then the vendored pure-Java
  [Concentus](https://github.com/lostromb/concentus) encoder. Wireless CarPlay sends calls and Siri as
  Opus and accepts no PCM alternative, so a head unit whose only encoder is the Java one would
  otherwise have no uplink at all. The audio diagnostic report names the implementation in use
  (`implementation=libopus|software|mediacodec`).
- The `audioInputFormats` entries offer Opus only when the car can really encode it; PCM stays on
  offer. `audioOutputFormats` still advertises Opus in every entry, unchanged: removing it there once
  made iOS build no audio stream at all.
