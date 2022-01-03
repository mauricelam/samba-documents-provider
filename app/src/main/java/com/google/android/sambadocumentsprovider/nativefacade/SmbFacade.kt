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
package com.google.android.sambadocumentsprovider.nativefacade

import android.annotation.TargetApi
import kotlin.Throws
import android.os.storage.StorageManager
import com.google.android.sambadocumentsprovider.base.OnTaskFinishedCallback
import android.os.ParcelFileDescriptor
import com.google.android.sambadocumentsprovider.provider.ByteBufferPool
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import java.io.IOException

interface SmbFacade : SmbClient {
    @TargetApi(26)
    @Throws(IOException::class)
    fun openProxyFile(
        uri: String,
        mode: String,
        storageManager: StorageManager,
        bufferPool: ByteBufferPool,
        callback: OnTaskFinishedCallback<String>?
    ): ParcelFileDescriptor
}

@TargetApi(26)
@Throws(IOException::class)
fun SmbFacade.openProxyFile(
    uri: String,
    mode: String,
    storageManager: StorageManager,
    bufferPool: ByteBufferPool
): Pair<ParcelFileDescriptor, Deferred<Unit>> {
    val deferred = CompletableDeferred<Unit>()
    return openProxyFile(uri, mode, storageManager, bufferPool) { status, result, exception ->
        deferred.complete(Unit)
    } to deferred
}