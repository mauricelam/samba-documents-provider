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
package com.google.android.sambadocumentsprovider.cache

import android.net.Uri
import com.google.android.sambadocumentsprovider.cache.CacheResult.Companion.obtain
import com.google.android.sambadocumentsprovider.document.DocumentMetadata
import com.google.android.sambadocumentsprovider.document.DocumentMetadata.Companion.buildParentUri
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.minutes

class DocumentCache {
    private val cache = ConcurrentHashMap<Uri, DocumentMetadata>()
    private val exceptionCache = ConcurrentHashMap<Uri, Exception>()

    operator fun get(uri: Uri): CacheResult {
        val metadata = cache[uri] ?: return obtain(CacheResult.State.CACHE_MISS, null)
        return if (metadata.timeStamp + CACHE_EXPIRATION.inWholeMilliseconds < System.currentTimeMillis()) {
            obtain(CacheResult.State.CACHE_EXPIRED, metadata)
        } else obtain(CacheResult.State.CACHE_HIT, metadata)
    }

    @Throws(Exception::class)
    fun throwLastExceptionIfAny(uri: Uri) {
        exceptionCache.remove(uri)?.let { throw it }
    }

    fun put(metadata: DocumentMetadata) {
        cache[metadata.uri] = metadata
        val parentUri = buildParentUri(metadata.uri)
        cache[parentUri]?.putChild(metadata)
    }

    fun put(uri: Uri, e: Exception) {
        exceptionCache[uri] = e
    }

    fun remove(uri: Uri) {
        cache.remove(uri)
        exceptionCache.remove(uri)
        val parentUri = buildParentUri(uri)
        cache[parentUri]?.children?.remove(uri)
    }

    companion object {
        private val CACHE_EXPIRATION = 1.minutes
    }
}