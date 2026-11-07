package com.shilapi.xcertplay

import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.core.app.ActivityOptionsCompat
import com.shilapi.xcertplay.host.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.util.ReflectionHelpers
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "en", shadows = [FileProviderPathTestShadow::class])
class DiagnosticExportUiTest {
    @Test fun missingPickerSavesAReportAndShowsTextWithoutBrokenSelectionPopup() {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        val activity = controller.get()
        val context = activity.applicationContext
        val authority = "${context.packageName}.diagnostic-reports"
        val info = context.packageManager.resolveContentProvider(authority, PackageManager.GET_META_DATA)!!
        ShadowContentResolver.registerProviderInternal(authority, DiagnosticReportProvider().apply { attachInfo(context, info) })
        val missingPicker = object : ActivityResultLauncher<String>() {
            override fun launch(input: String, options: ActivityOptionsCompat?) {
                throw ActivityNotFoundException("No DocumentsUI")
            }
            override fun unregister() = Unit
            override fun getContract() = androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/plain")
        }
        ReflectionHelpers.setField(activity, "export", missingPicker)
        try {
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "chooseReportDestination")
            val deadline = System.nanoTime() + 5_000_000_000L
            while (ShadowAlertDialog.getLatestAlertDialog() == null && System.nanoTime() < deadline) {
                Thread.sleep(20)
                shadowOf(Looper.getMainLooper()).idle()
            }
            val saved = requireNotNull(ShadowAlertDialog.getLatestAlertDialog())
            val reports = File(context.getExternalFilesDir(null)!!, "diagnostic-reports")
            val file = reports.listFiles()!!.single()
            assertTrue(file.name.endsWith(".txt"))
            assertTrue(descendants(saved.window!!.decorView).filterIsInstance<TextView>()
                .any { it.text.contains(file.absolutePath) })
            assertTrue(file.readText().contains("Android 9 / API 19"))
            saved.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            val viewer = ShadowAlertDialog.getLatestAlertDialog()
            // CS11 Android 4.4.3 crashes when long-press selection inflates the ROM's
            // broken ActionPopupWindow. Diagnostic text must remain readable but
            // must not enable the platform text-selection popup.
            val reportViews = descendants(viewer.window!!.decorView).filterIsInstance<TextView>()
                .filter { it.text.contains("Android 9 / API 19") }.toList()
            assertTrue(reportViews.isNotEmpty())
            assertTrue(reportViews.none { it.isTextSelectable })
            viewer.dismiss()
        } finally {
            controller.pause().stop().destroy()
        }
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
}
