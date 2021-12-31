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

import android.annotation.SuppressLint
import android.app.ProgressDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Uri
import android.net.nsd.NsdServiceInfo
import android.os.Bundle
import android.provider.DocumentsContract
import android.text.method.LinkMovementMethod
import android.util.Log
import android.view.*
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.google.android.sambadocumentsprovider.R
import com.google.android.sambadocumentsprovider.SambaProviderApplication
import com.google.android.sambadocumentsprovider.ShareManager
import com.google.android.sambadocumentsprovider.TaskManager
import com.google.android.sambadocumentsprovider.base.AuthFailedException
import com.google.android.sambadocumentsprovider.base.DocumentIdHelper
import com.google.android.sambadocumentsprovider.browsing.NetworkBrowser
import com.google.android.sambadocumentsprovider.cache.DocumentCache
import com.google.android.sambadocumentsprovider.document.DocumentMetadata
import com.google.android.sambadocumentsprovider.nativefacade.SmbClient
import com.google.android.sambadocumentsprovider.provider.SambaDocumentsProvider
import kotlinx.coroutines.launch

class MountServerActivity : AppCompatActivity() {
    private lateinit var mCache: DocumentCache
    private lateinit var mTaskManager: TaskManager
    private lateinit var mShareManager: ShareManager
    private lateinit var mClient: SmbClient
    private lateinit var mBrowsingAdapter: BrowsingAutocompleteAdapter
    private lateinit var mPasswordHideGroup: View
    private lateinit var mSharePathEditText: BrowsingAutocompleteTextView
    private lateinit var mDomainEditText: EditText
    private lateinit var mUsernameEditText: EditText
    private lateinit var mPasswordEditText: EditText
    private lateinit var mConnectivityManager: ConnectivityManager
    private var mNsdServiceInfo: NsdServiceInfo? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        mNsdServiceInfo = intent.getParcelableExtra("serviceInfo")

        mCache = SambaProviderApplication.getDocumentCache(this)
        mTaskManager = SambaProviderApplication.getTaskManager(this)
        mShareManager = SambaProviderApplication.getServerManager(this)
        mClient = SambaProviderApplication.getSambaClient(this)
        mPasswordHideGroup = findViewById(R.id.password_hide_group)
        mSharePathEditText = findViewById(R.id.share_path)
        mUsernameEditText = findViewById(R.id.username)
        mDomainEditText = findViewById(R.id.domain)
        mPasswordEditText = findViewById(R.id.password)
        mPasswordEditText.setOnKeyListener { _, _, keyEvent: KeyEvent ->
            if (keyEvent.action == KeyEvent.ACTION_UP
                && keyEvent.keyCode == KeyEvent.KEYCODE_ENTER
            ) {
                tryMount()
                true
            } else {
                false
            }
        }

        mNsdServiceInfo?.let { serviceInfo ->
            val port = serviceInfo.port.takeIf { it != 445 }?.let { ":$it" } ?: ""
            val uri = "smb://${serviceInfo.serviceName}.local${port}/shares"
            mSharePathEditText.setText(uri)
        }

        findViewById<Button>(R.id.mount).apply {
            setOnClickListener { tryMount() }
        }
        findViewById<Button>(R.id.cancel).apply {
            setOnClickListener { finish() }
        }

