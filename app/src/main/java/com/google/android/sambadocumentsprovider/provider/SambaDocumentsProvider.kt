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
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Build
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
import com.google.android.sambadocumentsprovider.base.OnTaskFinishedCallback
import com.google.android.sambadocumentsprovider.cache.CacheResult
import com.google.android.sambadocumentsprovider.cache.DocumentCache
import com.google.android.sambadocumentsprovider.document.DocumentMetadata
import com.google.android.sambadocumentsprovider.document.LoadChildrenTask
import com.google.android.sambadocumentsprovider.document.LoadDocumentTask
import com.google.android.sambadocumentsprovider.document.LoadStatTask
import com.google.android.sambadocumentsprovider.nativefacade.SmbFacade
import java.io.FileNotFoundException
import java.io.IOException
import java.util.*

class SambaDocumentsProvider : DocumentsProvider() {
    private val mLoadDocumentCallback =
        OnTaskFinishedCallback { status: Int, uri: Uri?, exception: Exception? ->
            context!!.contentResolver.notifyChange(
                toNotifyUri(uri),
                null,
                false
            )
        }
    private val mLoadChildrenCallback =
        OnTaskFinishedCallback { status: Int, metadata: DocumentMetadata?, exception: Exception? ->
            // Notify remote side that we get the list even though we don't have the stat yet.
            // If it failed we still should notify the remote side that the loading failed.
            context!!.contentResolver.notifyChange(
                toNotifyUri(metadata!!.uri), null, false
            )
        }
    private val mWriteFinishedCallback: OnTaskFinishedCallback<String> =
        OnTaskFinishedCallback { status, item, exception ->
            val uri = toUri(item!!)
            mCache[uri].use { result ->
                if (result.state != CacheResult.CACHE_MISS) {
                    result.item.reset()
                }
            }
            val parentUri = DocumentMetadata.buildParentUri(uri)
            context!!.contentResolver.notifyChange(toNotifyUri(parentUri), null, false)
        }
    private lateinit var mShareManager: ShareManager
    private lateinit var mClient: SmbFacade
    private lateinit var mBufferPool: ByteBufferPool
    private lateinit var mCache: DocumentCache
    private lateinit var mTaskManager: TaskManager
    private lateinit var mStorageManager: StorageManager

    override fun onCreate(): Boolean {
        return context?.let { context ->
            SambaProviderApplication.init(context)
            mClient = SambaProviderApplication.getSambaClient(context)
            mCache = SambaProviderApplication.getDocumentCache(context)
            mTaskManager = SambaProviderApplication.getTaskManager(context)
            mBufferPool = ByteBufferPool()
            mShareManager = SambaProviderApplication.getServerManager(context)
            mShareManager.addListener {
                val rootsUri = DocumentsContract.buildRootsUri(AUTHORITY)
                val resolver = context.contentResolver
                resolver.notifyChange(rootsUri, null, false)
            }
            mStorageManager = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
            true
        } ?: false
    }

    @Throws(FileNotFoundException::class)
    override fun queryRoots(projection: Array<String>?): Cursor {
        if (BuildConfig.DEBUG) Log.d(TAG, "Querying roots.")
        val projection = projection ?: DEFAULT_ROOT_PROJECTION
        val cursor = MatrixCursor(projection)
        for (uri in mShareManager.getShares()) {
            if (!mShareManager.isShareMounted(uri)) {
                continue
            }
            val name: String
            val parsedUri = Uri.parse(uri)
            mCache[parsedUri].use { result ->
                val metadata: DocumentMetadata
                if (result.state == CacheResult.CACHE_MISS) {
                    metadata = DocumentMetadata.createShare(parsedUri)
                    mCache.put(metadata)
                } else {
                    metadata = result.item
                }
                name = metadata.displayName
                cursor.addRow(
                    arrayOf<Any>(
                        toRootId(metadata),
                        toDocumentId(parsedUri),
                        name,
                        DocumentsContract.Root.FLAG_SUPPORTS_CREATE or DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD or DocumentsContract.Root.FLAG_SUPPORTS_EJECT,
                        R.drawable.ic_folder_shared
                    )
                )
            }
        }
        return cursor
    }

