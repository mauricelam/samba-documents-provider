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

import com.google.android.sambadocumentsprovider.base.DirectoryEntry
import com.google.android.sambadocumentsprovider.browsing.broadcast.BroadcastBrowsingProvider
import com.google.android.sambadocumentsprovider.nativefacade.CredentialCache
import com.google.android.sambadocumentsprovider.nativefacade.SmbClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * This class discovers Samba servers and shares under them available on the local network.
 */
class NetworkBrowser(
    private val smbClient: SmbClient,
    private val credentialCache: CredentialCache
) {
    private val masterProvider: NetworkBrowsingProvider = MasterBrowsingProvider(smbClient)
    private val broadcastProvider: NetworkBrowsingProvider = BroadcastBrowsingProvider()

    /**
     * Asynchronously get available servers and shares under them.
     * A server name is mapped to the list of its children.
     */
    suspend fun getShares(
        serverUri: String,
        domain: String,
        username: String,
        password: String
    ): List<String> {
        @Suppress("BlockingMethodInNonBlockingContext")
        return withContext(Dispatchers.IO) {
            try {
                credentialCache.setTempMode(true)
                if (username.isNotEmpty() || password.isNotEmpty()) {
                    credentialCache.putCredential("$serverUri/IPC$", domain, username, password)
                }
                val dir = smbClient.openDir(serverUri)
                generateSequence { dir.readDir() }
                    .filter { share -> share.type == DirectoryEntry.Type.FILE_SHARE }
                    .mapNotNull { shareEntry -> shareEntry.name?.trim { it <= ' ' } }
                    .toList()
            } finally {
                credentialCache.setTempMode(false)
            }
        }
    }

    companion object {
        private const val TAG = "NetworkBrowser"
    }
}