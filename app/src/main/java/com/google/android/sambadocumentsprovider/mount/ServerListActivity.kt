/*
 * Copyright 2021 Google Inc.
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

package com.google.android.sambadocumentsprovider.mount

import android.content.Intent
import android.net.nsd.NsdServiceInfo
import android.os.Bundle
import android.provider.DocumentsContract
import android.util.Log
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Eject
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.google.android.sambadocumentsprovider.*
import com.google.android.sambadocumentsprovider.R
import com.google.android.sambadocumentsprovider.base.DocumentIdHelper.toUriString
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*

@ExperimentalFoundationApi
@OptIn(ExperimentalMaterialApi::class)
class ServerListActivity : AppCompatActivity() {

    private lateinit var shareManager: ShareManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        shareManager = Components.shareManager
        val serverList = MdnsManager.discover(this, SMB_MDNS_SERVICE_NAME)
            .onEach { Log.d(TAG, "Found servers: $it") }
            .catch { e -> Log.e(TAG, "Error in server list", e) }
        setContent {
            MaterialTheme {
                Scaffold(
                    topBar = { AppBar() },
                    floatingActionButton = {
                        FloatingActionButton(onClick = { addServerActivity(null) }) {
                            Icon(Icons.Filled.Add, getString(R.string.add))
                        }
                    }) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        ServerList(serverList, getMountedServers())
                    }
                }
            }
        }
    }

    @Composable
    private fun AppBar() {
        TopAppBar(title = { Text(title.toString()) },
            navigationIcon = { BackButton() },
            actions = {
                OverflowMenu {
                    LicenseMenuItem()
                }
            })
    }

    private fun getMountedServers(): StateFlow<List<String>> {
        return callbackFlow {
            val listener: () -> Unit = { trySend(shareManager.getShareUris()) }
            shareManager.addListener(listener)

            awaitClose { shareManager.removeListener(listener) }
        }.stateIn(lifecycleScope, SharingStarted.Eagerly, shareManager.getShareUris())
    }

    private fun addServerActivity(serverUri: String?) {
        startActivity(Intent(this, MountServerActivity::class.java).apply {
            if (serverUri != null) {
                putExtra("serverUri", serverUri)
            }
        })
    }

    @Composable
    fun ServerList(
        serverList: Flow<List<NsdServiceInfo>>,
        savedServerList: StateFlow<List<String>>
    ) {
        val serverListState by serverList.collectAsState(initial = emptyList())
        val savedServerListState by savedServerList.collectAsState()
        LazyColumn {
            items(serverListState) { serviceInfo ->
                ListItem(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { addServerActivity(serviceInfo.toUriString()) },
                    icon = { Icon(Icons.Filled.Dns, "") }
                ) {
                    Text(serviceInfo.toUriString())
                }
            }
            items(savedServerListState) { serverUri ->
                ListItem(
                    modifier = Modifier.combinedClickable(
                        onLongClick = { addServerActivity(serverUri) },
                        onClick = { viewMountedDriveActivity(serverUri) }),
                    icon = { Icon(Icons.Filled.Favorite, "") },
                    trailing = {
                        IconButton(onClick = { shareManager.unmountServer(serverUri) }) {
                            Icon(Icons.Filled.Eject, "Eject")
                        }
                    }
                ) {
                    Text(serverUri)
                }
            }
        }
    }

    private fun viewMountedDriveActivity(smbUri: String) {
        startActivity(Intent(Intent.ACTION_VIEW).apply {
            addCategory(Intent.CATEGORY_DEFAULT)
            setDataAndType(
                DocumentsContract.buildRootUri(
                    "com.google.android.sambadocumentsprovider",
                    smbUri
                ),
                DocumentsContract.Root.MIME_TYPE_ITEM
            )
        })
    }

    companion object {
        private const val TAG = "ServerListActivity"
        private const val SMB_MDNS_SERVICE_NAME = "_smb._tcp"
    }
}