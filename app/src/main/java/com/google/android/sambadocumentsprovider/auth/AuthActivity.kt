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
import android.app.ProgressDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.google.android.sambadocumentsprovider.R
import com.google.android.sambadocumentsprovider.SambaProviderApplication
import com.google.android.sambadocumentsprovider.ShareManager
import com.google.android.sambadocumentsprovider.document.DocumentMetadata
import com.google.android.sambadocumentsprovider.nativefacade.SmbClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AuthActivity : AppCompatActivity() {
    private lateinit var mSharePathEditText: EditText
    private lateinit var mDomainEditText: EditText
    private lateinit var mUsernameEditText: EditText
    private lateinit var mPasswordEditText: EditText
    private lateinit var mPinShareCheckbox: CheckBox
    private lateinit var progressDialog: ProgressDialog
    private lateinit var mShareManager: ShareManager
    private lateinit var mClient: SmbClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val context = applicationContext
        mShareManager = SambaProviderApplication.getServerManager(context)
        mClient = SambaProviderApplication.getSambaClient(context)
        val authIntent = intent
        val shareUri = authIntent.getStringExtra(SHARE_URI_KEY)
        prepareUI(shareUri)
    }

    private fun prepareUI(shareUri: String?) {
        mSharePathEditText = findViewById(R.id.share_path)
        mUsernameEditText = findViewById(R.id.username)
        mDomainEditText = findViewById(R.id.domain)
        mPasswordEditText = findViewById(R.id.password)
        mPinShareCheckbox = findViewById(R.id.pin_share)
        mSharePathEditText.setText(shareUri)
        mSharePathEditText.isEnabled = false
        mPinShareCheckbox.visibility = View.VISIBLE
        val mLoginButton = findViewById<Button>(R.id.mount)
        mLoginButton.text = resources.getString(R.string.login)
        mLoginButton.setOnClickListener { tryAuth() }
        val cancel = findViewById<Button>(R.id.cancel)
        cancel.setOnClickListener { finish() }
    }

    private fun tryAuth() {
        progressDialog = ProgressDialog.show(
            this,
            null,
            getString(R.string.authenticating),
            true
        )
        val username = mUsernameEditText.text.toString()
        val password = mPasswordEditText.text.toString()
        if (username.isEmpty() || password.isEmpty()) {
            showMessage(R.string.empty_credentials)
            return
        }

        lifecycleScope.launch {
            try {
                runAuthorization(
                    mSharePathEditText.text.toString(),
                    username,
                    password,
                    mDomainEditText.text.toString(),
                    mPinShareCheckbox.isChecked,
                    mShareManager,
                    mClient
                )
                setResult(RESULT_OK)
                finish()
            } catch (e: Exception) {
                Log.i(TAG, "Authentication failed: ", e)
                showMessage(R.string.credential_error)
            }
            progressDialog.dismiss()
        }
    }

    private fun showMessage(@StringRes id: Int) {
        Snackbar.make(mPinShareCheckbox, id, Snackbar.LENGTH_SHORT).show()
    }

    @Throws(Exception::class)
    private suspend fun runAuthorization(
        mUri: String,
        mUser: String,
        mPassword: String,
        mDomain: String,
        mShouldPin: Boolean,
        mShareManager: ShareManager,
        mClient: SmbClient
    ) {
        withContext(Dispatchers.IO) {
            val shareMetadata = DocumentMetadata.createShare(Uri.parse(mUri))
            mShareManager.addOrUpdateServer(
                mUri,
                mDomain,
                mUser,
                mPassword,
                { shareMetadata.loadChildren(mClient) },
                mShouldPin
            )
        }
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