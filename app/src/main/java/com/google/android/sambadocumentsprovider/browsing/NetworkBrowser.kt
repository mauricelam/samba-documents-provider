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
import com.google.android.sambadocumentsprovider.TaskManager
import com.google.android.sambadocumentsprovider.base.DirectoryEntry
import com.google.android.sambadocumentsprovider.browsing.broadcast.BroadcastBrowsingProvider
import com.google.android.sambadocumentsprovider.nativefacade.SmbClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.*

/**
 * This class discovers Samba servers and shares under them available on the local network.
 */
class NetworkBrowser(client: SmbClient, taskManager: TaskManager) {
    private val mMasterProvider: NetworkBrowsingProvider
    private val mBroadcastProvider: NetworkBrowsingProvider
    private val mTaskManager: TaskManager
    private val mClient: SmbClient

    /**
     * Asynchronously get available servers and shares under them.
     * A server name is mapped to the list of its children.
     */
    suspend fun getSharesAsync(): Map<String, List<String>> {
        return withContext(Dispatchers.IO) {
            loadServers().mapNotNull { server ->
                try {
                    server to getSharesForServer(server)
                } catch (e: IOException) {
                    Log.e(TAG, "Failed to load shares for server", e)
                    null
                }
            }.toMap()
        }
//        mTaskManager.runTask(SMB_BROWSING_URI, loadServersTask)
    }

    @Throws(IOException::class)
    private fun getSharesForServer(server: String): List<String> {
        val shares: MutableList<String> = ArrayList()
        val serverUri = SMB_BROWSING_URI.toString() + server
        val serverDir = mClient.openDir(serverUri)
        var shareEntry: DirectoryEntry
        while (serverDir.readDir().also { shareEntry = it!! } != null) {
            if (shareEntry.type == DirectoryEntry.FILE_SHARE) {
                shares.add(serverUri + "/" + shareEntry.name.trim { it <= ' ' })
            } else {
                Log.i(TAG, "Unsupported entry type: " + shareEntry.type)
            }
        }
        return shares
    }

    @Throws(BrowsingException::class)
    private fun loadServers(): List<String> {
        return try {
            mMasterProvider.servers
        } catch (e: BrowsingException) {
            Log.e(TAG, "Master browsing failed", e)
            null
        }?.takeUnless { it.isEmpty() } ?: mBroadcastProvider.servers
    }

    companion object {
        private val SMB_BROWSING_URI = Uri.parse("smb://")
        private const val TAG = "NetworkBrowser"
    }

    init {
        mMasterProvider = MasterBrowsingProvider(client)
        mBroadcastProvider = BroadcastBrowsingProvider()
        mTaskManager = taskManager
        mClient = client
    }
}