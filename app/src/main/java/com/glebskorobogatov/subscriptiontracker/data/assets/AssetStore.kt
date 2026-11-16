package com.glebskorobogatov.subscriptiontracker.data.assets

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import com.glebskorobogatov.subscriptiontracker.app.ActiveDataset
import com.glebskorobogatov.subscriptiontracker.data.room.AssetEntity
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** All paths are owned by this app and one dataset. No gallery URI is retained. */
class AssetStore(private val context: Context, val generation: UUID) {
    private val root = File(context.filesDir, "assets/$generation")
    private val active = ActiveDataset(context.filesDir)
    private fun guard() { check(active.getOrCreate() == generation) { "Dataset changed" } }
    private fun file(id: UUID, extension: String, pending: Boolean) =
        File(File(root, if (pending) "pending" else "images"), "$id.$extension")

    suspend fun stage(uri: Uri): UUID = withContext(Dispatchers.IO) {
        guard()
        root.mkdirs()
        val source = File(root, "${UUID.randomUUID()}.source")
        val id = UUID.randomUUID()
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(source).use { output ->
                    val buffer = ByteArray(32 * 1024)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        require(total <= 10L * 1024 * 1024) { "Image too large" }
                        output.write(buffer, 0, read)
                    }
                    require(total > 0) { "Empty image" }
                }
            } ?: error("Image unavailable")
            val signature = source.inputStream().use { input ->
                val header = ByteArray(12)
                var count = 0
                while (count < header.size) {
                    val read = input.read(header, count, header.size - count)
                    if (read < 0) break
                    count += read
                }
                header.copyOf(count)
            }
            val jpeg = signature.size > 2 && signature[0] == 0xff.toByte() && signature[1] == 0xd8.toByte()
            val png = signature.size > 7 && signature.copyOfRange(0, 8).contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a))
            val webp = signature.size == 12 && String(signature, 0, 4) == "RIFF" && String(signature, 8, 4) == "WEBP"
            require(jpeg || png || webp) { "Unsupported image" }
            when (context.contentResolver.getType(uri)?.lowercase()) {
                "image/jpeg", "image/jpg" -> require(jpeg) { "Image type mismatch" }
                "image/png" -> require(png) { "Image type mismatch" }
                "image/webp" -> require(webp) { "Image type mismatch" }
            }
            val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(source)) { decoder, info, _ ->
                val width = info.size.width; val height = info.size.height
                require(width > 0 && height > 0 && width.toLong() * height <= 40_000_000L) { "Image dimensions too large" }
                val scale = minOf(1.0, 256.0 / maxOf(width, height))
                decoder.setTargetSize(maxOf(1, (width * scale).toInt()), maxOf(1, (height * scale).toInt()))
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
            val extension = if (bitmap.hasAlpha()) "png" else "jpg"
            val target = file(id, extension, true)
            target.parentFile!!.mkdirs()
            val partial = File(target.path + ".tmp")
            try {
                FileOutputStream(partial).use { output ->
                    check(bitmap.compress(if (extension == "png") Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 85, output))
                    output.fd.sync()
                }
                check(partial.length() in 1..1_048_576L) { "Normalized image too large" }
                guard()
                check(partial.renameTo(target)) { "Cannot finalize image" }
                id
            } finally { partial.delete(); bitmap.recycle() }
        } finally { source.delete() }
    }

    fun prepared(id: UUID): AssetEntity {
        guard()
        val pending = listOf("png", "jpg").map { file(id, it, true) }.singleOrNull { it.isFile } ?: error("Image draft unavailable")
        val final = file(id, pending.extension, false)
        final.parentFile!!.mkdirs()
        if (!final.exists()) {
            val partial = File(final.path + ".tmp")
            try {
                pending.inputStream().use { input ->
                    FileOutputStream(partial).use { output -> input.copyTo(output); output.fd.sync() }
                }
                check(partial.renameTo(final)) { "Cannot finalize image" }
            } finally { partial.delete() }
        }
        val hash = MessageDigest.getInstance("SHA-256").digest(final.readBytes()).joinToString("") { "%02x".format(it) }
        return AssetEntity(id, "assets/$generation/images/${final.name}",
            if (final.extension == "png") "image/png" else "image/jpeg", final.length(), hash, Instant.now())
    }

    fun pending(id: UUID): File? { guard(); return listOf("png", "jpg").map { file(id, it, true) }.firstOrNull { it.isFile } }

    /** Grace period protects drafts restored after process death. This never crosses datasets. */
    fun sweep(knownPaths: Set<String>, now: Long = System.currentTimeMillis()) {
        guard()
        val cutoff = now - 7L * 24 * 60 * 60 * 1000
        listOf(File(root, "pending"), File(root, "images"), root).forEach { dir ->
            dir.listFiles()?.filter { file -> file.isFile && file.lastModified() < cutoff &&
                (dir != File(root, "images") || "assets/$generation/images/${file.name}" !in knownPaths) &&
                (dir != root || file.extension == "source")
            }?.forEach { it.delete() }
        }
    }

    fun finish(id: UUID) { listOf("png", "jpg").forEach { file(id, it, true).delete() } }
    fun abort(id: UUID) { finish(id); listOf("png", "jpg").forEach { file(id, it, false).delete() } }
    fun removeFile(asset: AssetEntity) {
        require(asset.relativePath.startsWith("assets/$generation/images/"))
        val file = File(context.filesDir, asset.relativePath)
        require(file.canonicalFile.parentFile == File(root, "images").canonicalFile)
        file.delete()
    }
    fun readable(asset: AssetEntity): File? {
        guard()
        if (!asset.relativePath.startsWith("assets/$generation/images/")) return null
        val candidate = File(context.filesDir, asset.relativePath)
        return candidate.takeIf { it.canonicalFile.parentFile == File(root, "images").canonicalFile && it.isFile }
    }
}
