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
import android.text.TextUtils
import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import android.util.Log
import com.google.android.sambadocumentsprovider.encryption.EncryptionException
import com.google.android.sambadocumentsprovider.encryption.EncryptionManager
import com.google.android.sambadocumentsprovider.nativefacade.CredentialCache
import java.io.IOException
import java.io.StringReader
import java.io.StringWriter
import java.util.*

class ShareManager internal constructor(
    context: Context,
    private val mCredentialCache: CredentialCache
) : Iterable<String> {
    private val mPref =
        context.getSharedPreferences(SERVER_CACHE_PREF_KEY, Context.MODE_PRIVATE)
    private val mServerStringSet: MutableSet<String>
    private val mMountedServerSet = HashSet<String>()
    private val mServerStringMap = HashMap<String, String>()
    private val mEncryptionManager = EncryptionManager(context)
    private val mListeners = ArrayList<() -> Unit>()

    /**
     * Save the server and credentials to permanent storage.
     * Throw an exception if a server with such a uri is already present.
     */
    @Synchronized
    @Throws(IOException::class)
    fun addServer(
        uri: String, workgroup: String, username: String, password: String,
        checker: () -> Unit, mount: Boolean
    ) {
        check(uri !in mMountedServerSet) { "Uri $uri is already stored." }
        saveServerInfo(uri, workgroup, username, password, checker, mount)
    }

    /**
     * Update the server info. If a server with such a uri doesn't exist, create it.
     */
    @Synchronized
    @Throws(IOException::class)
    fun addOrUpdateServer(
        uri: String, workgroup: String, username: String, password: String,
        checker: () -> Unit, mount: Boolean
    ) {
        saveServerInfo(uri, workgroup, username, password, checker, mount)
    }

    @Throws(IOException::class)
    private fun saveServerInfo(
        uri: String, workgroup: String, username: String, password: String,
        checker: () -> Unit, mount: Boolean
    ) {
        checkServerCredentials(uri, workgroup, username, password, checker)
        val hasPassword = !TextUtils.isEmpty(username) && !TextUtils.isEmpty(password)
        val tuple = if (hasPassword) ShareTuple(
            workgroup,
            username,
            password,
            mount
        ) else ShareTuple.EMPTY_TUPLE
        updateServersData(uri, tuple, mount)
    }

    private fun updateServersData(uri: String, tuple: ShareTuple, shouldNotify: Boolean) {
        val serverString = encode(uri, tuple)
            ?: throw IllegalStateException("Failed to encode credential tuple.")
        val encryptedString: String
        try {
            encryptedString = mEncryptionManager.encrypt(serverString)
            mServerStringSet.add(encryptedString)
        } catch (e: EncryptionException) {
            throw IllegalStateException("Failed to encrypt server data", e)
        }
        if (tuple.isMounted) {
            mMountedServerSet.add(uri)
        } else {
            mMountedServerSet.remove(uri)
        }
        mPref.edit().putStringSet(SERVER_STRING_SET_KEY, mServerStringSet).apply()
        mServerStringMap[uri] = encryptedString
        if (shouldNotify) {
            notifyServerChange()
        }
    }

    @Throws(IOException::class)
    private fun checkServerCredentials(
        uri: String,
        workgroup: String,
        username: String,
        password: String,
        checker: () -> Unit
    ) {
        if (username.isNotEmpty() && password.isNotEmpty()) {
            mCredentialCache.putCredential(uri, workgroup, username, password)
        }
        runMountChecker(uri, checker)
    }

    @Throws(IOException::class)
    private fun runMountChecker(uri: String, checker: () -> Unit) {
        try {
            checker()
        } catch (e: Exception) {
            Log.i(TAG, "Failed to mount server.", e)
            mCredentialCache.removeCredential(uri)
            throw e
        }
    }

    private fun encryptServers(servers: List<String?>) {
        for (server in servers) {
            try {
                mServerStringSet.add(mEncryptionManager.encrypt(server))
            } catch (e: EncryptionException) {
                Log.e(TAG, "Failed to encrypt server data: ", e)
            }
        }
        mPref.edit().putStringSet(SERVER_STRING_SET_KEY, mServerStringSet).apply()
    }

    @Synchronized
    fun unmountServer(uri: String): Boolean {
        if (!mServerStringMap.containsKey(uri)) {
            return true
        }
        if (!mServerStringSet.remove(mServerStringMap[uri])) {
            Log.e(TAG, "Failed to remove server $uri")
            return false
        }
        mServerStringMap.remove(uri)
        mMountedServerSet.remove(uri)
        mPref.edit().putStringSet(SERVER_STRING_SET_KEY, mServerStringSet).apply()
        mCredentialCache.removeCredential(uri)
        notifyServerChange()
        return true
    }

    @Synchronized
    override fun iterator(): MutableIterator<String> {
        // Create a deep copy of current set to avoid modification on iteration.
        return ArrayList(mServerStringMap.keys).iterator()
    }

    @Synchronized
    fun size(): Int {
        return mServerStringMap.size
    }

    @Synchronized
    fun containsShare(uri: String): Boolean {
        return mServerStringMap.containsKey(uri)
    }

    @Synchronized
    fun isShareMounted(uri: String): Boolean {
        return mMountedServerSet.contains(uri)
    }

    private fun notifyServerChange() {
        for (i in mListeners.indices.reversed()) {
            mListeners[i]()
        }
    }

    fun addListener(listener: () -> Unit) {
        mListeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        mListeners.remove(listener)
    }

    private data class ShareTuple(
        val workgroup: String,
        val username: String,
        val password: String,
        val isMounted: Boolean
    ) {

        companion object {
            val EMPTY_TUPLE = ShareTuple("", "", "", true)
        }
    }

    companion object {
        private const val TAG = "ShareManager"
        private const val SERVER_CACHE_PREF_KEY = "ServerCachePref"
        private const val SERVER_STRING_SET_KEY = "ServerStringSet"

        // JSON value
        private const val URI_KEY = "uri"
        private const val MOUNT_KEY = "mount"
        private const val CREDENTIAL_TUPLE_KEY = "credentialTuple"
        private const val WORKGROUP_KEY = "workgroup"
        private const val USERNAME_KEY = "username"
        private const val PASSWORD_KEY = "password"
        private fun encode(uri: String, tuple: ShareTuple): String? {
            return try {
                val stringWriter = StringWriter()
                JsonWriter(stringWriter).writeObject {
                    name(URI_KEY).value(uri)
                    name(CREDENTIAL_TUPLE_KEY).encodeTuple(tuple)
                }
                stringWriter.toString()
            } catch (e: IOException) {
                Log.e(TAG, "Failed to encode credential for $uri")
                null
            }
        }

        private inline fun JsonWriter.writeObject(crossinline writer: JsonWriter.() -> Unit) {
            beginObject()
            writer()
            endObject()
        }

        @Throws(IOException::class)
        private fun JsonWriter.encodeTuple(tuple: ShareTuple) {
            if (tuple === ShareTuple.EMPTY_TUPLE) {
                nullValue()
            } else {
                writeObject {
                    name(WORKGROUP_KEY).value(tuple.workgroup)
                    name(USERNAME_KEY).value(tuple.username)
                    name(PASSWORD_KEY).value(tuple.password)
                    name(MOUNT_KEY).value(tuple.isMounted)
                }
            }
        }

        private fun decode(content: String, shareMap: MutableMap<String, ShareTuple>): String? {
            try {
                JsonReader(StringReader(content)).apply {
                    beginObject()
                    var uri: String? = null
                    var tuple: ShareTuple? = null
                    while (hasNext()) {
                        when (val name = nextName()) {
                            URI_KEY -> uri = nextString()
                            CREDENTIAL_TUPLE_KEY -> tuple = nextTuple()
                            else -> Log.w(TAG, "Ignoring unknown key $name")
                        }
                    }
                    endObject()
                    check(!(uri == null || tuple == null)) { "Either uri or tuple is null." }
                    shareMap[uri] = tuple
                    return uri
                }
            } catch (e: IOException) {
                Log.e(TAG, "Failed to load credential.")
                return null
            }
        }

        @Throws(IOException::class)
        private fun JsonReader.nextTuple(): ShareTuple {
            if (peek() == JsonToken.NULL) {
                nextNull()
                return ShareTuple.EMPTY_TUPLE
            }
            var workgroup: String? = null
            var username: String? = null
            var password: String? = null
            var mounted = true
            beginObject()
            while (hasNext()) {
                when (val name = nextName()) {
                    WORKGROUP_KEY -> workgroup = nextString()
                    USERNAME_KEY -> username = nextString()
                    PASSWORD_KEY -> password = nextString()
                    MOUNT_KEY -> {
                        mounted = nextBoolean()
                        Log.w(TAG, "Ignoring unknown key $name")
                    }
                    else -> Log.w(TAG, "Ignoring unknown key $name")
                }
            }
            endObject()
            return ShareTuple(workgroup!!, username!!, password!!, mounted)
        }
    }

    init {
        // Loading saved servers.
        val serverStringSet = mPref.getStringSet(SERVER_STRING_SET_KEY, emptySet())!!
        val shareMap = HashMap<String, ShareTuple>(serverStringSet.size)
        val forceEncryption: MutableList<String> = ArrayList()
        for (serverString in serverStringSet) {
            var decryptedString = serverString
            try {
                decryptedString = mEncryptionManager.decrypt(serverString)
            } catch (e: EncryptionException) {
                Log.i(TAG, "Failed to decrypt server data: ", e)
                forceEncryption.add(serverString)
            }
            val uri = decode(decryptedString, shareMap)
            if (uri != null) {
                mServerStringMap[uri] = serverString
            }
        }
        mServerStringSet = HashSet(serverStringSet)
        encryptServers(forceEncryption)
        for ((key, tuple) in shareMap) {
            if (tuple.isMounted) {
                mMountedServerSet.add(key)
            }
            mCredentialCache.putCredential(key, tuple.workgroup, tuple.username, tuple.password)
        }
    }
}