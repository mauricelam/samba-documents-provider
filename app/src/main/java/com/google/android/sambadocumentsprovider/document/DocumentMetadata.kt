/*
 * Copyright 2017 Google Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.google.android.sambadocumentsprovider.document

import android.net.Uri
import android.provider.DocumentsContract
import android.system.OsConstants
import android.system.StructStat
import android.util.Log
import android.webkit.MimeTypeMap
import com.google.android.sambadocumentsprovider.R
import com.google.android.sambadocumentsprovider.base.DirectoryEntry
import com.google.android.sambadocumentsprovider.getValue
import com.google.android.sambadocumentsprovider.nativefacade.SmbClient
import com.google.android.sambadocumentsprovider.setValue
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * This is a snapshot of the metadata of a seen document. It contains its SMB URI, display name,
 * access/create/modified time, size, its children etc sometime in the past. It also contains
 * the last exception thrown when querying its children.
 *
 *
 * The metadata inside this class may be fetched at different time due to Samba client API.
 */
class DocumentMetadata(var uri: Uri, private val entry: DirectoryEntry) {
    private var stat by AtomicReference<StructStat?>(null)
    var children by AtomicReference<MutableMap<Uri, DocumentMetadata>?>(null)

    private val lastChildUpdateException = AtomicReference<Exception?>(null)
    private val lastStatException = AtomicReference<Exception?>(null)
    var timeStamp: Long = System.currentTimeMillis()
        private set
    val isFileShare: Boolean
        get() = entry.type == DirectoryEntry.Type.FILE_SHARE

    val iconResourceId: Int?
        get() = when (entry.type) {
            DirectoryEntry.Type.SERVER -> R.drawable.ic_server
            DirectoryEntry.Type.FILE_SHARE -> R.drawable.ic_folder_shared
            else -> null  // Tells SAF to use the default icon.
        }
    val lastModified: Long?
        get() = stat?.let { stat ->
            TimeUnit.MILLISECONDS.convert(stat.st_mtime, TimeUnit.SECONDS)
        }
    val displayName: String?
        get() = entry.name
    val comment: String
        get() = entry.comment

    fun needsStat(): Boolean {
        return hasStat() && stat == null
    }

    private fun hasStat(): Boolean {
        return when (entry.type) {
            DirectoryEntry.Type.FILE -> true
            DirectoryEntry.Type.WORKGROUP, DirectoryEntry.Type.SERVER,
            DirectoryEntry.Type.FILE_SHARE, DirectoryEntry.Type.DIR -> {
                // Everything is writable so no need to fetch stats for them.
                false
            }
            else -> throw UnsupportedOperationException(
                "Unsupported type of Samba directory entry: ${entry.type}"
            )
        }
    }

    val size: Long?
        get() = stat?.st_size

    fun canCreateDocument(): Boolean {
        return when (entry.type) {
            DirectoryEntry.Type.DIR, DirectoryEntry.Type.FILE_SHARE -> true
            DirectoryEntry.Type.WORKGROUP, DirectoryEntry.Type.SERVER,
            DirectoryEntry.Type.FILE -> false
            else -> throw UnsupportedOperationException(
                "Unsupported type of Samba directory entry ${entry.type}"
            )
        }
    }

    val mimeType: String
        get() {
            return when (entry.type) {
                DirectoryEntry.Type.FILE_SHARE, DirectoryEntry.Type.WORKGROUP,
                DirectoryEntry.Type.SERVER, DirectoryEntry.Type.DIR -> {
                    DocumentsContract.Document.MIME_TYPE_DIR
                }
                DirectoryEntry.Type.LINK, DirectoryEntry.Type.COMMS_SHARE,
                DirectoryEntry.Type.IPC_SHARE, DirectoryEntry.Type.PRINTER_SHARE -> {
                    throw UnsupportedOperationException(
                        "Unsupported type of Samba directory entry ${entry.type}"
                    )
                }
                DirectoryEntry.Type.FILE -> {
                    val ext = getExtension(entry.name) ?: return GENERIC_MIME_TYPE
                    MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: GENERIC_MIME_TYPE
                }
            }
        }

    private fun getExtension(name: String?): String? {
        if (name.isNullOrEmpty()) {
            return null
        }
        val idxOfDot = name.lastIndexOf('.', name.length - 1)
        return if (idxOfDot <= 0) null else name.substring(idxOfDot + 1).lowercase()
    }

    fun reset() {
        stat = null
        children = null
    }

    @Throws(Exception::class)
    fun throwLastChildUpdateExceptionIfAny() {
        lastChildUpdateException.getAndSet(null)?.let { throw it }
    }

    fun hasLoadingStatFailed(): Boolean {
        return lastStatException.getAndSet(null) != null
    }

