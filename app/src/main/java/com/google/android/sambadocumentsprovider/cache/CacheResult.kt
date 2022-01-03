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

import androidx.core.util.Pools
import com.google.android.sambadocumentsprovider.document.DocumentMetadata
import com.google.android.sambadocumentsprovider.BuildConfig
import androidx.core.util.Pools.SynchronizedPool

data class CacheResult(
    var state: State = State.CACHE_MISS,
    private var _item: DocumentMetadata? = null
) : AutoCloseable {

    enum class State {
        CACHE_MISS, CACHE_HIT, CACHE_EXPIRED
    }

    val item: DocumentMetadata
        get() {
            check(state != State.CACHE_MISS)
            return _item!!
        }

    private fun setValue(state: State, item: DocumentMetadata?) {
        check(item != null || state == State.CACHE_MISS)
        this.state = state
        this._item = item
    }

    override fun close() {
        state = State.CACHE_MISS
        _item = null
        val recycled = POOL.release(this)
        check(!(BuildConfig.DEBUG && !recycled)) { "One item is not enough!" }
    }

    companion object {
        private val POOL: Pools.Pool<CacheResult> = SynchronizedPool(10)

        @JvmStatic
        fun obtain(state: State, item: DocumentMetadata?): CacheResult {
            return (POOL.acquire() ?: CacheResult()).apply { setValue(state, item) }
        }
    }
}