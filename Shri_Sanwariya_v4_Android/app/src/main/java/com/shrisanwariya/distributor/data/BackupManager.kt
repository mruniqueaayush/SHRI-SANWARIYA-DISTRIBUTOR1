package com.shrisanwariya.distributor.data

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.shrisanwariya.distributor.model.AppBackupPayload
import com.shrisanwariya.distributor.model.BackupManifest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Fixes the v3 cross-device export/import corruption issue:
 * 1. Writes canonical UTF-8 backup.json + manifest.json (with SHA-256 checksum) + portable images/<id>.jpg
 * 2. Uses ContentResolver.openOutputStream(uri, "wt") with explicit flush/close to prevent truncated ZIPs
 * 3. Copies restored images into internal filesDir/product_images/ (never relies on device-local content:// URIs)
 * 4. Accepts BOTH .zip archives AND .json backups (compatible with the Shri Sanwariya Web App)
 */
class BackupManager(private val context: Context) {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val imagesDir: File
        get() = File(context.filesDir, "product_images").apply { mkdirs() }

    data class BackupResult(
        val success: Boolean,
        val message: String,
        val payload: AppBackupPayload? = null
    )

    suspend fun exportBackupToUri(uri: Uri, payload: AppBackupPayload): BackupResult =
        withContext(Dispatchers.IO) {
            try {
                // Collect portable image files referenced by products
                val imageFiles = mutableMapOf<String, ByteArray>()
                val normalizedProducts = payload.products.map { product ->
                    val rel = product.imageRelativePath
                    if (!rel.isNullOrBlank()) {
                        val fileName = File(rel).name
                        val localFile = File(imagesDir, fileName)
                        if (localFile.exists() && localFile.length() > 0) {
                            imageFiles["images/$fileName"] = localFile.readBytes()
                            product.copy(imageRelativePath = "images/$fileName")
                        } else {
                            product.copy(imageRelativePath = null)
                        }
                    } else product
                }

                val cleanPayload = payload.copy(
                    schemaVersion = 4,
                    exportedAt = System.currentTimeMillis(),
                    products = normalizedProducts,
                    embeddedImagesBase64 = emptyMap() // Stored as binary entries inside ZIP
                )

                val backupJsonBytes = json.encodeToString(cleanPayload).toByteArray(Charsets.UTF_8)
                val sha256 = sha256Hex(backupJsonBytes)
                val manifest = BackupManifest(
                    schemaVersion = 4,
                    exportedAt = cleanPayload.exportedAt,
                    sha256BackupJson = sha256,
                    productCount = cleanPayload.products.size,
                    orderCount = cleanPayload.orders.size,
                    imageCount = imageFiles.size
                )
                val manifestBytes = json.encodeToString(manifest).toByteArray(Charsets.UTF_8)

                // Open with "wt" (write + truncate) to guarantee no trailing garbage bytes from prior files
                val outputStream = context.contentResolver.openOutputStream(uri, "wt")
                    ?: return@withContext BackupResult(false, "Could not open destination file for writing.")

                outputStream.use { rawOut ->
                    ZipOutputStream(BufferedOutputStream(rawOut)).use { zos ->
                        zos.putNextEntry(ZipEntry("manifest.json"))
                        zos.write(manifestBytes)
                        zos.closeEntry()

                        zos.putNextEntry(ZipEntry("backup.json"))
                        zos.write(backupJsonBytes)
                        zos.closeEntry()

                        imageFiles.forEach { (entryPath, bytes) ->
                            zos.putNextEntry(ZipEntry(entryPath))
                            zos.write(bytes)
                            zos.closeEntry()
                        }
                        zos.finish()
                        zos.flush()
                    }
                    rawOut.flush()
                }

                BackupResult(
                    success = true,
                    message = "Backup exported cleanly (${cleanPayload.products.size} products, ${cleanPayload.orders.size} orders, ${imageFiles.size} photos)."
                )
            } catch (e: Exception) {
                BackupResult(false, "Export failed: ${e.localizedMessage ?: "Unknown error"}")
            }
        }

    suspend fun importBackupFromUri(uri: Uri): BackupResult = withContext(Dispatchers.IO) {
        try {
            val rawBytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return@withContext BackupResult(false, "Could not open selected backup file.")

            if (rawBytes.isEmpty()) {
                return@withContext BackupResult(false, "Selected backup file is empty (0 bytes).")
            }

            // Detect ZIP magic header (PK) vs plain UTF-8 JSON
            val isZip = rawBytes.size >= 4 &&
                rawBytes[0] == 0x50.toByte() &&
                rawBytes[1] == 0x4B.toByte()

            if (!isZip) {
                // Direct JSON backup import (compatible with Web App exports)
                val text = rawBytes.toString(Charsets.UTF_8).trimStart('﻿').trim()
                val payload = json.decodeFromString<AppBackupPayload>(text)
                // Restore any base64-embedded images to internal storage
                val restoredProducts = payload.products.map { p ->
                    val b64 = payload.embeddedImagesBase64[p.id]
                    if (!b64.isNullOrBlank()) {
                        val cleanB64 = b64.substringAfter("base64,", b64)
                        val bytes = Base64.decode(cleanB64, Base64.DEFAULT)
                        val outFile = File(imagesDir, "${p.id}.jpg")
                        outFile.writeBytes(bytes)
                        p.copy(imageRelativePath = "images/${p.id}.jpg")
                    } else p
                }
                val finalPayload = payload.copy(products = restoredProducts, embeddedImagesBase64 = emptyMap())
                return@withContext BackupResult(
                    success = true,
                    message = "JSON backup restored (${finalPayload.products.size} products, ${finalPayload.orders.size} orders).",
                    payload = finalPayload
                )
            }

            // Extract ZIP entries safely
            var backupJsonBytes: ByteArray? = null
            var manifest: BackupManifest? = null
            val restoredImages = mutableSetOf<String>()

            ZipInputStream(BufferedInputStream(rawBytes.inputStream())).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val name = entry.name.replace("\\", "/")
                    if (!entry.isDirectory && !name.contains("..")) {
                        val entryBytes = readEntryBytes(zis)
                        when {
                            name.equals("backup.json", ignoreCase = true) ||
                                name.endsWith("/backup.json", ignoreCase = true) ||
                                (name.endsWith(".json", ignoreCase = true) && !name.contains("manifest")) -> {
                                backupJsonBytes = entryBytes
                            }
                            name.equals("manifest.json", ignoreCase = true) -> {
                                manifest = runCatching {
                                    json.decodeFromString<BackupManifest>(entryBytes.toString(Charsets.UTF_8))
                                }.getOrNull()
                            }
                            name.startsWith("images/") || name.endsWith(".jpg", true) || name.endsWith(".png", true) -> {
                                val fileName = File(name).name
                                if (fileName.isNotBlank()) {
                                    File(imagesDir, fileName).writeBytes(entryBytes)
                                    restoredImages.add("images/$fileName")
                                }
                            }
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }

            val jsonBytes = backupJsonBytes
                ?: return@withContext BackupResult(false, "Invalid backup ZIP: missing backup.json inside archive.")

            // Verify SHA-256 checksum if manifest is present
            manifest?.let { m ->
                if (m.sha256BackupJson.isNotBlank()) {
                    val actualHash = sha256Hex(jsonBytes)
                    if (!actualHash.equals(m.sha256BackupJson, ignoreCase = true)) {
                        return@withContext BackupResult(false, "Backup integrity check failed: file appears corrupted during transfer.")
                    }
                }
            }

            val decoded = json.decodeFromString<AppBackupPayload>(
                jsonBytes.toString(Charsets.UTF_8).trimStart('﻿').trim()
            )

            BackupResult(
                success = true,
                message = "Backup restored (${decoded.products.size} products, ${decoded.orders.size} orders, ${restoredImages.size} photos).",
                payload = decoded
            )
        } catch (e: Exception) {
            BackupResult(false, "Import failed: ${e.localizedMessage ?: "Unrecognized backup format"}")
        }
    }

    fun saveProductImageFromUri(productId: String, sourceUri: Uri): String? {
        return try {
            val bytes = context.contentResolver.openInputStream(sourceUri)?.use { it.readBytes() } ?: return null
            val outFile = File(imagesDir, "$productId.jpg")
            outFile.writeBytes(bytes)
            "images/$productId.jpg"
        } catch (_: Exception) {
            null
        }
    }

    fun resolveLocalImageFile(relativePath: String?): File? {
        if (relativePath.isNullOrBlank()) return null
        val file = File(imagesDir, File(relativePath).name)
        return if (file.exists()) file else null
    }

    private fun readEntryBytes(zis: ZipInputStream): ByteArray {
        val baos = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var count: Int
        while (zis.read(buffer).also { count = it } != -1) {
            baos.write(buffer, 0, count)
        }
        return baos.toByteArray()
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }
}
