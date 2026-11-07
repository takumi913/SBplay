package com.shilapi.xcertplay

import androidx.multidex.MultiDexApplication

/** Install the crash recorder before any Activity, service, or CarPlay initialization runs. */
class SBplayApplication : MultiDexApplication() {
    override fun onCreate() {
        super.onCreate()
        CrashDiagnostics.install(this)
    }
}
