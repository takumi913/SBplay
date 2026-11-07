# Building SBplay (CS11)

> ## This fork (Lynk & Co CS11) — read first
>
> The guide below is **upstream's** (BYD / Android 9+ target) and is kept for reference. For this
> fork the following is what actually applies:
>
> - Toolchain: **JDK 21** (not 25), Android SDK as configured by AGP 9.3.0, offline Gradle wrapper.
> - The accessory identity is **committed** (`mobile/src/main/auth-assets/offline-mfi/`), so
>   `:mobile:assembleStandaloneDebug` needs **no** environment variable:
>   ```powershell
>   .\gradlew.bat :mobile:clean :mobile:assembleStandaloneDebug --console=plain --offline
>   ```
>   `DIPLAY_AUTH_ASSETS_DIR` still works and **replaces** the committed identity (the committed pair
>   is then not part of the APK). The credential scan allows exactly the identity that is active.
> - The build fails, by name, when the active identity is missing or any *other*
>   `.pk8/.p7b/.key/.pem/.p12/.pfx/.jks/.keystore` appears in the assets.
> - `:mobile:clean` before assembling whenever the APK looks too large: incremental packaging once
>   produced a 15.87 MB APK carrying ~5 MB of dead zip space (a clean build is ~10.89 MB).
> - Built artifact: `mobile\build\outputs\apk\debug\mobile-debug.apk`.
> - **CI**: `.github/workflows/android.yml` ("Build CS11 APK") builds the same
>   `:mobile:assembleStandaloneDebug` on JDK 21 + NDK 25.2.9519653 + cmake 3.22.1, checks with
>   `aapt2`/`apksigner` that the APK is `sdkVersion 19` / `targetSdkVersion 19` / `armeabi-v7a` only /
>   v1-signed / carries the identity, then uploads it as the `SBplay-CS11-API19-apk` artifact
>   (Actions run page → Artifacts). Unit tests and lint run after the upload as gates.
>   CI proves it compiles and is installable; it does **not** prove it works — 必须上机复验.
> - **Releases**: publish a GitHub Release (tag e.g. `v0.2.23`) and the same workflow attaches
>   `SBplay-<tag>.apk` + `.sha256` to it. An empty description is filled with install info plus
>   GitHub's auto-generated "What's Changed" (grouped by labels via `.github/release.yml`); a
>   description you wrote yourself is never overwritten. Tip: use *Generate release notes* in the UI
>   to preview, and label PRs (`feature`, `bug`, `api19`, `ci`, `docs`, …) so they land in the right group.

Requirements: JDK 25, Android SDK 37, NDK 28.2.13676358 and the included Gradle wrapper.

## Source and CI builds

```sh
./gradlew :shared:testDebugUnitTest :common:testDebugUnitTest :mobile:lintDebug :mobile:assembleDebug
```

The resulting source-only APK contains no accessory identity. Standalone CarPlay requires runtime authentication provisioning. Tests generate synthetic identities at runtime; no test private-key files are tracked.

## Local release packaging

Provide an external asset directory using `DIPLAY_AUTH_ASSETS_DIR`. The directory must contain exactly the intended runtime files under `offline-mfi/identity.pk8` and `offline-mfi/certificate.p7b`. Neither file belongs in Git. The build permits those two files only when this explicit input is set and rejects unexpected credential containers elsewhere in APK assets.

Set `ANDROID_KEYSTORE_PATH`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, and `ANDROID_KEY_PASSWORD` locally for your Android signing key. Never commit these values or the keystore. Different signing keys cannot update an existing project-signed installation.

```sh
./gradlew :shared:testDebugUnitTest :common:testDebugUnitTest :mobile:lintRelease :mobile:assembleRelease
```

Output: `mobile/build/outputs/apk/release/mobile-release.apk`. The release APK deliberately contains the experimental identity described in the notices; it is extractable by recipients. The separate Android signing key is not included. The retired build-beta.py helper is not used; this Gradle workflow uses explicit environment inputs.

The public release source archive corresponds to the tagged source and excludes runtime identities, signing keys, local configuration and build output.

## Standalone car-test APK

Use `:mobile:assembleStandaloneDebug` for a test APK that must connect to an iPhone:

```sh
DIPLAY_AUTH_ASSETS_DIR=/absolute/path/to/runtime-assets ./gradlew :mobile:assembleStandaloneDebug
```

This task refuses missing or empty runtime inputs. `assembleDebug` remains an identity-free
source/CI build when the explicit asset input is absent; do not install that output as a
standalone car-test package. Before delivery, verify both `assets/offline-mfi/identity.pk8`
and `assets/offline-mfi/certificate.p7b` in the APK against the selected local inputs.
Update the existing test app without uninstalling it to preserve its settings.
