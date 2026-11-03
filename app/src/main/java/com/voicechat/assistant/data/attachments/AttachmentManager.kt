package com.voicechat.assistant.data.attachments

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import com.voicechat.assistant.core.AppLog
import com.voicechat.assistant.core.LogCategory
import com.voicechat.assistant.data.model.Attachment
import com.voicechat.assistant.data.model.AttachmentKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import java.util.UUID

/**
 * Copies user-selected content into app-private storage so conversations keep
 * working after the picker's URI grant expires, and prepares images for the
 * realtime image buffer (JPEG, base64, < 256 KB).
 */
class AttachmentManager(private val context: Context) {

    private val directory: File
        get() = File(context.filesDir, "attachments").apply { if (!exists()) mkdirs() }

    fun createCameraTarget(): Pair<Uri, File> {
        val dir = File(context.cacheDir, "images").apply { if (!exists()) mkdirs() }
        val file = File(dir, "cam_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        return uri to file
    }

    suspend fun importCameraPhoto(file: File): Attachment? = withContext(Dispatchers.IO) {
        runCatching {
            val target = File(directory, "${UUID.randomUUID()}.jpg")
            file.copyTo(target, overwrite = true)
            file.delete()
            Attachment(
                path = target.absolutePath,
                mimeType = "image/jpeg",
                name = "Camera photo",
                kind = AttachmentKind.IMAGE,
            )
        }.onFailure {
            AppLog.warn(LogCategory.UI, "Failed to import camera photo: ${it.message}")
        }.getOrNull()
    }

    suspend fun importUri(uri: Uri): Attachment? = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            val mime = resolver.getType(uri) ?: guessMime(uri)
            val displayName = queryName(uri) ?: "attachment"
            val extension = extensionFor(mime, displayName)
            val target = File(directory, "${UUID.randomUUID()}.$extension")
            resolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: error("Could not open $uri")
            val kind = if (mime.startsWith("image/")) AttachmentKind.IMAGE else AttachmentKind.DOCUMENT
            Attachment(
                path = target.absolutePath,
                mimeType = mime,
                name = displayName,
                kind = kind,
            )
        }.onFailure {
            AppLog.warn(LogCategory.UI, "Failed to import attachment: ${it.message}")
        }.getOrNull()
    }

    /**
     * Returns a base64 JPEG under [maxBytes]. Returns null when the file is not
     * a decodable image. The vendor limit is 256 KB encoded; we stay under.
     */
    suspend fun imageBase64(attachment: Attachment, maxBytes: Int = 240_000): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val original = decodeSampled(attachment.path, 2048) ?: return@runCatching null
                val rotated = applyExifRotation(attachment.path, original)
                var scaled = scaleToFit(rotated, 1280)
                var quality = 80
                var bytes = compress(scaled, quality)
                while (bytes.size > maxBytes && quality > 35) {
                    quality -= 10
                    bytes = compress(scaled, quality)
                }
                while (bytes.size > maxBytes && scaled.width > 320) {
                    scaled = scaleToFit(scaled, (scaled.width * 3) / 4)
                    bytes = compress(scaled, quality)
                }
                if (bytes.size > maxBytes) {
                    AppLog.warn(
                        LogCategory.UI,
                        "Image still too large after compression: ${bytes.size} bytes",
                    )
                    null
                } else {
                    Base64.getEncoder().encodeToString(bytes)
                }
            }.onFailure {
                AppLog.warn(LogCategory.UI, "Image processing failed: ${it.message}")
            }.getOrNull()
        }

    suspend fun documentText(attachment: Attachment, limit: Int = 24_000): String? =
        withContext(Dispatchers.IO) {
            val file = File(attachment.path)
            if (!file.exists()) return@withContext null
            if (!isTextLike(attachment)) return@withContext null
            runCatching {
                file.readText().take(limit)
            }.getOrNull()
        }

    fun isTextLike(attachment: Attachment): Boolean {
        val mime = attachment.mimeType.lowercase()
        if (mime.startsWith("text/")) return true
        if (mime.contains("json") || mime.contains("xml") || mime.contains("csv")) return true
        val extension = attachment.name.substringAfterLast('.', "").lowercase()
        return extension in TEXT_EXTENSIONS
    }

    private fun queryName(uri: Uri): String? =
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
        }.getOrNull()

    private fun guessMime(uri: Uri): String =
        when (uri.lastPathSegment?.substringAfterLast('.', "")?.lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "pdf" -> "application/pdf"
            "txt" -> "text/plain"
            "md" -> "text/markdown"
            "json" -> "application/json"
            "csv" -> "text/csv"
            else -> "application/octet-stream"
        }

    private fun extensionFor(mime: String, name: String): String {
        val fromName = name.substringAfterLast('.', "").lowercase()
        if (fromName.isNotBlank() && fromName.length <= 6) return fromName
        return when {
            mime.contains("jpeg") -> "jpg"
            mime.contains("png") -> "png"
            mime.contains("webp") -> "webp"
            mime.contains("pdf") -> "pdf"
            mime.startsWith("text/") -> "txt"
            else -> "bin"
        }
    }

    private fun decodeSampled(path: String, maxDimension: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxDimension) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeFile(path, options)
    }

    private fun applyExifRotation(path: String, bitmap: Bitmap): Bitmap {        val orientation = runCatching {
            ExifInterface(path).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (degrees == 0f) return bitmap
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun scaleToFit(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val largest = maxOf(bitmap.width, bitmap.height)
        if (largest <= maxDimension) return bitmap
        val ratio = maxDimension.toFloat() / largest
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * ratio).toInt().coerceAtLeast(1),
            (bitmap.height * ratio).toInt().coerceAtLeast(1),
            true,
        )
    }

    private fun compress(bitmap: Bitmap, quality: Int): ByteArray =
        ByteArrayOutputStream().use { stream ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
            stream.toByteArray()
        }

    private companion object {
        val TEXT_EXTENSIONS = setOf("txt", "md", "markdown", "json", "csv", "log", "yaml", "yml", "kt", "java", "xml")
    }
}
