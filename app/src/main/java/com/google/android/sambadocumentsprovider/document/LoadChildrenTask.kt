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

import com.google.android.sambadocumentsprovider.cache.DocumentCache
import com.google.android.sambadocumentsprovider.nativefacade.SmbClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

suspend fun DocumentMetadata.loadChildren(
    client: SmbClient,
    cache: DocumentCache
) {
    val metadata = this
    return withContext(Dispatchers.IO + NonCancellable) {
        loadChildren(client)
        children?.values?.forEach { _ ->
            cache.put(metadata)
        }
    }
}