# SBplay 0.2.25 — upcoming release notes

Earlier upstream DiPlay changes through 0.2.12 are documented in [0.2.12 release notes](RELEASE-NOTES-0.2.12.md). Measured checks are in [VALIDATION.md](VALIDATION.md). Document new CS11-specific changes for SBplay 0.2.25 here.

## Crash diagnostics on Android 4.4.3

- Failure details no longer allow long-press text selection, avoiding a system ROM crash
  when its selection popup loads invalid XML string resources. Use "Copy page" for a
  single screen of diagnostic text; full logs remain available via report export.
- Keep the five most recent new Java/Kotlin crash stacks plus the pre-update legacy
  crash record. Never erase a past crash merely because another connection starts.
- Show crashes matching the interrupted connection's PID before other process crashes;
  unrelated crashes are labeled explicitly, avoiding mistaken root-cause attribution.
- **必须上机复验**: APK compilation/CI cannot establish runtime behavior on CS11.

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
