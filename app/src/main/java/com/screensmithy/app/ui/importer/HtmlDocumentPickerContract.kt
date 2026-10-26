package com.screensmithy.app.ui.importer

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContract

/**
 * Storage Access Framework contract to pick a single HTML document (.html or .htm).
 * Uses [Intent.ACTION_OPEN_DOCUMENT] with openable category and HTML MIME types,
 * avoiding broad runtime storage permissions.
 */
class HtmlDocumentPickerContract : ActivityResultContract<Unit, Uri?>() {

    override fun createIntent(context: Context, input: Unit): Intent {
        return Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = MIME_TYPE_HTML
            putExtra(Intent.EXTRA_MIME_TYPES, ALLOWED_MIME_TYPES)
        }
    }

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? {
        return if (resultCode == Activity.RESULT_OK) {
            intent?.data
        } else {
            null
        }
    }

    companion object {
        const val MIME_TYPE_HTML = "text/html"
        val ALLOWED_MIME_TYPES = arrayOf("text/html", "application/xhtml+xml")

        /**
         * Checks whether an application is installed that can handle the document picker intent.
         * On Google TV / Android TV, DocumentsUI is typically missing, and instead
         * com.android.tv.frameworkpackagestubs (Stubs$DocumentsStub) is registered,
         * which merely displays "You don't have an app that can do this."
         * We detect this stub to avoid invoking it.
         */
        fun isAvailable(context: Context): Boolean {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = MIME_TYPE_HTML
                putExtra(Intent.EXTRA_MIME_TYPES, ALLOWED_MIME_TYPES)
            }
            val resolveInfo = context.packageManager.resolveActivity(intent, 0) ?: return false
            val pkg = resolveInfo.activityInfo?.packageName.orEmpty()
            val cls = resolveInfo.activityInfo?.name.orEmpty()
            if (pkg.contains("frameworkpackagestubs", ignoreCase = true) ||
                cls.contains("Stub", ignoreCase = true)
            ) {
                return false
            }
            return true
        }
    }
}
