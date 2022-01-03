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

import android.app.Application
import android.content.Context
import com.google.android.sambadocumentsprovider.nativefacade.SmbFacade
import com.google.android.sambadocumentsprovider.browsing.NetworkBrowser
import com.google.android.sambadocumentsprovider.nativefacade.SambaMessageLooper
import android.net.ConnectivityManager
import android.net.NetworkRequest
import android.net.NetworkCapabilities
import android.net.ConnectivityManager.NetworkCallback
import android.net.Network
import android.util.Log
import com.google.android.sambadocumentsprovider.cache.DocumentCache
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

class SambaProviderApplication : Application() {
    private val cache = DocumentCache()
    private val taskManager = TaskManager()
    private lateinit var sambaClient: SmbFacade
    private lateinit var shareManager: ShareManager
    private lateinit var networkBrowser: NetworkBrowser
    private var initialized: Boolean = false

    override fun onCreate() {
        super.onCreate()
        init(this)
    }

    private fun initialize(context: Context) {
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
        val home = context.getDir("home", MODE_PRIVATE)
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
        val manager = context.getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        manager.registerNetworkCallback(
            NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                .build(),
            object : NetworkCallback() {
                override fun onAvailable(network: Network) {
                    sambaClient.reset()
                }
            })
    }

    companion object {
        private const val TAG = "SambaProviderApplication"
        fun init(context: Context?) {
            context ?: return
            getApplication(context).initialize(context)
        }

        fun getServerManager(context: Context): ShareManager = getApplication(context).shareManager

        fun getSambaClient(context: Context): SmbFacade = getApplication(context).sambaClient

        fun getDocumentCache(context: Context): DocumentCache = getApplication(context).cache

        fun getTaskManager(context: Context): TaskManager = getApplication(context).taskManager

        fun getNetworkBrowser(context: Context): NetworkBrowser =
            getApplication(context).networkBrowser

        private fun getApplication(context: Context): SambaProviderApplication =
            context.applicationContext as SambaProviderApplication
    }
}