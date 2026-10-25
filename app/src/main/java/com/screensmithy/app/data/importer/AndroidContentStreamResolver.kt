package com.screensmithy.app.data.importer

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import com.screensmithy.app.domain.importer.ContentStreamResolver
import com.screensmithy.app.domain.importer.DocumentDescriptor
import java.io.InputStream

/**
 * Android framework implementation of [ContentStreamResolver] using [ContentResolver].
 */
class AndroidContentStreamResolver(
    private val contentResolver: ContentResolver
) : ContentStreamResolver {

    override fun queryDescriptor(uriString: String): DocumentDescriptor? {
        val uri = Uri.parse(uriString)
        var name: String? = null
        var size: Long? = null

        val projection = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1) {
                    name = cursor.getString(nameIndex)
                }

                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex != -1 && !cursor.isNull(sizeIndex)) {
                    size = cursor.getLong(sizeIndex)
                }
            }
        }

        return if (name != null || size != null) {
            DocumentDescriptor(displayName = name, sizeBytes = size)
        } else {
            null
        }
    }

    override fun openInputStream(uriString: String): InputStream? {
        val uri = Uri.parse(uriString)
        return contentResolver.openInputStream(uri)
    }
}
