/*
 * Copyright 2022 Google Inc.
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

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import com.google.android.sambadocumentsprovider.browsing.NetworkBrowser
import com.google.android.sambadocumentsprovider.cache.DocumentCache
import com.google.android.sambadocumentsprovider.nativefacade.SambaMessageLooper
import com.google.android.sambadocumentsprovider.nativefacade.SmbFacade
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

private const val TAG = "Components"

object Components {
    val documentCache = DocumentCache()
    val taskManager = TaskManager()
    lateinit var sambaClient: SmbFacade
        private set
    lateinit var shareManager: ShareManager
        private set
    lateinit var networkBrowser: NetworkBrowser
        private set
    private var initialized: Boolean = false

    fun initialize(context: Context) {
        if (initialized) {
            // Already initialized.
            return
        }
        initialized = true
        val looper = SambaMessageLooper()
        val credentialCache = looper.credentialCache
        sambaClient = looper.client
        shareManager = ShareManager(context, credentialCache)
        networkBrowser = NetworkBrowser(sambaClient)
        initializeSambaConf(context)
        registerNetworkCallback(context)
    }

    private fun initializeSambaConf(context: Context) {
        val home = context.getDir("home", Application.MODE_PRIVATE)
        val share = context.getExternalFilesDir(null)
        val sambaConf = SambaConfiguration(home, share)

        // Sync from external folder. The reason why we don't use external folder directly as HOME
        // is because there are cases where external storage is not ready, and we don't have an
        // external folder at all.
        GlobalScope.launch {
            if (sambaConf.syncFromExternal()) {
                if (BuildConfig.DEBUG) Log.d(
                    TAG, "Syncing smb.conf from external folder. No need to flush default config."
                )
            } else {
                sambaConf.flushDefault()
            }
            sambaClient.reset()
        }
    }

    private fun registerNetworkCallback(context: Context) {
        val manager =
            context.getSystemService(Application.CONNECTIVITY_SERVICE) as ConnectivityManager
        manager.registerNetworkCallback(
            NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                .build(),
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    sambaClient.reset()
                }
            })
    }
}