    fun rename(newUri: Uri) {
        entry.name = newUri.lastPathSegment
        uri = newUri
    }

    override fun toString(): String {
        return "DocumentMetadata{entry=$entry, uri=$uri, stat=$stat, " +
                "children=${this.children}, lastChildUpdateException=$lastChildUpdateException, " +
                "lastStatException=$lastStatException, timeStamp=$timeStamp}"
    }

    @Throws(IOException::class)
    fun loadChildren(client: SmbClient) {
        try {
            client.openDir(uri.toString()).use { dir ->
                val children = ConcurrentHashMap<Uri, DocumentMetadata>()
                generateSequence { dir.readDir() }.forEach { entry ->
                    val childUri = buildChildUri(uri, entry)
                    if (childUri != null) {
                        children[childUri] = DocumentMetadata(childUri, entry)
                    }
                }
                this.children = children
                timeStamp = System.currentTimeMillis()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load children.", e)
            lastChildUpdateException.set(e)
            throw e
        }
    }

    fun putChild(child: DocumentMetadata) {
        children?.put(child.uri, child)
    }

    @Throws(IOException::class)
    fun loadStat(client: SmbClient) {
        try {
            stat = client.stat(uri.toString())
            timeStamp = System.currentTimeMillis()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get stat.", e)
            lastStatException.set(e)
            throw e
        }
    }

    companion object {
        private const val TAG = "DocumentMetadata"
        private const val GENERIC_MIME_TYPE = "application/octet-stream"
        private val SMB_BASE_URI = Uri.parse("smb://")
        fun buildChildUri(parentUri: Uri, entry: DirectoryEntry): Uri? {
            return when (entry.type) {
                DirectoryEntry.Type.LINK, DirectoryEntry.Type.COMMS_SHARE,
                DirectoryEntry.Type.IPC_SHARE, DirectoryEntry.Type.PRINTER_SHARE -> {
                    Log.i(
                        TAG, "Found unsupported type: ${entry.type} name: ${entry.name} " +
                                "comment: ${entry.comment}"
                    )
                    null
                }
                DirectoryEntry.Type.WORKGROUP, DirectoryEntry.Type.SERVER -> {
                    SMB_BASE_URI.buildUpon().authority(entry.name).build()
                }
                DirectoryEntry.Type.FILE_SHARE, DirectoryEntry.Type.DIR, DirectoryEntry.Type.FILE -> {
                    buildChildUri(parentUri, entry.name)
                }
            }
        }

        fun buildChildUri(parentUri: Uri, displayName: String?): Uri? {
            return if ("." == displayName || ".." == displayName) {
                null
            } else {
                parentUri.buildUpon().appendPath(displayName).build()
            }
        }

        fun buildParentUri(childUri: Uri): Uri {
            val segments = childUri.pathSegments
            if (segments.isEmpty()) {
                // This is possibly a server or a workgroup. We don't know its exact parent, so just
                // return "smb://".
                return SMB_BASE_URI
            }
            val builder = SMB_BASE_URI.buildUpon().authority(childUri.authority)
            for (segment in segments) {
                builder.appendPath(segment)
            }
            return builder.build()
        }

        fun isServerUri(uri: Uri): Boolean {
            return uri.pathSegments.isEmpty() && uri.authority?.isNotEmpty() == true
        }

        fun isShareUri(uri: Uri): Boolean {
            return uri.pathSegments.size == 1
        }

        @Throws(IOException::class)
        fun fromUri(uri: Uri, client: SmbClient): DocumentMetadata {
            val pathSegments = uri.pathSegments
            if (pathSegments.isEmpty()) {
                throw UnsupportedOperationException("Can't load metadata for workgroup or server.")
            }
            val stat = client.stat(uri.toString())
            val entry = DirectoryEntry(
                if (OsConstants.S_ISDIR(stat.st_mode)) DirectoryEntry.Type.DIR else DirectoryEntry.Type.FILE,
                comment = "",
                uri.lastPathSegment
            )
            return DocumentMetadata(uri, entry).apply {
                this.stat = stat
            }
        }

        fun createShare(host: String, share: String): DocumentMetadata {
            val uri = SMB_BASE_URI.buildUpon().authority(host).encodedPath(share).build()
            return createShare(uri)
        }

        fun createServer(uri: Uri): DocumentMetadata = create(uri, DirectoryEntry.Type.SERVER)
        fun createShare(uri: Uri): DocumentMetadata = create(uri, DirectoryEntry.Type.FILE_SHARE)

        private fun create(uri: Uri, type: DirectoryEntry.Type): DocumentMetadata {
            val entry = DirectoryEntry(type, comment = "", uri.lastPathSegment)
            return DocumentMetadata(uri, entry)
        }
    }
}