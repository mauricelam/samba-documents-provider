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

import android.content.Intent
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.util.Log
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
    private val networkBrowser by lazy { NetworkBrowser(smbClient, Components.credentialsCache) }

    private lateinit var connectivityManager: ConnectivityManager
    private var editMode = false

    private interface UiState {
        var serverUri: String
        var domain: String
        var needsAuth: Boolean
        var username: String
        var password: String
        var sharePath: String
        val availableShares: MutableList<String>
        var mounting: Boolean
        val scaffoldState: ScaffoldState

        fun clearServerFields() {
            needsAuth = false
            domain = ""
            username = ""
            password = ""
            sharePath = ""
            mounting = false
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        connectivityManager = getSystemService(ConnectivityManager::class.java)

        setContent {
            val uiState = object : UiState {
                override var serverUri by composeState("")
                override var domain by composeState("")
                override var username by composeState("")
                override var password by composeState("")
                override var sharePath by composeState("")
                override var needsAuth by composeState(false)
                override var mounting by composeState(false, saveable = false)
                override val scaffoldState = rememberScaffoldState()
                override val availableShares = remember { mutableStateListOf<String>() }
            }

            intent.getStringExtra("serverUri").takeUnless { it.isNullOrEmpty() }?.let { shareUri ->
                val (host, share) = parseShareUri(shareUri) ?: return@let
                uiState.serverUri = "smb://$host"
                uiState.sharePath = share ?: ""
                if (shareUri.isNotEmpty()) {
                    shareManager.getShareTuple(shareUri)?.let { tuple ->
                        editMode = true
                        uiState.domain = tuple.workgroup
                        uiState.username = tuple.username
                        uiState.password = tuple.password
                        uiState.needsAuth = tuple.username.isNotEmpty()
                    }
                    LaunchedEffect(uiState) {
                        loadAvailableShares(uiState)
                    }
                }
            }

            MaterialTheme {
                Scaffold(
                    scaffoldState = uiState.scaffoldState,
                    topBar = { AppBar() },
                    floatingActionButton = {
                        if (!uiState.mounting) {
                            FloatingActionButton(onClick = { tryMount(uiState) }) {
                                Icon(Icons.Filled.Done, "Done")
                            }
                        }
                    }) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 8.dp, vertical = 8.dp)
                    ) {
                        if (uiState.mounting) {
                            Column(
                                modifier = Modifier.fillMaxSize(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Row { CircularProgressIndicator(Modifier.padding(8.dp)) }
                                Row { Text(getString(R.string.mounting_share)) }
                            }
                        } else {
                            ServerFields(uiState)
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun ServerFields(uiState: UiState) {
        val coroutineScope = rememberCoroutineScope()
        val focusManager = LocalFocusManager.current
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
        DropdownSelector(
            uiState::sharePath,
            loadDropdown = { loadAvailableShares(uiState) }) {
            uiState.availableShares.forEach { share ->
                DropdownMenuItem(onClick = { uiState.sharePath = share }) {
                    Text(share)
                }
            }
        }
    }

    inner class UserFriendlyException(msg: String) : Exception(msg) {
        constructor(@StringRes msg: Int) : this(getString(msg))
    }

    private fun tryMount(uiState: UiState) {
        try {
            tryMountImpl(uiState)
        } catch (e: UserFriendlyException) {
            uiState.showMessage(e.message!!)
        }
    }

    private inline fun userCheck(condition: Boolean, block: () -> String) {
        if (!condition) throw UserFriendlyException(block())
    }

    private fun tryMountImpl(uiState: UiState) {
        userCheck(uiState.sharePath.isNotEmpty()) { "Share path must not be empty" }
        userCheck(connectivityManager.activeNetworkInfo?.isConnected == true) {
            getString(R.string.no_active_network)
        }
        val (host, share) = parseShareUri(uiState.serverUri)
            ?: throw UserFriendlyException("Unable to parse server URI ${uiState.serverUri}")
        userCheck(share == null) { "Share must not be specified in server URI" }
        val metadata = DocumentMetadata.createShare(host, uiState.sharePath)
        cache.put(metadata)
        uiState.mounting = true
        lifecycleScope.launch {
            try {
                mountServer(metadata, uiState.domain, uiState.username, uiState.password)
                uiState.clearServerFields()
                launchFileManager(metadata)
                uiState.showMessage(R.string.share_mounted)
                finish()
            } catch (e: Exception) {
                cache.remove(metadata.uri)
                uiState.showMessage(if (e is AuthFailedException) R.string.credential_error else R.string.failed_mounting)
            }
            uiState.mounting = false
        }
//        taskManager.runTask(metadata.uri, task)
    }

    private suspend fun mountServer(
        metadata: DocumentMetadata,
        domain: String,
        username: String,
        password: String,
    ) {
        try {
            withContext(Dispatchers.IO) {
                shareManager.addServer(
                    metadata.uri.toString(),
                    domain,
                    username,
                    password,
                    checker = { metadata.loadChildren(smbClient) },
                    mount = true,
                    updateExisting = editMode
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
            val shares = networkBrowser.getShares(
                state.serverUri,
                state.domain,
                state.username,
                state.password
            )
            state.availableShares.addAll(shares)
        } catch (e: AuthFailedException) {
            if (!state.needsAuth) {
                state.needsAuth = true
            } else {
                // Auth still fails!
                state.showMessage("Authentication failed. Check your username and password")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading dropdown", e)
            state.showMessage(e.message ?: "Unknown error loading dropdown")
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

    private fun UiState.showMessage(@StringRes id: Int) = showMessage(getString(id))
    private fun UiState.showMessage(message: String) {
        lifecycleScope.launch {
            scaffoldState.snackbarHostState.showSnackbar(message, actionLabel = "Dismiss")
        }
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

    private fun parseShareUri(path: String): Pair<String, String?>? {
        if (path.startsWith("\\")) {
            // Possibly Windows share path
            if (path.length == 1) {
                return null
            }
            val endCharacter = if (path.endsWith("\\")) path.length - 1 else path.length
            val segments = path.substring(2, endCharacter).split("\\\\")
            return segments[0] to segments.getOrNull(1)
        } else {
            // Try SMB URI
            val smbUri = Uri.parse(path)
            val host = smbUri.authority
            if (host.isNullOrEmpty()) return null
            check(smbUri.pathSegments.size <= 1) { "SMB URI should not have segment after share" }
            return host to smbUri.pathSegments.getOrNull(0)
        }
    }

    companion object {
        private const val TAG = "MountServerActivity"
        private const val ACTION_BROWSE = "android.provider.action.BROWSE"
    }
}