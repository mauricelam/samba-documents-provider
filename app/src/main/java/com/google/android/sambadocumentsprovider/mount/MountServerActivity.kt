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
package com.google.android.sambadocumentsprovider.mount

import android.app.ProgressDialog
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Uri
import android.net.nsd.NsdServiceInfo
import android.os.Bundle
import android.provider.DocumentsContract
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.google.android.sambadocumentsprovider.*
import com.google.android.sambadocumentsprovider.R
import com.google.android.sambadocumentsprovider.base.AuthFailedException
import com.google.android.sambadocumentsprovider.base.DocumentIdHelper
import com.google.android.sambadocumentsprovider.browsing.NetworkBrowser
import com.google.android.sambadocumentsprovider.cache.DocumentCache
import com.google.android.sambadocumentsprovider.document.DocumentMetadata
import com.google.android.sambadocumentsprovider.nativefacade.SmbClient
import com.google.android.sambadocumentsprovider.provider.SambaDocumentsProvider
import kotlinx.coroutines.launch

class MountServerActivity : AppCompatActivity() {
    private lateinit var cache: DocumentCache
    private lateinit var taskManager: TaskManager
    private lateinit var shareManager: ShareManager
    private lateinit var smbClient: SmbClient

    private lateinit var connectivityManager: ConnectivityManager

    private val serviceInfo: NsdServiceInfo? by lazy { intent.getParcelableExtra("serviceInfo") }

