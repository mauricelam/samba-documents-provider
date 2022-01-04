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
import android.os.Bundle
import android.provider.DocumentsContract
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Done
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.google.android.sambadocumentsprovider.*
import com.google.android.sambadocumentsprovider.R
import com.google.android.sambadocumentsprovider.base.AuthFailedException
import com.google.android.sambadocumentsprovider.base.DocumentIdHelper
import com.google.android.sambadocumentsprovider.browsing.NetworkBrowser
import com.google.android.sambadocumentsprovider.document.DocumentMetadata
import com.google.android.sambadocumentsprovider.provider.SambaDocumentsProvider
import kotlinx.coroutines.*

class MountServerActivity : AppCompatActivity() {
    private val cache by lazy { Components.documentCache }
    private val shareManager by lazy { Components.shareManager }
    private val smbClient by lazy { Components.sambaClient }
    private val networkBrowser by lazy { NetworkBrowser(smbClient) }

    private lateinit var connectivityManager: ConnectivityManager

    private interface UiState {
        var serverUri: String
        var domain: String
        var needsAuth: Boolean
        var username: String
        var password: String
        var sharePath: String
        val availableShares: MutableList<String>

        fun clear() {
            needsAuth = false
            domain = ""
            username = ""
            password = ""
            sharePath = ""
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        connectivityManager = getSystemService(ConnectivityManager::class.java)

        setContent {
            val coroutineScope = rememberCoroutineScope()
            val uiState = object : UiState {
                override var serverUri by rememberSaveable {
                    mutableStateOf(intent.getStringExtra("serverUri") ?: "")
                }
                override var domain by rememberSaveable { mutableStateOf("") }
                override var username by rememberSaveable { mutableStateOf("") }
                override var password by rememberSaveable { mutableStateOf("") }
                override var sharePath by rememberSaveable { mutableStateOf("") }
                override var needsAuth by rememberSaveable { mutableStateOf(false) }
                override val availableShares = remember {
                    mutableStateListOf<String>().also {
                        if (serverUri.isNotEmpty()) {
                            val uiState = this
                            coroutineScope.launch {
                                loadAvailableShares(uiState)
                            }
                        }
                    }
                }
            }
            val focusManager = LocalFocusManager.current

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
                            uiState::serverUri,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("URI") },
                            placeholder = { Text("smb://myserver.local") },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                            keyboardActions = KeyboardActions {
                                coroutineScope.launch {
                                    loadAvailableShares(uiState)
                                    // Add delay so UI changes are propagated before moving focus
                                    delay(1)
                                    focusManager.moveFocus(FocusDirection.Next)
                                }
                            }
                        )
                        if (uiState.needsAuth) {
                            TextField(
                                uiState::domain,
                                modifier = Modifier.fillMaxWidth(),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                                label = { Text("Domain") })
                            TextField(
                                uiState::username,
                                modifier = Modifier.fillMaxWidth(),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                                label = { Text("Username") })
                            PasswordField(
                                uiState::password,
                                label = { Text("Password") },
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        ShareSelector(uiState)
                    }
                }
            }
        }
    }

    private fun tryMount(uiState: UiState) {
        check(uiState.sharePath.isNotEmpty()) { "Share path must not be empty" }
        if (connectivityManager.activeNetworkInfo?.isConnected != true) {
            return showMessage(R.string.no_active_network)
        }
        val metadata = DocumentMetadata.createShare(
            parseServerHost(uiState.serverUri),
            uiState.sharePath
        )
        if (shareManager.isShareMounted(metadata.uri.toString())) {
            return showMessage(R.string.share_already_mounted)
        }
        cache.put(metadata)
        val dialog =
            ProgressDialog.show(this, null, getString(R.string.mounting_share), true)
        lifecycleScope.launch {
            try {
                mountServer(metadata, uiState.domain, uiState.username, uiState.password)
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
//        taskManager.runTask(metadata.uri, task)
    }

    private suspend fun mountServer(
        metadata: DocumentMetadata,
        domain: String,
        username: String,
        password: String
    ) {
        try {
            withContext(Dispatchers.IO) {
                shareManager.addServer(
                    metadata.uri.toString(), domain, username, password,
                    checker = { metadata.loadChildren(smbClient) }, mount = true
                )
            }
            for (m in metadata.children!!.values) {
                cache.put(m)
            }
        } catch (e: CancellationException) {
            // User cancelled the task, unmount it regardless of its result.
            Log.w(TAG, "mountServer cancelled", e)
            shareManager.unmountServer(metadata.uri.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to mount share.", e)
            throw e
        }
    }

    private suspend fun loadAvailableShares(state: UiState) {
        try {
            state.availableShares.clear()
            shareManager.addTemporaryCredentials(
                "smb://${parseServerHost(state.serverUri)}/IPC$",
                state.domain,
                state.username,
                state.password,
            )
            networkBrowser.getSharesAsync(state.serverUri).forEach { (server, shares) ->
                android.util.Log.d("FINDME", "Server=$server, Share=$shares")
                state.availableShares.addAll(shares)
            }
        } catch (e: AuthFailedException) {
            if (!state.needsAuth) {
                state.needsAuth = true
            } else {
                // Auth still fails!
                showMessage("Authentication failed. Check your username and password")
            }
        } catch (e: Exception) {
            Log.e("FINDME", "Error loading dropdown", e)
            showMessage(e.message ?: "Unknown error loading dropdown")
        }
    }

    @OptIn(ExperimentalMaterialApi::class)
    @Composable
    private fun ShareSelector(state: UiState) {
        DropdownSelector(state::sharePath, loadDropdown = { loadAvailableShares(state) }) {
            state.availableShares.forEach { share ->
                DropdownMenuItem(onClick = { state.sharePath = share }) {
                    Text(share)
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

    private fun showMessage(@StringRes id: Int) {
        Toast.makeText(this, id, Toast.LENGTH_SHORT).show()
    }

    private fun showMessage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
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
            check(smbUri.pathSegments.size == 0) { "SMB URI should not have path segments" }
            return host
        }
    }

    companion object {
        private const val TAG = "MountServerActivity"
        private const val ACTION_BROWSE = "android.provider.action.BROWSE"
    }
}