        // Set MovementMethod to make it respond to clicks on hyperlinks
        findViewById<TextView>(R.id.gplv3_link).apply {
            movementMethod = LinkMovementMethod.getInstance()
        }
        mConnectivityManager = getSystemService(ConnectivityManager::class.java)
        restoreSavedInstanceState(savedInstanceState)
        startBrowsing()
    }

    private fun restoreSavedInstanceState(savedInstanceState: Bundle?) {
        if (savedInstanceState == null) {
            return
        }
        mSharePathEditText.setText(savedInstanceState.getString(SHARE_PATH_KEY, ""))
        mDomainEditText.setText(savedInstanceState.getString(DOMAIN_KEY, ""))
        mUsernameEditText.setText(savedInstanceState.getString(USERNAME_KEY, ""))
        mPasswordEditText.setText(savedInstanceState.getString(PASSWORD_KEY, ""))
    }

    public override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(SHARE_PATH_KEY, mSharePathEditText.text.toString())
        outState.putString(DOMAIN_KEY, mDomainEditText.text.toString())
        outState.putString(USERNAME_KEY, mUsernameEditText.text.toString())
        outState.putString(PASSWORD_KEY, mPasswordEditText.text.toString())
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.activity, menu)
        return true
    }

    override fun onOptionsItemSelected(menuItem: MenuItem): Boolean {
        return when (menuItem.itemId) {
            R.id.send_feedback -> {
                sendFeedback()
                true
            }
            else -> false
        }
    }

    private fun sendFeedback() {
        val url = getString(R.string.feedback_link)
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.no_web_browser, Toast.LENGTH_LONG).show()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun startBrowsing() {
        mSharePathEditText.setOnTouchListener { _: View?, _: MotionEvent? ->
            mSharePathEditText.filter()
            false
        }
        mBrowsingAdapter = BrowsingAutocompleteAdapter()
        mSharePathEditText.setAdapter(mBrowsingAdapter)
        mSharePathEditText.threshold = 0
        val browser = NetworkBrowser(mClient, mTaskManager)
        lifecycleScope.launch {
            browser.getSharesAsync().forEach { (server, share) ->
                mBrowsingAdapter.addServer(server, share)
            }
            mBrowsingAdapter.finishLoading()
            if (mSharePathEditText.isPopupShowing) {
                mSharePathEditText.filter()
            }
        }
    }

    private fun tryMount() {
        val info = mConnectivityManager.activeNetworkInfo
        if (info == null || !info.isConnected) {
            showMessage(R.string.no_active_network)
            return
        }
        val path = parseSharePath()
        if (path == null) {
            showMessage(R.string.share_path_malformed)
            return
        }
        val (host, share) = path
        val domain = mDomainEditText.text.toString()
        val username = mUsernameEditText.text.toString()
        val password = mPasswordEditText.text.toString()
        val metadata = DocumentMetadata.createShare(host, share)
        if (mShareManager.isShareMounted(metadata.uri.toString())) {
            showMessage(R.string.share_already_mounted)
            return
        }
        mCache.put(metadata)
        val dialog = ProgressDialog.show(this, null, getString(R.string.mounting_share), true)
        val task = MountServerTask(
            metadata, domain, username, password, mClient, mCache, mShareManager)
        lifecycleScope.launch {
            try {
                task.execute()
                clearInputs()
                launchFileManager(metadata)
                showMessage(R.string.share_mounted)
                finish()
            } catch (e: Exception) {
                mCache.remove(metadata.uri)
                if (e is AuthFailedException) {
                    showMessage(R.string.credential_error)
                } else {
                    showMessage(R.string.failed_mounting)
                }
            }
            dialog.dismiss()
        }
//        mTaskManager.runTask(metadata.uri, task)
    }

    private fun showMessage(@StringRes id: Int) {
        Snackbar.make(mDomainEditText, id, Snackbar.LENGTH_SHORT).show()
    }

    private fun launchFileManager(metadata: DocumentMetadata) {
        val rootUri = DocumentsContract.buildRootUri(
            SambaDocumentsProvider.AUTHORITY, DocumentIdHelper.toRootId(metadata)
        )
        if (launchFileManager(Intent.ACTION_VIEW, rootUri)) {
            return
        }
        if (launchFileManager(ACTION_BROWSE, rootUri)) {
            return
        }
        Log.w(TAG, "Failed to find an activity to show mounted root.")
    }

    private fun launchFileManager(action: String, data: Uri): Boolean {
        return try {
            val intent = Intent(action)
            intent.addCategory(Intent.CATEGORY_DEFAULT)
            intent.data = data
            startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }

    private fun clearInputs() {
        mSharePathEditText.setText("")
        clearCredentials()
    }

    private fun clearCredentials() {
        mDomainEditText.setText("")
        mUsernameEditText.setText("")
        mPasswordEditText.setText("")
    }

    private fun parseSharePath(): Pair<String, String>? {
        val path = mSharePathEditText.text.toString()
        return if (path.startsWith("\\")) {
            // Possibly Windows share path
            if (path.length == 1) {
                return null
            }
            val endCharacter = if (path.endsWith("\\")) path.length - 1 else path.length
            val components = path.substring(2, endCharacter).split("\\\\")
            if (components.size == 2) Pair(components[0], components[1]) else null
        } else {
            // Try SMB URI
            val smbUri = Uri.parse(path)
            val host = smbUri.authority
            if (host.isNullOrEmpty()) return null
            val pathSegments = smbUri.pathSegments
            val share = pathSegments.getOrElse(0) { "~" }
            Pair(host, share)
        }
    }

    companion object {
        private const val TAG = "MountServerActivity"
        private const val ACTION_BROWSE = "android.provider.action.BROWSE"
        private const val SHARE_PATH_KEY = "sharePath"
        private const val DOMAIN_KEY = "domain"
        private const val USERNAME_KEY = "username"
        private const val PASSWORD_KEY = "password"
    }
}