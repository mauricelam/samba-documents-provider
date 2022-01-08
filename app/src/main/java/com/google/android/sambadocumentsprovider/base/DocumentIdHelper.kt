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
package com.google.android.sambadocumentsprovider.base

import android.net.Uri
import android.net.nsd.NsdServiceInfo
import com.google.android.sambadocumentsprovider.document.DocumentMetadata
import com.google.android.sambadocumentsprovider.BuildConfig
import com.google.android.sambadocumentsprovider.base.DocumentIdHelper
import java.lang.RuntimeException

object DocumentIdHelper {
    @JvmStatic
    fun toRootId(metadata: DocumentMetadata): String {
        if (BuildConfig.DEBUG && !metadata.isFileShare) {
            throw RuntimeException("$metadata is not a file share.")
        }
        return metadata.uri.toString()
    }

    @JvmStatic
    fun toDocumentId(smbUri: Uri): String {
        // TODO: Change document ID to infer root.
        return smbUri.toString()
    }

    @JvmStatic
    fun toUri(documentId: String): Uri = Uri.parse(toUriString(documentId))

    @JvmStatic
    fun toUriString(documentId: String): String = documentId

    fun NsdServiceInfo.toUriString(): String {
        val port = port.takeUnless { it == 445 }?.let { ":$it" } ?: ""
        return "smb://${serviceName}.local${port}"
    }
}