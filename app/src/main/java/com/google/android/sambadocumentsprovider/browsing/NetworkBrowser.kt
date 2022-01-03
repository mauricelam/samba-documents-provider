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
package com.google.android.sambadocumentsprovider.browsing

import android.net.Uri
import android.util.Log
import androidx.annotation.WorkerThread
import com.google.android.sambadocumentsprovider.base.DirectoryEntry
import com.google.android.sambadocumentsprovider.browsing.broadcast.BroadcastBrowsingProvider
import com.google.android.sambadocumentsprovider.nativefacade.SmbClient
import com.google.android.sambadocumentsprovider.nativefacade.SmbDir
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * This class discovers Samba servers and shares under them available on the local network.
 */
class NetworkBrowser(client: SmbClient) {
    private val masterProvider: NetworkBrowsingProvider
    private val broadcastProvider: NetworkBrowsingProvider
    private val smbClient: SmbClient

    /**
     * Asynchronously get available servers and shares under them.
     * A server name is mapped to the list of its children.
     */
    suspend fun getSharesAsync(serverUri: String): Map<String, List<String>> {
        return withContext(Dispatchers.IO) {
            mapOf(serverUri to getSharesForServer(serverUri))
        }
    }

    @WorkerThread
    @Throws(IOException::class)
    private fun getSharesForServer(serverUri: String): List<String> {
        return smbClient.openDir(serverUri).iterDir()
            .mapNotNull { shareEntry -> shareEntry.name?.trim { it <= ' ' } }
            .toList()
    }

    private fun SmbDir.iterDir(): Sequence<DirectoryEntry> {
        return sequence {
            var shareEntry: DirectoryEntry?
            while (readDir().also { shareEntry = it } != null) {
                shareEntry?.let { share ->
                    if (share.type == DirectoryEntry.Type.FILE_SHARE) {
                        yield(share)
                    } else {
                        Log.i(TAG, "Unsupported entry type: ${share.type}")
                    }
                }
            }
        }
    }

    @Throws(BrowsingException::class)
    private fun loadServers(): List<String> {
        return try {
            masterProvider.servers
        } catch (e: BrowsingException) {
            Log.e(TAG, "Master browsing failed", e)
            null
        }?.takeUnless { it.isEmpty() } ?: broadcastProvider.servers
    }

    companion object {
        private val SMB_BROWSING_URI = Uri.parse("smb://")
        private const val TAG = "NetworkBrowser"
    }

    init {
        masterProvider = MasterBrowsingProvider(client)
        broadcastProvider = BroadcastBrowsingProvider()
        smbClient = client
    }
}