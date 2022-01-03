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
package com.google.android.sambadocumentsprovider.provider

import androidx.core.util.Pools
import androidx.core.util.Pools.SynchronizedPool
import java.nio.ByteBuffer

class ByteBufferPool {
    private val bufferPool: Pools.Pool<ByteBuffer> = SynchronizedPool(16)

    fun obtainBuffer(): ByteBuffer {
        return bufferPool.acquire() ?: ByteBuffer.allocateDirect(BUFFER_CAPACITY)
    }

    fun recycleBuffer(buffer: ByteBuffer) {
        buffer.clear()
        bufferPool.release(buffer)
    }

    companion object {
        private const val BUFFER_CAPACITY = 1024 * 1024
    }
}