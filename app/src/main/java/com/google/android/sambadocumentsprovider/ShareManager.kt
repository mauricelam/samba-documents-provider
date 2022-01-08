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
package com.google.android.sambadocumentsprovider

import android.content.Context
import android.util.Log
import com.google.android.sambadocumentsprovider.encryption.EncryptionException
import com.google.android.sambadocumentsprovider.encryption.EncryptionManager
import com.google.android.sambadocumentsprovider.nativefacade.CredentialCache
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException
import java.util.*
import kotlin.collections.ArrayList

class ShareManager internal constructor(
    context: Context,
    private val credentialCache: CredentialCache
) {
    private val pref =
        context.getSharedPreferences(SERVER_CACHE_PREF_KEY, Context.MODE_PRIVATE)
    private val mountedServerSet = HashSet<String>()
    private val serverStringMap = HashMap<String, String>()
    private val encryptionManager = EncryptionManager(context)
    private val listeners = ArrayList<() -> Unit>()

    /**
     * Save the server and credentials to permanent storage.
     * Update the server info. If a server with such a uri doesn't exist, create it.
     */
    @Synchronized
    @Throws(IOException::class)
    fun addServer(
        uri: String, workgroup: String, username: String, password: String,
        checker: () -> Unit, mount: Boolean, updateExisting: Boolean = false
    ) {
        if (updateExisting) {
            check(uri in mountedServerSet) { "Cannot find server $uri to update" }
        } else {
            check(uri !in mountedServerSet) { "Cannot add server $uri. Server already exists." }
        }
        saveAndCheckServerCredentials(uri, workgroup, username, password, checker)
        updateServersData(
            ShareTuple(uri, workgroup, username, password, mount),
            shouldNotify = mount
        )
    }

    @Throws(EncryptionException::class)
    private fun updateServersData(tuple: ShareTuple, shouldNotify: Boolean) {
        val serverString = Json.encodeToString(tuple)
        serverStringMap[tuple.uri] = encryptionManager.encrypt(serverString)
        if (tuple.isMounted) {
            mountedServerSet.add(tuple.uri)
        } else {
            mountedServerSet.remove(tuple.uri)
        }
        pref.edit().putStringSet(SERVER_STRING_SET_KEY, serverStringMap.values.toSet()).apply()
        if (shouldNotify) {
            notifyServerChange()
        }
    }

    @Throws(IOException::class)
    private fun saveAndCheckServerCredentials(
        uri: String,
        workgroup: String,
        username: String,
        password: String,
        checker: () -> Unit,
    ) {
        if (username.isNotEmpty() && password.isNotEmpty()) {
            credentialCache.putCredential(uri, workgroup, username, password)
        }
        try {
            checker()
        } catch (e: Exception) {
            Log.i(TAG, "Failed to mount server.", e)
            credentialCache.removeCredential(uri)
            throw e
        }
    }

    @Synchronized
    fun unmountServer(uri: String): Boolean {
        if (!serverStringMap.containsKey(uri)) {
            return true
        }
        serverStringMap.remove(uri)
        mountedServerSet.remove(uri)
        pref.edit().putStringSet(SERVER_STRING_SET_KEY, serverStringMap.values.toSet()).apply()
        credentialCache.removeCredential(uri)
        notifyServerChange()
        return true
    }

    @Synchronized
    fun getShareUris(): List<String> {
        return serverStringMap.keys.toList()
    }

    @Synchronized
    fun containsShare(uri: String): Boolean {
        return serverStringMap.containsKey(uri)
    }

    @Synchronized
    fun isShareMounted(uri: String): Boolean {
        return mountedServerSet.contains(uri)
    }

    private fun notifyServerChange() {
        for (i in listeners.indices.reversed()) {
            listeners[i]()
        }
    }

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    @Serializable
    data class ShareTuple(
        val uri: String,
        val workgroup: String,
        val username: String,
        val password: String,
        val isMounted: Boolean
    )

    companion object {
        private const val TAG = "ShareManager"
        private const val SERVER_CACHE_PREF_KEY = "ServerCachePref"
        private const val SERVER_STRING_SET_KEY = "ServerStringSet"
    }

    private fun decryptTuple(serverString: String): ShareTuple {
        val decryptedString = encryptionManager.decrypt(serverString)
        return Json.decodeFromString(decryptedString)
    }

    fun getShareTuple(uri: String): ShareTuple? {
        val serverString = serverStringMap[uri] ?: return null
        return decryptTuple(serverString)
    }

    init {
        // Loading saved servers.
        val serverStringSet = pref.getStringSet(SERVER_STRING_SET_KEY, emptySet())!!
        for (serverString in serverStringSet) {
            val tuple = decryptTuple(serverString)
            if (tuple.isMounted) {
                mountedServerSet.add(tuple.uri)
            }
            credentialCache.putCredential(
                tuple.uri,
                tuple.workgroup,
                tuple.username,
                tuple.password
            )
            serverStringMap[tuple.uri] = serverString
        }
    }
}