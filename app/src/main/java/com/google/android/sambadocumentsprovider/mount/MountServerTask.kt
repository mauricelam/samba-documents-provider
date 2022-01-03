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
package com.google.android.sambadocumentsprovider.mount

import android.util.Log
import com.google.android.sambadocumentsprovider.document.DocumentMetadata
import com.google.android.sambadocumentsprovider.nativefacade.SmbClient
import com.google.android.sambadocumentsprovider.ShareManager
import com.google.android.sambadocumentsprovider.cache.DocumentCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.lang.Exception

private const val TAG = "MountServerTask"

suspend fun mountServer(
    metadata: DocumentMetadata,
    domain: String,
    username: String,
    password: String,
    client: SmbClient,
    cache: DocumentCache,
    shareManager: ShareManager,
) {
    try {
        withContext(Dispatchers.IO) {
            shareManager.addServer(
                metadata.uri.toString(), domain, username, password,
                { metadata.loadChildren(client) }, true
            )
        }
        for (m in metadata.children!!.values) {
            cache.put(m)
        }
    } catch (e: Exception) {
        Log.e(TAG, "Failed to mount share.", e)
        throw e
    }

//    fun onCancelled() {
//        // User cancelled the task, unmount it regardless of its result.
//        mShareManager.unmountServer(mMetadata.uri.toString())
//    }
}