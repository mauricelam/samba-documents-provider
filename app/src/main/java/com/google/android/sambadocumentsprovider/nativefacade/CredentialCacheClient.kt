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

import android.os.Looper
import android.os.Message

internal class CredentialCacheClient(looper: Looper, credentialCacheImpl: CredentialCache) :
    BaseClient(), CredentialCache {

    override val handler: BaseHandler = CredentialCacheHandler(looper, credentialCacheImpl)

    override fun putCredential(uri: String, workgroup: String, username: String, password: String) {
        MessageValues.obtain<String>().use { messageValues ->
            val msg = handler.obtainMessage(PUT_CREDENTIAL, messageValues)
            msg.data.apply {
                putString(URI_KEY, uri)
                putString(WORKGROUP_KEY, workgroup)
                putString(USERNAME_KEY, username)
                putString(PASSWORD_KEY, password)
            }
            enqueue(msg)
        }
    }

    override fun removeCredential(uri: String) {
        MessageValues.obtain<String>().use { messageValues ->
            enqueue(handler.obtainMessage(REMOVE_CREDENTIAL, messageValues).apply {
                data.putString(URI_KEY, uri)
            })
        }
    }

    override fun setTempMode(tempMode: Boolean) {
        MessageValues.obtain<String>().use { messageValues ->
            enqueue(handler.obtainMessage(SET_TEMP_MODE, messageValues).apply {
                data.putBoolean("tempMode", tempMode)
            })
        }
    }

    private class CredentialCacheHandler constructor(
        looper: Looper,
        private val credentialCacheImpl: CredentialCache
    ) : BaseHandler(looper) {
        override fun processMessage(msg: Message) {
            val args = msg.peekData()
            when (msg.what) {
                PUT_CREDENTIAL -> {
                    val uri = args.getString(URI_KEY)!!
                    val workgroup = args.getString(WORKGROUP_KEY)!!
                    val username = args.getString(USERNAME_KEY)!!
                    val password = args.getString(PASSWORD_KEY)!!
                    credentialCacheImpl.putCredential(uri, workgroup, username, password)
                }
                REMOVE_CREDENTIAL -> {
                    val uri = args.getString(URI_KEY)!!
                    credentialCacheImpl.removeCredential(uri)
                }
                SET_TEMP_MODE -> credentialCacheImpl.setTempMode(args.getBoolean("tempMode"))
                else -> throw UnsupportedOperationException("Unknown operation ${msg.what}")
            }
        }
    }

    companion object {
        private const val PUT_CREDENTIAL = 1
        private const val REMOVE_CREDENTIAL = 2
        private const val SET_TEMP_MODE = 3
        private const val URI_KEY = "URI"
        private const val WORKGROUP_KEY = "WORKGROUP"
        private const val USERNAME_KEY = "USERNAME"
        private const val PASSWORD_KEY = "PASSWORD"
    }
}