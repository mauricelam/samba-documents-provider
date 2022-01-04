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
package com.google.android.sambadocumentsprovider.auth

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.lifecycle.lifecycleScope
import com.google.android.sambadocumentsprovider.*
import com.google.android.sambadocumentsprovider.R
import com.google.android.sambadocumentsprovider.document.DocumentMetadata
import com.google.android.sambadocumentsprovider.nativefacade.SmbClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AuthActivity : AppCompatActivity() {
    private lateinit var shareManager: ShareManager
    private lateinit var client: SmbClient

    private interface UiState {
        val shareUri: String
        var username: String
        var password: String
        var domain: String
        var loading: Boolean
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        shareManager = Components.shareManager
        client = Components.sambaClient
        val shareUri = intent.getStringExtra(SHARE_URI_KEY)
            ?: return finish().also { Log.e(TAG, "Missing SHARE_URI_KEY") }

        setContent {
            val state = object : UiState {
                override val shareUri = shareUri
                override var username by rememberSaveable { mutableStateOf("") }
                override var password by rememberSaveable { mutableStateOf("") }
                override var domain by rememberSaveable { mutableStateOf("") }
                override var loading by rememberSaveable { mutableStateOf(false) }
            }

            MaterialTheme {
                AlertDialog(onDismissRequest = { finish() },
                    confirmButton = {
                        Button(onClick = { tryAuth(state) }) { Text("Login") }
                    },
                    dismissButton = {
                        TextButton(onClick = { finish() }) { Text("Cancel") }
                    },
                    title = { Text("Authentication required") },
                    text = {
                        if (state.loading) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center
                            ) {
                                CircularProgressIndicator()
                            }
                        } else {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                TextField(state::shareUri, label = { Text("URI") })
                                TextField(state::domain, label = { Text("Domain") })
                                TextField(state::username, label = { Text("Username") })
                                PasswordField(state::password, label = { Text("Password") })
                            }
                        }
                    })
            }
        }
    }

    private fun tryAuth(state: UiState) {
        state.loading = true
        if (state.username.isEmpty() || state.password.isEmpty()) {
            showMessage(R.string.empty_credentials)
            return
        }

        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val shareMetadata = DocumentMetadata.createShare(Uri.parse(state.shareUri))
                    shareManager.addOrUpdateServer(
                        state.shareUri,
                        state.domain,
                        state.username,
                        state.password,
                        { shareMetadata.loadChildren(client) },
                        false
                    )
                }
                setResult(RESULT_OK)
                finish()
            } catch (e: Exception) {
                Log.i(TAG, "Authentication failed: ", e)
                showMessage(R.string.credential_error)
            } finally {
                state.loading = false
            }
        }
    }

    private fun showMessage(@StringRes id: Int) {
        Toast.makeText(this, id, Toast.LENGTH_LONG).show()
    }

    companion object {
        private const val TAG = "AuthActivity"
        private const val SHARE_URI_KEY = "shareUri"

        @JvmStatic
        fun createAuthIntent(context: Context, shareUri: String?): PendingIntent {
            val authIntent = Intent()
            authIntent.component = ComponentName(
                context.packageName,
                AuthActivity::class.java.name
            )
            authIntent.putExtra(SHARE_URI_KEY, shareUri)
            return PendingIntent.getActivity(
                context,
                0,
                authIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }
}