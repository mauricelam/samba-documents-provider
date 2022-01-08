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
package com.google.android.sambadocumentsprovider.document

import android.net.Uri
import android.util.Log
import com.google.android.sambadocumentsprovider.nativefacade.SmbClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

private const val TAG = "LoadStat"

suspend fun loadStat(metadataMap: Map<Uri, DocumentMetadata>, client: SmbClient) {
    withContext(Dispatchers.IO) {
        for (metadata in metadataMap.values) {
            try {
                metadata.loadStat(client)
                if (!isActive) {
                    break
                }
            } catch (e: Exception) {
                // Failed to load a stat for a child... Just eat this exception, the only
                // consequence it may have is constantly retrying to fetch the stat.
                Log.e(TAG, "Failed to load stat for ${metadata.uri}")
            }
        }
    }
}