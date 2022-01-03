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
import androidx.compose.foundation.clickable
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
import com.google.android.sambadocumentsprovider.R
import com.google.android.sambadocumentsprovider.SambaProviderApplication
import com.google.android.sambadocumentsprovider.ShareManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*

class ServerListActivity : AppCompatActivity() {

    private lateinit var shareManager: ShareManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        shareManager = SambaProviderApplication.getServerManager(this)
        val serverList = MdnsManager.discover(this, "_smb._tcp")
            .onEach { Log.d("FINDME", "Found servers: $it") }
            .catch { e -> Log.e("FINDME", "Error in server list", e) }
        setContent {
            MaterialTheme {
                Scaffold(
                    topBar = { TopAppBar(title = { Text(title.toString()) }) },
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

    private fun getMountedServers(): StateFlow<List<String>> {
        return callbackFlow<List<String>> {
            val listener: () -> Unit = { trySend(shareManager.getShares()) }
            shareManager.addListener(listener)

            awaitClose { shareManager.removeListener(listener) }
        }.stateIn(lifecycleScope, SharingStarted.Eagerly, shareManager.getShares())
    }

    private fun addServerActivity(serviceInfo: NsdServiceInfo?) {
        startActivity(Intent(this, MountServerActivity::class.java).apply {
            putExtra("serviceInfo", serviceInfo)
        })
    }

    @OptIn(ExperimentalMaterialApi::class)
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
                        .clickable { addServerActivity(serviceInfo) },
                    icon = { Icon(Icons.Filled.Dns, "") }
                ) {
                    val portDescription = serviceInfo.port.takeIf { it != 455 }?.let { ":$it" }
                    Text(text = "${serviceInfo.serviceName}${portDescription}")
                }
            }
            items(savedServerListState) { serverName ->
                if (!serverName.endsWith("IPC$")) {
                    ListItem(
                        modifier = Modifier.clickable { viewMountedDriveActivity(serverName) },
                        icon = { Icon(Icons.Filled.Favorite, "") },
                        trailing = {
                            IconButton(onClick = { shareManager.unmountServer(serverName) }) {
                                Icon(Icons.Filled.Eject, "Eject")
                            }
                        }
                    ) {
                        Text(text = serverName)
                    }
                }
            }
        }
    }

    private fun viewMountedDriveActivity(serverName: String) {
        startActivity(Intent(Intent.ACTION_VIEW).apply {
            addCategory(Intent.CATEGORY_DEFAULT)
            setDataAndType(
                DocumentsContract.buildRootUri(
                    "com.google.android.sambadocumentsprovider",
                    serverName
                ),
                DocumentsContract.Root.MIME_TYPE_ITEM
            )
        })
    }
}