# SBplay 0.2.23 — upcoming release notes

Earlier upstream DiPlay changes through 0.2.12 are documented in [0.2.12 release notes](RELEASE-NOTES-0.2.12.md). Measured checks are in [VALIDATION.md](VALIDATION.md). Document new CS11-specific changes for SBplay 0.2.23 here.

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
