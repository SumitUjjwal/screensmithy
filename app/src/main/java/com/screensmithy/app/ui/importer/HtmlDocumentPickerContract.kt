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
    }
}