    private data class UiState(
        val serverUriState: MutableState<String>,
        val domainState: MutableState<String>,
        val usernameState: MutableState<String>,
        val passwordState: MutableState<String>,
        val sharePathState: MutableState<String>,
    ) {
        var serverUri by serverUriState
        var domain by domainState
        var username by usernameState
        var password by passwordState
        var sharePath by sharePathState

        fun clear() {
            domain = ""
            username = ""
            password = ""
            sharePath = ""
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val uiState = UiState(
                serverUriState = rememberSaveable {
                    mutableStateOf(
                        serviceInfo?.let { info ->
                            val port = info.port.takeIf { it != 445 }?.let { ":$it" } ?: ""
                            "smb://${info.serviceName}.local${port}"
                        } ?: "smb://"
                    )
                },
                domainState = rememberSaveable { mutableStateOf("") },
                usernameState = rememberSaveable { mutableStateOf("") },
                passwordState = rememberSaveable { mutableStateOf("") },
                sharePathState = rememberSaveable { mutableStateOf("") })

            MaterialTheme {
                Scaffold(
                    topBar = { AppBar() },
                    floatingActionButton = {
                        FloatingActionButton(onClick = { tryMount(uiState) }) {
                            Icon(Icons.Filled.Done, "Done")
                        }
                    }) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 8.dp, vertical = 8.dp)
                    ) {
                        TextField(
                            uiState.serverUri, { uiState.serverUri = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("URI") },
                            placeholder = { Text("smb://myserver.local") })
                        TextField(
                            uiState.domain, { uiState.domain = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Domain") })
                        TextField(
                            uiState.username, { uiState.username = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Username") })
                        TextField(
                            uiState.password, { uiState.password = it },
                            label = { Text("Password") },
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            modifier = Modifier
                                .fillMaxWidth()
                                .onKeyEvent { keyEvent ->
                                    if (keyEvent.type == KeyEventType.KeyUp
                                        && keyEvent.key == Key.Enter
                                    ) {
                                        tryMount(uiState)
                                        true
                                    } else {
                                        false
                                    }
                                })
                        ShareSelector(uiState)
                    }
                }
            }
        }

        cache = SambaProviderApplication.getDocumentCache(this)
        taskManager = SambaProviderApplication.getTaskManager(this)
        shareManager = SambaProviderApplication.getServerManager(this)
        smbClient = SambaProviderApplication.getSambaClient(this)
        connectivityManager = getSystemService(ConnectivityManager::class.java)
    }

    private fun tryMount(uiState: UiState) {
        if (connectivityManager.activeNetworkInfo?.isConnected != true) {
            return showMessage(R.string.no_active_network)
        }
        val host = parseServerHost(uiState.serverUri)
        val share = uiState.sharePath
        val metadata = DocumentMetadata.createShare(host, share)
        if (shareManager.isShareMounted(metadata.uri.toString())) {
            return showMessage(R.string.share_already_mounted)
        }
        cache.put(metadata)
        val dialog =
            ProgressDialog.show(this, null, getString(R.string.mounting_share), true)
        lifecycleScope.launch {
            try {
                mountServer(
                    metadata,
                    uiState.domain,
                    uiState.username,
                    uiState.password,
                    smbClient,
                    cache,
                    shareManager
                )
                uiState.clear()
                launchFileManager(metadata)
                showMessage(R.string.share_mounted)
                finish()
            } catch (e: Exception) {
                cache.remove(metadata.uri)
                showMessage(if (e is AuthFailedException) R.string.credential_error else R.string.failed_mounting)
            }
            dialog.dismiss()
        }
//        mTaskManager.runTask(metadata.uri, task)
    }

    @OptIn(ExperimentalMaterialApi::class)
    @Composable
    private fun ShareSelector(
        uiState: UiState,
    ) {
        var expanded by remember { mutableStateOf(false) }
        var loading by remember { mutableStateOf(false) }
        val browser = NetworkBrowser(smbClient)
        val availableShares = remember { mutableStateListOf<String>() }
        val coroutineScope = rememberCoroutineScope()

        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = {
            expanded = !expanded
            coroutineScope.launch {
                loading = true
                try {
                    availableShares.clear()
                    shareManager.addTemporaryCredentials(
                        uiState.serverUri + "/IPC$",
                        uiState.domain,
                        uiState.username,
                        uiState.password,
                    )
                    browser.getSharesAsync(uiState.serverUri).forEach { (server, shares) ->
                        android.util.Log.d("FINDME", "Server=$server, Share=$shares")
                        availableShares.addAll(shares)
                    }
                } catch (e: Exception) {
                    Log.e("FINDME", "Error loading dropdown", e)
                } finally {
                    loading = false
                }
            }
        }) {
            OutlinedTextField(
                value = uiState.sharePath,
                onValueChange = { uiState.sharePath = it },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                colors = ExposedDropdownMenuDefaults.textFieldColors(),
                label = { Text("Share") },
                readOnly = true
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                if (loading) {
                    CircularProgressIndicator()
                } else {
                    availableShares.forEach { share ->
                        DropdownMenuItem(
                            onClick = {
                                uiState.sharePath = share
                                expanded = false
                            }
                        ) {
                            Text(share)
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun AppBar() {
        var showMenu by remember { mutableStateOf(false) }
        TopAppBar(title = { Text(title.toString()) },
            navigationIcon = {
                IconButton(onClick = { finish() }) {
                    Icon(Icons.Default.ArrowBack, "Back")
                }
            },
            actions = {
                IconButton(onClick = { showMenu = !showMenu }) {
                    Icon(Icons.Default.MoreVert, "More")
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false }
                ) {
                    DropdownMenuItem(onClick = { showLicense() }) {
                        Text(text = "License")
                    }
                }
            })
    }

    private fun showLicense() {
        startActivity(Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("https://www.gnu.org/licenses/gpl-3.0-standalone.html")
        })
    }

    private fun showMessage(@StringRes id: Int) {
        Toast.makeText(this, id, Toast.LENGTH_SHORT).show()
    }

    private fun launchFileManager(metadata: DocumentMetadata) {
        val rootUri = DocumentsContract.buildRootUri(
            SambaDocumentsProvider.AUTHORITY, DocumentIdHelper.toRootId(metadata)
        )
        catchExceptions { launchFileManager(Intent.ACTION_VIEW, rootUri) }
            ?: catchExceptions { launchFileManager(ACTION_BROWSE, rootUri) }
            ?: Log.w(TAG, "Failed to find an activity to show mounted root.")
    }

    private fun launchFileManager(action: String, data: Uri) {
        startActivity(Intent(action).apply {
            this.data = data
            addCategory(Intent.CATEGORY_DEFAULT)
        })
    }

    private fun parseServerHost(path: String): String? {
        if (path.startsWith("\\")) {
            // Possibly Windows share path
            if (path.length == 1) {
                return null
            }
            val endCharacter = if (path.endsWith("\\")) path.length - 1 else path.length
            return path.substring(2, endCharacter).split("\\\\")[0]
        } else {
            // Try SMB URI
            val smbUri = Uri.parse(path)
            val host = smbUri.authority
            if (host.isNullOrEmpty()) return null
            return host
        }
    }

    companion object {
        private const val TAG = "MountServerActivity"
        private const val ACTION_BROWSE = "android.provider.action.BROWSE"
    }
}