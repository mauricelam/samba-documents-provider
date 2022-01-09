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
package com.google.android.sambadocumentsprovider.provider

import android.app.AuthenticationRequiredException
import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Build.VERSION.SDK_INT
import android.os.Build.VERSION_CODES
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import android.util.Log
import androidx.annotation.StringRes
import com.google.android.sambadocumentsprovider.*
import com.google.android.sambadocumentsprovider.auth.AuthActivity.Companion.createAuthIntent
import com.google.android.sambadocumentsprovider.base.AuthFailedException
import com.google.android.sambadocumentsprovider.base.DirectoryEntry
import com.google.android.sambadocumentsprovider.base.DocumentCursor
import com.google.android.sambadocumentsprovider.base.DocumentIdHelper.toDocumentId
import com.google.android.sambadocumentsprovider.base.DocumentIdHelper.toRootId
import com.google.android.sambadocumentsprovider.base.DocumentIdHelper.toUri
import com.google.android.sambadocumentsprovider.base.DocumentIdHelper.toUriString
import com.google.android.sambadocumentsprovider.cache.CacheResult
import com.google.android.sambadocumentsprovider.cache.DocumentCache
import com.google.android.sambadocumentsprovider.document.DocumentMetadata
import com.google.android.sambadocumentsprovider.nativefacade.SmbClient
import com.google.android.sambadocumentsprovider.nativefacade.SmbFacade
import kotlinx.coroutines.*
import java.io.FileNotFoundException
import java.io.IOException
import java.util.*

class SambaDocumentsProvider : DocumentsProvider() {
    private lateinit var shareManager: ShareManager
    private lateinit var smbFacade: SmbFacade
    private lateinit var bufferPool: ByteBufferPool
    private lateinit var cache: DocumentCache
    private lateinit var taskManager: TaskManager
    private lateinit var storageManager: StorageManager
    private lateinit var providerContext: Context
    private val contentResolver
        get() = providerContext.contentResolver

    override fun onCreate(): Boolean {
        providerContext = context!!
        Components.initialize(providerContext)
        smbFacade = Components.sambaClient
        cache = Components.documentCache
        taskManager = Components.taskManager
        bufferPool = ByteBufferPool()
        shareManager = Components.shareManager
        shareManager.addListener {
            val rootsUri = DocumentsContract.buildRootsUri(AUTHORITY)
            contentResolver.notifyChange(rootsUri, null, 0)
        }
        storageManager = providerContext.getSystemService(StorageManager::class.java)
        return true
    }

    @Throws(FileNotFoundException::class)
    override fun queryRoots(proj: Array<String>?): Cursor {
        // TODO: Add a virtual root for discovering local SMB shares
        if (BuildConfig.DEBUG) Log.d(TAG, "Querying roots.")
        val projection = proj ?: DEFAULT_ROOT_PROJECTION
        val cursor = MatrixCursor(projection)
        for (uri in shareManager.getShareUris()) {
            if (!shareManager.isShareMounted(uri)) {
                continue
            }
            val parsedUri = Uri.parse(uri)
            cache[parsedUri].use { result ->
                val metadata = if (result.state == CacheResult.State.CACHE_MISS) {
                    DocumentMetadata.createShare(parsedUri).also { cache.put(it) }
                } else {
                    result.item
                }
                cursor.addRow(
                    arrayOf<Any>(
                        toRootId(metadata),
                        toDocumentId(parsedUri),
                        metadata.displayName!!,
                        DocumentsContract.Root.FLAG_SUPPORTS_CREATE
                                or DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD
                                or if (SDK_INT >= VERSION_CODES.O) {
                            DocumentsContract.Root.FLAG_SUPPORTS_EJECT
                        } else 0,
                        R.drawable.ic_folder_shared
                    )
                )
            }
        }
        return cursor
    }

