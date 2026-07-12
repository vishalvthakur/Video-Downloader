package com.example.media

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream

object MediaStoreManager {
    private const val TAG = "MediaStoreManager"

    suspend fun saveVideoToGallery(
        context: Context,
        localFile: File,
        title: String,
        mimeType: String = "video/mp4"
    ): Uri? = withContext(Dispatchers.IO) {
        if (!localFile.exists() || localFile.length() == 0L) {
            Log.e(TAG, "Local source file is invalid.")
            return@withContext null
        }

        val resolver = context.contentResolver
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

        // Sanitize display name
        val sanitizedTitle = title.replace(Regex("[/:*?\"<>|]"), "_")
        val displayName = if (sanitizedTitle.length > 100) sanitizedTitle.take(100) else sanitizedTitle
        val fileName = "$displayName [${System.currentTimeMillis().toString().takeLast(6)}].mp4"

        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Video.Media.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/VideoDownloader")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }

        var uri: Uri? = null
        try {
            uri = resolver.insert(collection, values)
            if (uri == null) {
                Log.e(TAG, "Failed to insert MediaStore record.")
                return@withContext null
            }

            resolver.openOutputStream(uri).use { outputStream ->
                if (outputStream == null) {
                    Log.e(TAG, "Failed to open MediaStore output stream.")
                    return@withContext null
                }
                FileInputStream(localFile).use { inputStream ->
                    val buffer = ByteArray(16384)
                    var bytesRead: Int
                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        outputStream.write(buffer, 0, bytesRead)
                    }
                }
                outputStream.flush()
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }

            Log.d(TAG, "File successfully saved to MediaStore: $uri")
            
            // Delete original cache file safely
            try {
                localFile.delete()
            } catch (e: Exception) {
                Log.e(TAG, "Could not delete cached source file", e)
            }

            return@withContext uri
        } catch (e: Exception) {
            Log.e(TAG, "Error saving video to MediaStore", e)
            if (uri != null) {
                try {
                    resolver.delete(uri, null, null)
                } catch (ex: Exception) {
                    Log.e(TAG, "Clean up failed for broken MediaStore entry", ex)
                }
            }
            return@withContext null
        }
    }
}
