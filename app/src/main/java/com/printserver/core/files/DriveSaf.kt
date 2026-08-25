package com.printserver.core.files

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import com.printserver.core.common.PrinterLog
import java.io.File

object DriveSaf {
    private const val TAG = "DriveSaf"

    fun persist(context: Context, treeUri: Uri): Boolean = runCatching {
        context.contentResolver.takePersistableUriPermission(
            treeUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        true
    }.getOrElse { PrinterLog.w(TAG, "persist failed: ${it.message}"); false }

    fun parentDoc(treeUri: Uri): Uri =
        DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))

    fun childNames(context: Context, treeUri: Uri): Map<String, Pair<String, Long>> {
        val out = HashMap<String, Pair<String, Long>>()
        runCatching {
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                treeUri, DocumentsContract.getTreeDocumentId(treeUri)
            )
            context.contentResolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_SIZE,
                ),
                null, null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    val size = c.getLong(2)
                    out[name] = id to size
                }
            }
        }
        return out
    }

    fun writeBytes(context: Context, treeUri: Uri, name: String, mime: String, bytes: ByteArray): Boolean {
        val existing = childNames(context, treeUri)[name]
        val docUri = if (existing != null) {
            if (existing.second == bytes.size.toLong()) {
                PrinterLog.d(TAG, "skip (same size): $name")
                return true
            }
            DocumentsContract.buildDocumentUriUsingTree(treeUri, existing.first)
        } else {
            runCatching {
                DocumentsContract.createDocument(context.contentResolver, parentDoc(treeUri), mime, name)
            }.getOrElse { PrinterLog.w(TAG, "create failed $name: ${it.message}"); null } ?: return false
        }
        return runCatching {
            context.contentResolver.openOutputStream(docUri, "w")?.use { it.write(bytes) } ?: return false
            true
        }.getOrElse { PrinterLog.w(TAG, "write failed $name: ${it.message}"); false }
    }

    fun writeAppend(context: Context, treeUri: Uri, name: String, mime: String, src: File): Boolean =
        runCatching {
            val bytes = src.readBytes()
            writeBytes(context, treeUri, name, mime, bytes)
        }.getOrDefault(false)
}