    override fun ejectRoot(rootId: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, "Ejecting root: $rootId")
        check(shareManager.unmountServer(rootId)) { "Failed to eject root: $rootId" }
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        val parentUri = toUriString(parentDocumentId)
        val childUri = toUriString(documentId)
        return childUri.startsWith(parentUri)
    }

    @Throws(FileNotFoundException::class)
    override fun queryDocument(documentId: String, proj: Array<String>?): Cursor {
        if (BuildConfig.DEBUG) Log.d(TAG, "Querying document: $documentId")
        val projection = proj ?: DEFAULT_DOCUMENT_PROJECTION
        val cursor = MatrixCursor(projection)
        val uri = toUri(documentId)
        try {
            cache[uri].use { result ->
                val metadata: DocumentMetadata
                if (result.state == CacheResult.State.CACHE_MISS) {
                    metadata = if (shareManager.containsShare(uri.toString())) {
                        DocumentMetadata.createShare(uri)
                    } else {
                        // There is no cache for this URI. Fetch it from remote side.
                        DocumentMetadata.fromUri(uri, smbFacade)
                    }
                    cache.put(metadata)
                } else {
                    metadata = result.item
                }
                cursor.addRow(getDocumentValues(projection, metadata))
                return cursor
            }
        } catch (e: Exception) {
            when (e) {
                is FileNotFoundException -> throw e
                is RuntimeException -> throw e
                else -> throw IllegalStateException(e)
            }
        }
    }

    @Throws(FileNotFoundException::class, AuthenticationRequiredException::class)
    override fun queryChildDocuments(
        documentId: String,
        proj: Array<String>?,
        sortOrder: String
    ): Cursor {
        if (BuildConfig.DEBUG) Log.d(TAG, "Querying children documents under $documentId")
        val projection = proj ?: DEFAULT_DOCUMENT_PROJECTION
        val uri = toUri(documentId)
        try {
            if (DocumentMetadata.isServerUri(uri)) {
                cache[uri].use { result ->
                    if (result.state == CacheResult.State.CACHE_MISS) {
                        val metadata = DocumentMetadata.createServer(uri)
                        cache.put(metadata)
                    }
                }
            }
            val cursor = DocumentCursor(projection)
            val notifyUri = toNotifyUri(uri)
            cache[uri].use { result ->
                if (result.state == CacheResult.State.CACHE_MISS) {
                    // Last loading failed... Just feed the bitter fruit.
                    cache.throwLastExceptionIfAny(uri)
                    runBlocking {
                        loadDocument(uri)
                    }
                }
            }
            cache[uri].use { result ->
                var isLoading = false
                val metadata = result.item
                require(DocumentsContract.Document.MIME_TYPE_DIR == metadata.mimeType) {
                    "$documentId is not a folder."
                }
                metadata.throwLastChildUpdateExceptionIfAny()
                if (metadata.children == null || result.state == CacheResult.State.CACHE_EXPIRED) {
                    runBlocking {
                        taskManager.runTask(uri) {
                            loadChildren(metadata)
                        }
                    }
                }

                val childrenMap = metadata.children
                // Still return something even if the cache expired.
                if (childrenMap != null) {
                    val docMap: MutableMap<Uri, DocumentMetadata> = HashMap()
                    for (child in childrenMap.values) {
                        if (child.needsStat() && !child.hasLoadingStatFailed()) {
                            docMap[child.uri] = child
                        }
                        cursor.addRow(getDocumentValues(projection, child))
                    }
                    if (!isLoading && docMap.isNotEmpty()) {
                        val job = lifecycleScope.launch {
                            taskManager.runTask(uri) {
                                loadStat(docMap)
                            }
                            contentResolver.notifyChange(notifyUri, null, NOTIFY_UPDATE)
                        }
                        cursor.loadingJob = job
                        isLoading = true
                    }
                }
                cursor.extras = Bundle().apply {
                    putBoolean(DocumentsContract.EXTRA_LOADING, isLoading)
                }
                cursor.setNotificationUri(providerContext.contentResolver, notifyUri)
                return cursor
            }
        } catch (e: AuthFailedException) {
            if (SDK_INT >= VERSION_CODES.O && DocumentMetadata.isShareUri(uri)) {
                throw AuthenticationRequiredException(
                    e, createAuthIntent(providerContext, uri.toString())
                )
            } else {
                return buildErrorCursor(projection, R.string.view_folder_denied)
            }
        } catch (e: Exception) {
            when (e) {
                is FileNotFoundException -> throw e
                is RuntimeException -> throw e
                else -> throw IllegalStateException(e)
            }
        }
    }

    private suspend fun loadDocument(uri: Uri) {
        Log.d(TAG, "loadDocument(): uri = $uri")
        @Suppress("BlockingMethodInNonBlockingContext")
        return withContext(Dispatchers.IO + NonCancellable) {
            try {
                val documentMetadata = DocumentMetadata.fromUri(uri, smbFacade)
                Log.d(TAG, "Loaded document $documentMetadata")
                cache.put(documentMetadata)
            } catch (e: Exception) {
                cache.put(uri, e)
                throw e
            }
        }
    }

    private suspend fun loadChildren(metadata: DocumentMetadata) {
        @Suppress("BlockingMethodInNonBlockingContext")
        return withContext(Dispatchers.IO + NonCancellable) {
            metadata.loadChildren(smbFacade)
            metadata.children?.values?.forEach {
                cache.put(metadata)
            }
        }
    }

    private suspend fun loadStat(metadataMap: Map<Uri, DocumentMetadata>) {
        @Suppress("BlockingMethodInNonBlockingContext")
        withContext(Dispatchers.IO) {
            for (metadata in metadataMap.values) {
                try {
                    metadata.loadStat(smbFacade)
                    if (!isActive) {
                        break
                    }
                } catch (e: Exception) {
                    // Failed to load a stat for a child... Just eat this exception, the only
                    // consequence it may have is constantly retrying to fetch the stat.
                    Log.e(TAG, "Failed to load stat for ${metadata.uri}")
                }
            }
        }
    }

    private fun buildErrorCursor(projection: Array<String>, @StringRes resId: Int): Cursor {
        return DocumentCursor(projection).apply {
            extras = Bundle().apply {
                putString(DocumentsContract.EXTRA_ERROR, providerContext.getString(resId))
            }
        }
    }

    private fun getDocumentValues(
        projection: Array<String>?, metadata: DocumentMetadata
    ): Array<Any?> {
        return projection?.map { col ->
            when (col) {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID -> toDocumentId(metadata.uri)
                DocumentsContract.Document.COLUMN_DISPLAY_NAME -> metadata.displayName
                DocumentsContract.Document.COLUMN_FLAGS -> {
                    // Always assume it can write to it until the file operation fails. Windows 10
                    // also does the same thing.
                    DocumentsContract.Document.FLAG_SUPPORTS_WRITE or
                            DocumentsContract.Document.FLAG_SUPPORTS_DELETE or
                            DocumentsContract.Document.FLAG_SUPPORTS_RENAME or
                            DocumentsContract.Document.FLAG_SUPPORTS_REMOVE or
                            DocumentsContract.Document.FLAG_SUPPORTS_MOVE or
                            if (metadata.canCreateDocument()) {
                                DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE
                            } else 0
                }
                DocumentsContract.Document.COLUMN_MIME_TYPE -> metadata.mimeType
                DocumentsContract.Document.COLUMN_SIZE -> metadata.size
                DocumentsContract.Document.COLUMN_LAST_MODIFIED -> metadata.lastModified
                DocumentsContract.Document.COLUMN_ICON -> metadata.iconResourceId
                else -> null
            }
        }?.toTypedArray() ?: arrayOf()
    }

    @Throws(FileNotFoundException::class)
    override fun createDocument(
        parentDocumentId: String,
        mimeType: String,
        displayName: String
    ): String {
        return try {
            val parentUri = toUri(parentDocumentId)
            val isDir = DocumentsContract.Document.MIME_TYPE_DIR == mimeType
            val entry = DirectoryEntry(
                if (isDir) DirectoryEntry.Type.DIR else DirectoryEntry.Type.FILE,
                comment = "",
                displayName
            )
            val uri = DocumentMetadata.buildChildUri(parentUri, entry)!!
            if (isDir) {
                smbFacade.mkdir(uri.toString())
            } else {
                smbFacade.createFile(uri.toString())
            }

            // Notify anyone who's listening on the parent folder.
            contentResolver.notifyChange(toNotifyUri(parentUri), null, NOTIFY_INSERT)
            cache[uri].use { result ->
                if (result.state != CacheResult.State.CACHE_MISS) {
                    // It must be a file, and the file is truncated... Reset its cache.
                    result.item.reset()

                    // No need to update the cache anymore.
                    return toDocumentId(uri)
                }
            }

            // Put it to cache without stat, newly created stuff is likely to be changed soon.
            val metadata = DocumentMetadata(uri, entry)
            cache.put(metadata)
            toDocumentId(uri)
        } catch (e: FileNotFoundException) {
            throw e
        } catch (e: IOException) {
            throw IllegalStateException(e)
        }
    }

    @Throws(FileNotFoundException::class)
    override fun renameDocument(documentId: String, displayName: String): String {
        return try {
            val uri = toUri(documentId)
            val parentUri = DocumentMetadata.buildParentUri(uri)
            if (parentUri.pathSegments.isEmpty()) {
                throw UnsupportedOperationException("Not support renaming a share/workgroup/server.")
            }
            val newUri = DocumentMetadata.buildChildUri(parentUri, displayName)
                ?: throw UnsupportedOperationException("$displayName is not a valid name.")
            smbFacade.rename(uri.toString(), newUri.toString())
            revokeDocumentPermission(documentId)
            contentResolver.notifyChange(
                toNotifyUri(parentUri),
                null,
                NOTIFY_UPDATE
            )
            cache[uri].use { result ->
                if (result.state != CacheResult.State.CACHE_MISS) {
                    val metadata = result.item
                    metadata.rename(newUri)
                    cache.remove(uri)
                    cache.put(metadata)
                }
            }
            toDocumentId(newUri)
        } catch (e: FileNotFoundException) {
            throw e
        } catch (e: IOException) {
            throw IllegalStateException(e)
        }
    }

    @Throws(FileNotFoundException::class)
    override fun deleteDocument(documentId: String) {
        val uri = toUri(documentId)
        try {
            // Obtain metadata first to determine whether it's a file or a folder. We need to do
            // different things on them. Ignore our cache since it might be out of date.
            val metadata = DocumentMetadata.fromUri(uri, smbFacade)
            if (DocumentsContract.Document.MIME_TYPE_DIR == metadata.mimeType) {
                recursiveDeleteFolder(metadata)
            } else {
                deleteFile(metadata)
            }
            val notifyUri = toNotifyUri(DocumentMetadata.buildParentUri(uri))
            contentResolver.notifyChange(notifyUri, null, NOTIFY_DELETE)
        } catch (e: FileNotFoundException) {
            Log.w(TAG, "$documentId is not found. No need to delete it.", e)
            cache.remove(uri)
            val notifyUri = toNotifyUri(DocumentMetadata.buildParentUri(uri))
            contentResolver.notifyChange(
                notifyUri,
                null,
                NOTIFY_DELETE
            )
        } catch (e: IOException) {
            throw IllegalStateException(e)
        }
    }

    @Throws(IOException::class)
    private fun recursiveDeleteFolder(metadata: DocumentMetadata) {
        // Fetch the latest children just in case our cache is out of date.
        metadata.loadChildren(smbFacade)
        for (child in metadata.children!!.values) {
            if (DocumentsContract.Document.MIME_TYPE_DIR == child.mimeType) {
                recursiveDeleteFolder(child)
            } else {
                deleteFile(child)
            }
        }
        val uri = metadata.uri
        smbFacade.rmdir(uri.toString())
        cache.remove(uri)
        revokeDocumentPermission(toDocumentId(uri))
    }

    @Throws(IOException::class)
    private fun deleteFile(metadata: DocumentMetadata) {
        val uri = metadata.uri
        smbFacade.unlink(uri.toString())
        cache.remove(uri)
        revokeDocumentPermission(toDocumentId(uri))
    }

    @Throws(FileNotFoundException::class)
    override fun removeDocument(documentId: String, parentDocumentId: String) {
        // documentId is hierarchical. It can only have one parent.
        deleteDocument(documentId)
    }

    @Throws(FileNotFoundException::class)
    override fun moveDocument(
        sourceDocumentId: String, sourceParentDocumentId: String, targetParentDocumentId: String
    ): String {
        return try {
            val uri = toUri(sourceDocumentId)
            val targetParentUri = toUri(targetParentDocumentId)
            if (uri.authority != targetParentUri.authority) {
                throw UnsupportedOperationException("Instant move across services are not supported.")
            }
            val pathSegmentsOfSource = uri.pathSegments
            val pathSegmentsOfTargetParent = targetParentUri.pathSegments
            if (pathSegmentsOfSource.isEmpty() ||
                pathSegmentsOfTargetParent.isEmpty() ||
                pathSegmentsOfSource[0] != pathSegmentsOfTargetParent[0]
            ) {
                throw UnsupportedOperationException("Instance move across shares are not supported.")
            }
            val targetUri = DocumentMetadata.buildChildUri(targetParentUri, uri.lastPathSegment)!!
            smbFacade.rename(uri.toString(), targetUri.toString())
            revokeDocumentPermission(sourceDocumentId)
            contentResolver.notifyChange(
                toNotifyUri(DocumentMetadata.buildParentUri(uri)),
                null,
                NOTIFY_UPDATE
            )
            contentResolver.notifyChange(
                toNotifyUri(targetParentUri),
                null,
                NOTIFY_UPDATE
            )
            cache[uri].use { result ->
                if (result.state != CacheResult.State.CACHE_MISS) {
                    val metadata = result.item
                    metadata.rename(targetUri)
                    cache.remove(uri)
                    cache.put(metadata)
                }
            }
            toDocumentId(targetUri)
        } catch (e: FileNotFoundException) {
            throw e
        } catch (e: IOException) {
            throw IllegalStateException(e)
        }
    }

    @Throws(FileNotFoundException::class)
    override fun openDocument(
        documentId: String, mode: String,
        cancellationSignal: CancellationSignal?
    ): ParcelFileDescriptor {
        if (BuildConfig.DEBUG) Log.d(TAG, "Opening document $documentId with mode $mode")
        return try {
            val uri = toUriString(documentId)
            if (SDK_INT >= VERSION_CODES.O) {
                val (parcelableFd, deferred) = smbFacade.openProxyFile(
                    uri,
                    mode,
                    storageManager,
                    bufferPool
                )
                if (mode.contains("w")) {
                    writeAsync(uri) {
                        deferred.await()
                    }
                }
                parcelableFd
            } else {
                openDocumentPreO(uri, mode)
            }
        } catch (e: FileNotFoundException) {
            throw e
        } catch (e: IOException) {
            throw IllegalStateException(e)
        }
    }

    @Throws(IOException::class)
    private fun openDocumentPreO(uri: String, mode: String): ParcelFileDescriptor {

        // Doesn't support complex mode on pre-O devices.
        if ("r" != mode && "w" != mode) {
            throw UnsupportedOperationException("Mode $mode is not supported")
        }
        val pipe = ParcelFileDescriptor.createReliablePipe()
        return when (mode) {
            "r" -> {
                lifecycleScope.launch {
                    readFile(uri, smbFacade, pipe[1], bufferPool)
                }
                pipe[0]
            }
            "w" -> {
                writeAsync(uri) {
                    writeFile(uri, smbFacade, pipe[0], bufferPool)
                }
                pipe[1]
            }
            else -> {
                // Should never happen.
                pipe[0].close()
                pipe[1].close()
                throw UnsupportedOperationException("Mode $mode is not supported.")
            }
        }
    }

    private suspend fun writeFile(
        uri: String,
        client: SmbClient,
        pfd: ParcelFileDescriptor,
        bufferPool: ByteBufferPool
    ) {
        val buffer = bufferPool.obtainBuffer()
        @Suppress("BlockingMethodInNonBlockingContext")
        withContext(Dispatchers.IO) {
            try {
                ParcelFileDescriptor.AutoCloseInputStream(pfd).use { inputStream ->
                    client.openFile(uri, "w").use { file ->
                        var size: Int
                        val buf = ByteArray(buffer.capacity())
                        while (inputStream.read(buf).also { size = it } > 0) {
                            buffer.put(buf, 0, size)
                            file.write(buffer, size)
                            buffer.clear()
                        }
                    }
                }
            } catch (e: IOException) {
                Log.e("WriteFileTask", "Failed to write file.", e)
                try {
                    pfd.closeWithError(e.message)
                } catch (exc: IOException) {
                    Log.e("WriteFileTask", "Can't even close PFD with error.", exc)
                }
                throw e
            }
        }
        bufferPool.recycleBuffer(buffer)
    }

    private fun writeAsync(uriString: String, writeFunc: suspend () -> Unit) {
        lifecycleScope.launch {
            writeFunc()
            val uri = toUri(uriString)
            // Update the cache
            cache[uri].use { result ->
                if (result.state != CacheResult.State.CACHE_MISS) {
                    result.item.reset()
                }
            }
            // Notify write change
            val parentUri = DocumentMetadata.buildParentUri(uri)
            contentResolver.notifyChange(
                toNotifyUri(parentUri),
                null,
                NOTIFY_UPDATE
            )
        }
    }

    private fun toNotifyUri(uri: Uri): Uri {
        return DocumentsContract.buildDocumentUri(AUTHORITY, toDocumentId(uri))
    }

    private suspend fun readFile(
        uri: String,
        client: SmbClient,
        pfd: ParcelFileDescriptor,
        bufferPool: ByteBufferPool
    ) {
        val buffer = bufferPool.obtainBuffer()
        @Suppress("BlockingMethodInNonBlockingContext")
        withContext(Dispatchers.IO) {
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(pfd).use { os ->
                    client.openFile(uri, "r").use { file ->
                        var size: Int
                        val buf = ByteArray(buffer.capacity())
                        while (file.read(buffer, Int.MAX_VALUE).also { size = it } > 0) {
                            buffer[buf, 0, size]
                            os.write(buf, 0, size)
                            buffer.clear()
                        }
                    }
                }
            } catch (e: IOException) {
                Log.e("ReadFileTask", "Failed to read file.", e)
                try {
                    pfd.closeWithError(e.message)
                } catch (exc: IOException) {
                    Log.e("ReadFileTask", "Can't even close PFD with error.", exc)
                }
            }
        }
        bufferPool.recycleBuffer(buffer)
    }

    private val lifecycleScope: CoroutineScope = GlobalScope

    companion object {
        const val AUTHORITY = "com.google.android.sambadocumentsprovider"
        private const val TAG = "SambaDocumentsProvider"
        private val DEFAULT_ROOT_PROJECTION = arrayOf(
            DocumentsContract.Root.COLUMN_ROOT_ID,
            DocumentsContract.Root.COLUMN_DOCUMENT_ID,
            DocumentsContract.Root.COLUMN_TITLE,
            DocumentsContract.Root.COLUMN_FLAGS,
            DocumentsContract.Root.COLUMN_ICON
        )
        private val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_FLAGS,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_ICON
        )
        private const val NOTIFY_UPDATE = ContentResolver.NOTIFY_UPDATE
        private const val NOTIFY_DELETE = ContentResolver.NOTIFY_DELETE
        private const val NOTIFY_INSERT = ContentResolver.NOTIFY_INSERT
    }
}