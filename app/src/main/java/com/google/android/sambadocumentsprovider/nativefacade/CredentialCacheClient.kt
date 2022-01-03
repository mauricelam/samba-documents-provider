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

    override fun putCredential(
        uri: String,
        workgroup: String,
        username: String,
        password: String,
        overwrite: Boolean
    ) {
        MessageValues.obtain<String>().use { messageValues ->
            val msg = mHandler.obtainMessage(PUT_CREDENTIAL, messageValues)
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
            enqueue(mHandler.obtainMessage(REMOVE_CREDENTIAL, messageValues).apply {
                data.putString(URI_KEY, uri)
            })
        }
    }

    private class CredentialCacheHandler constructor(
        looper: Looper,
        private val mCredentialCacheImpl: CredentialCache
    ) : BaseHandler(looper) {
        public override fun processMessage(msg: Message) {
            val args = msg.peekData()
            val uri = args.getString(URI_KEY)!!
            when (msg.what) {
                PUT_CREDENTIAL -> {
                    val workgroup = args.getString(WORKGROUP_KEY)!!
                    val username = args.getString(USERNAME_KEY)!!
                    val password = args.getString(PASSWORD_KEY)!!
                    mCredentialCacheImpl.putCredential(uri, workgroup, username, password)
                }
                REMOVE_CREDENTIAL -> {
                    mCredentialCacheImpl.removeCredential(uri)
                }
                else -> throw UnsupportedOperationException("Unknown operation ${msg.what}")
            }
        }
    }

    companion object {
        private const val PUT_CREDENTIAL = 1
        private const val REMOVE_CREDENTIAL = 2
        private const val URI_KEY = "URI"
        private const val WORKGROUP_KEY = "WORKGROUP"
        private const val USERNAME_KEY = "USERNAME"
        private const val PASSWORD_KEY = "PASSWORD"
    }

    init {
        mHandler = CredentialCacheHandler(looper, credentialCacheImpl)
    }
}