plugins {
    alias(libs.plugins.android.application)
}

// The offline MFi identity the software-simulated CarPlay authentication needs. It is committed under
// src/main/auth-assets so a clone builds and runs standalone; DIPLAY_AUTH_ASSETS_DIR replaces it with
// a different identity (and then the committed copy is not part of the APK at all, because the source
// set points at the replacement).
val localAuthenticationAssets = providers.environmentVariable("DIPLAY_AUTH_ASSETS_DIR")
    .orNull?.let { file(it).canonicalFile }
val authenticationAssets = localAuthenticationAssets ?: file("src/main/auth-assets")

android {
    namespace = "com.shilapi.xcertplay"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.shihab.diplay"
        minSdk = 19
        targetSdk = 19
        versionCode = 46
        versionName = "0.2.28"
        multiDexEnabled = true
        // CS11 only: the head unit is 32-bit armv7 and the bundled libopus ships armeabi-v7a.
        ndk { abiFilters += "armeabi-v7a" }
    }

    sourceSets.getByName("main").assets.srcDir(authenticationAssets)

    signingConfigs {
        create("release") {
            storeFile = file(
                providers.environmentVariable("ANDROID_KEYSTORE_PATH")
                    .getOrElse("missing-release-keystore.jks"),
            )
            storePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").getOrElse("")
            keyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS").getOrElse("")
            keyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD").getOrElse("")
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".hudtest"
            // Preserve .hudtest application ID for upgrades; the visible version is the release version.
        }
        release {
            optimization {
                enable = false
            }
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        isCoreLibraryDesugaringEnabled = true
    }

    // CS11: NewApi blocks the build; the remaining checks are meaningless with targetSdk 19.
    lint {
        abortOnError = true
        disable += listOf(
            "UnsafeOptInUsageError", "UnsafeOptInUsageWarning",
            "MissingPermission", "ExpiredTargetSdkVersion", "ForegroundServiceType",
            "StringFormatMatches", "StringFormatInvalid", "RestrictedApi",
            "SuspiciousIndentation", "PropertyEscape", "MissingTranslation",
        )
    }
}

dependencies {
    implementation(project(":common"))
    implementation(project(":shared"))
    implementation(libs.androidx.activity)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.multidex)
    coreLibraryDesugaring(libs.desugarJdkLibs)
}

// No implicit import. Only the selected runtime identity (committed, or the DIPLAY_AUTH_ASSETS_DIR
// replacement) is allowed; any other credential container in the assets fails the build.
val credentialAssets = files(android.sourceSets.flatMap { source ->
    source.assets.directories.map { directory ->
        fileTree(directory) {
            include("**/offline-mfi/**", "**/*.pk8", "**/*.p7b", "**/*.key",
                "**/*.pem", "**/*.p12", "**/*.pfx", "**/*.jks", "**/*.keystore")
        }
    }
})
val rejectBundledCredentials by tasks.registering {
    group = "verification"
    description = "Reject unexpected credential files in APK assets."
    val filesToCheck = credentialAssets
    val allowed = listOf("identity.pk8", "certificate.p7b")
        .map { authenticationAssets.resolve("offline-mfi/$it").canonicalFile }.toSet()
    inputs.files(filesToCheck)
    doLast {
        check(allowed.all { it.isFile }) { "The CarPlay authentication identity is incomplete" }
        val unexpected = filesToCheck.files.filter { it.canonicalFile !in allowed }
        check(unexpected.isEmpty()) { "Unexpected credential files in APK assets" }
    }
}
tasks.named("preBuild") { dependsOn(rejectBundledCredentials) }

// Car-test packages must be standalone: the identity comes from the committed auth-assets, or from
// DIPLAY_AUTH_ASSETS_DIR when a different accessory identity is supplied.
val verifyStandaloneAuthentication by tasks.registering {
    group = "verification"
    description = "Require the runtime authentication identity for a standalone car-test APK."
    val directory = authenticationAssets
    val overridden = localAuthenticationAssets != null
    doLast {
        check(listOf("identity.pk8", "certificate.p7b").all {
            directory.resolve("offline-mfi/$it").let { file -> file.isFile && file.length() > 0 }
        }) {
            "CarPlay authentication files are missing or empty in $directory" +
                if (overridden) " (from DIPLAY_AUTH_ASSETS_DIR)" else " (committed auth-assets)"
        }
    }
}
tasks.named("preBuild") { mustRunAfter(verifyStandaloneAuthentication) }
tasks.register("assembleStandaloneDebug") {
    group = "build"
    description = "Build a standalone car-test APK with explicitly provisioned authentication."
    dependsOn(verifyStandaloneAuthentication, "assembleDebug")
}
