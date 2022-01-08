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
import android.util.JsonReader
import android.util.JsonWriter
import android.util.Log
import com.google.android.sambadocumentsprovider.ShareManager.ShareTuple.Companion.fromServerString
import com.google.android.sambadocumentsprovider.encryption.EncryptionException
import com.google.android.sambadocumentsprovider.encryption.EncryptionManager
import com.google.android.sambadocumentsprovider.nativefacade.CredentialCache
import java.io.IOException
import java.io.StringReader
import java.io.StringWriter
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
        val serverString = encode(tuple)
            ?: throw IllegalStateException("Failed to encode credential tuple.")
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

    data class ShareTuple(
        val uri: String,
        val workgroup: String,
        val username: String,
        val password: String,
        val isMounted: Boolean
    ) {
        companion object {
            fun ShareManager.fromServerString(serverString: String): ShareTuple {
                val decryptedString = encryptionManager.decrypt(serverString)
                return decode(decryptedString)
            }
        }
    }

    companion object {
        private const val TAG = "ShareManager"
        private const val SERVER_CACHE_PREF_KEY = "ServerCachePref"
        private const val SERVER_STRING_SET_KEY = "ServerStringSet"

        // JSON value
        private const val URI_KEY = "uri"
        private const val MOUNT_KEY = "mount"
        private const val WORKGROUP_KEY = "workgroup"
        private const val USERNAME_KEY = "username"
        private const val PASSWORD_KEY = "password"

        private fun encode(tuple: ShareTuple): String? {
            return try {
                val stringWriter = StringWriter()
                JsonWriter(stringWriter).writeObject {
                    name(URI_KEY).value(tuple.uri)
                    if (tuple.username.isNotEmpty()) {
                        name(WORKGROUP_KEY).value(tuple.workgroup)
                        name(USERNAME_KEY).value(tuple.username)
                        name(PASSWORD_KEY).value(tuple.password)
                    }
                    name(MOUNT_KEY).value(tuple.isMounted)
                }
                stringWriter.toString()
            } catch (e: IOException) {
                Log.e(TAG, "Failed to encode credential for ${tuple.uri}")
                null
            }
        }

        private inline fun JsonWriter.writeObject(crossinline writer: JsonWriter.() -> Unit) {
            beginObject()
            writer()
            endObject()
        }

        private fun decode(content: String): ShareTuple {
            JsonReader(StringReader(content)).apply {
                var uri: String? = null
                var workgroup: String? = null
                var username: String? = null
                var password: String? = null
                var mounted = true
                nextObject { name ->
                    when (name) {
                        URI_KEY -> uri = nextString()
                        WORKGROUP_KEY -> workgroup = nextString()
                        USERNAME_KEY -> username = nextString()
                        PASSWORD_KEY -> password = nextString()
                        MOUNT_KEY -> mounted = nextBoolean()
                        else -> Log.w(TAG, "Ignoring unknown key $name")
                    }
                }
                return ShareTuple(
                    uri!!,
                    workgroup ?: "",
                    username ?: "",
                    password ?: "",
                    mounted
                )
            }
        }

        private inline fun JsonReader.nextObject(block: JsonReader.(String) -> Unit) {
            beginObject()
            while (hasNext()) {
                this.block(nextName())
            }
            endObject()
        }
    }

    fun getShareTuple(uri: String): ShareTuple? {
        val serverString = serverStringMap[uri] ?: return null
        return fromServerString(serverString)
    }

    init {
        // Loading saved servers.
        val serverStringSet = pref.getStringSet(SERVER_STRING_SET_KEY, emptySet())!!
        for (serverString in serverStringSet) {
            val tuple = fromServerString(serverString)
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