    override fun ejectRoot(rootId: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, "Ejecting root: $rootId")
        check(mShareManager.unmountServer(rootId)) { "Failed to eject root: $rootId" }
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        val parentUri = toUriString(parentDocumentId)
        val childUri = toUriString(documentId)
        return childUri.startsWith(parentUri)
    }

    @Throws(FileNotFoundException::class)
    override fun queryDocument(documentId: String, projection: Array<String>?): Cursor {
        if (BuildConfig.DEBUG) Log.d(TAG, "Querying document: $documentId")
        val projection = projection ?: DEFAULT_DOCUMENT_PROJECTION
        val cursor = MatrixCursor(projection)
        val uri = toUri(documentId)
        try {
            mCache[uri].use { result ->
                val metadata: DocumentMetadata
                if (result.state == CacheResult.CACHE_MISS) {
                    metadata = if (mShareManager.containsShare(uri.toString())) {
                        DocumentMetadata.createShare(uri)
                    } else {
                        // There is no cache for this URI. Fetch it from remote side.
                        DocumentMetadata.fromUri(uri, mClient)
                    }
                    mCache.put(metadata)
                } else {
                    metadata = result.item
                }
                cursor.addRow(getDocumentValues(projection, metadata))
                return cursor
            }
        } catch (e: FileNotFoundException) {
            throw e
        } catch (e: RuntimeException) {
            throw e
        } catch (e: Exception) {
            throw IllegalStateException(e)
        }
    }

    @Throws(FileNotFoundException::class, AuthenticationRequiredException::class)
    override fun queryChildDocuments(
        documentId: String,
        projection: Array<String>?,
        sortOrder: String
    ): Cursor {
        if (BuildConfig.DEBUG) Log.d(TAG, "Querying children documents under $documentId")
        val projection = projection ?: DEFAULT_DOCUMENT_PROJECTION
        val uri = toUri(documentId)
        try {
            if (DocumentMetadata.isServerUri(uri)) {
                mCache[uri].use { result ->
                    if (result.state == CacheResult.CACHE_MISS) {
                        val metadata = DocumentMetadata.createServer(uri)
                        mCache.put(metadata)
                    }
                }
            }
            mCache[uri].use { result ->
                var isLoading = false
                val extra = Bundle()
                val notifyUri = toNotifyUri(uri)
                val cursor = DocumentCursor(projection)
                if (result.state == CacheResult.CACHE_MISS) {
                    // Last loading failed... Just feed the bitter fruit.
                    mCache.throwLastExceptionIfAny(uri)
                    val task = LoadDocumentTask(uri, mClient, mCache, mLoadDocumentCallback)
                    mTaskManager.runTask(uri, task)
                    cursor.setLoadingTask(task)
                    isLoading = true
                } else { // At least we have something in cache.
                    val metadata = result.item
                    require(DocumentsContract.Document.MIME_TYPE_DIR == metadata.mimeType) { "$documentId is not a folder." }
                    metadata.throwLastChildUpdateExceptionIfAny()
                    val childrenMap = metadata.children
                    if (childrenMap == null || result.state == CacheResult.CACHE_EXPIRED) {
                        val task =
                            LoadChildrenTask(metadata, mClient, mCache, mLoadChildrenCallback)
                        mTaskManager.runTask(uri, task)
                        cursor.setLoadingTask(task)
                        isLoading = true
                    }

                    // Still return something even if the cache expired.
                    if (childrenMap != null) {
                        val children: Collection<DocumentMetadata> = childrenMap.values
                        val docMap: MutableMap<Uri, DocumentMetadata> = HashMap()
                        for (child in children) {
                            if (child.needsStat() && !child.hasLoadingStatFailed()) {
                                docMap[child.uri] = child
                            }
                            cursor.addRow(getDocumentValues(projection, child))
                        }
                        if (!isLoading && !docMap.isEmpty()) {
                            val task = LoadStatTask(
                                docMap, mClient
                            ) { status, item, exception ->
                                context!!.contentResolver.notifyChange(
                                    notifyUri,
                                    null,
                                    false
                                )
                            }
                            mTaskManager.runTask(uri, task)
                            cursor.setLoadingTask(task)
                            isLoading = true
                        }
                    }
                }
                extra.putBoolean(DocumentsContract.EXTRA_LOADING, isLoading)
                cursor.extras = extra
                cursor.setNotificationUri(context!!.contentResolver, notifyUri)
                return cursor
            }
        } catch (e: AuthFailedException) {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && DocumentMetadata.isShareUri(
                    uri
                )
            ) {
                throw AuthenticationRequiredException(
                    e, createAuthIntent(context!!, uri.toString())
                )
            } else {
                buildErrorCursor(projection, R.string.view_folder_denied)
            }
        } catch (e: FileNotFoundException) {
            throw e
        } catch (e: RuntimeException) {
            throw e
        } catch (e: Exception) {
            throw IllegalStateException(e)
        }
    }

    private fun buildErrorCursor(projection: Array<String>?, @StringRes resId: Int): Cursor {
        val message = context!!.getString(resId)
        val extra = Bundle()
        extra.putString(DocumentsContract.EXTRA_ERROR, message)
        val cursor = DocumentCursor(projection)
        cursor.extras = extra
        return cursor
    }

    private fun getDocumentValues(
        projection: Array<String>?, metadata: DocumentMetadata
    ): Array<Any?> {
        val row = arrayOfNulls<Any>(projection!!.size)
        for (i in projection.indices) {
            when (projection[i]) {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID -> row[i] = toDocumentId(metadata.uri)
                DocumentsContract.Document.COLUMN_DISPLAY_NAME -> row[i] = metadata.displayName
                DocumentsContract.Document.COLUMN_FLAGS -> {
                    // Always assume it can write to it until the file operation fails. Windows 10 also does
                    // the same thing.
                    var flag =
                        if (metadata.canCreateDocument()) DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE else 0
                    flag = flag or DocumentsContract.Document.FLAG_SUPPORTS_WRITE
                    flag = flag or DocumentsContract.Document.FLAG_SUPPORTS_DELETE
                    flag = flag or DocumentsContract.Document.FLAG_SUPPORTS_RENAME
                    flag = flag or DocumentsContract.Document.FLAG_SUPPORTS_REMOVE
                    flag = flag or DocumentsContract.Document.FLAG_SUPPORTS_MOVE
                    row[i] = flag
                }
                DocumentsContract.Document.COLUMN_MIME_TYPE -> row[i] = metadata.mimeType
                DocumentsContract.Document.COLUMN_SIZE -> row[i] = metadata.size
                DocumentsContract.Document.COLUMN_LAST_MODIFIED -> row[i] = metadata.lastModified
                DocumentsContract.Document.COLUMN_ICON -> row[i] = metadata.iconResourceId
            }
        }
        return row
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
                "",  // comment
                displayName
            )
            val uri = DocumentMetadata.buildChildUri(parentUri, entry)
            if (isDir) {
                mClient.mkdir(uri.toString())
            } else {
                mClient.createFile(uri.toString())
            }

            // Notify anyone who's listening on the parent folder.
            context!!.contentResolver.notifyChange(toNotifyUri(parentUri), null, false)
            mCache[uri].use { result ->
                if (result.state != CacheResult.CACHE_MISS) {
                    // It must be a file, and the file is truncated... Reset its cache.
                    result.item.reset()

                    // No need to update the cache anymore.
                    return toDocumentId(uri)
                }
            }

            // Put it to cache without stat, newly created stuff is likely to be changed soon.
            val metadata = DocumentMetadata(uri, entry)
            mCache.put(metadata)
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
            mClient.rename(uri.toString(), newUri.toString())
            revokeDocumentPermission(documentId)
            context!!.contentResolver.notifyChange(toNotifyUri(parentUri), null, false)
            mCache[uri].use { result ->
                if (result.state != CacheResult.CACHE_MISS) {
                    val metadata = result.item
                    metadata.rename(newUri)
                    mCache.remove(uri)
                    mCache.put(metadata)
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
            val metadata = DocumentMetadata.fromUri(uri, mClient)
            if (DocumentsContract.Document.MIME_TYPE_DIR == metadata.mimeType) {
                recursiveDeleteFolder(metadata)
            } else {
                deleteFile(metadata)
            }
            val notifyUri = toNotifyUri(DocumentMetadata.buildParentUri(uri))
            context!!.contentResolver.notifyChange(notifyUri, null, false)
        } catch (e: FileNotFoundException) {
            Log.w(TAG, "$documentId is not found. No need to delete it.", e)
            mCache.remove(uri)
            val notifyUri = toNotifyUri(DocumentMetadata.buildParentUri(uri))
            context!!.contentResolver.notifyChange(notifyUri, null, false)
        } catch (e: IOException) {
            throw IllegalStateException(e)
        }
    }

    @Throws(IOException::class)
    private fun recursiveDeleteFolder(metadata: DocumentMetadata) {
        // Fetch the latest children just in case our cache is out of date.
        metadata.loadChildren(mClient)
        for (child in metadata.children!!.values) {
            if (DocumentsContract.Document.MIME_TYPE_DIR == child.mimeType) {
                recursiveDeleteFolder(child)
            } else {
                deleteFile(child)
            }
        }
        val uri = metadata.uri
        mClient.rmdir(uri.toString())
        mCache.remove(uri)
        revokeDocumentPermission(toDocumentId(uri))
    }

    @Throws(IOException::class)
    private fun deleteFile(metadata: DocumentMetadata) {
        val uri = metadata.uri
        mClient.unlink(uri.toString())
        mCache.remove(uri)
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
            val targetUri = DocumentMetadata
                .buildChildUri(targetParentUri, uri.lastPathSegment)
            mClient.rename(uri.toString(), targetUri.toString())
            revokeDocumentPermission(sourceDocumentId)
            context!!.contentResolver
                .notifyChange(toNotifyUri(DocumentMetadata.buildParentUri(uri)), null, false)
            context!!.contentResolver.notifyChange(toNotifyUri(targetParentUri), null, false)
            mCache[uri].use { result ->
                if (result.state != CacheResult.CACHE_MISS) {
                    val metadata = result.item
                    metadata.rename(targetUri)
                    mCache.remove(uri)
                    mCache.put(metadata)
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val callback = if (mode.contains("w")) mWriteFinishedCallback else null
                mClient.openProxyFile(
                    uri,
                    mode,
                    mStorageManager,
                    mBufferPool,
                    callback
                )
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
                run {
                    val task = ReadFileTask(
                        uri, mClient, pipe[1], mBufferPool
                    )
                    mTaskManager.runIoTask(task)
                }
                pipe[0]
            }
            "w" -> {
                val task = WriteFileTask(uri, mClient, pipe[0], mBufferPool, mWriteFinishedCallback)
                mTaskManager.runIoTask(task)
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

    private fun toNotifyUri(uri: Uri?): Uri {
        return DocumentsContract.buildDocumentUri(
            AUTHORITY, toDocumentId(
                uri!!
            )
        )
    }

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
    }
}