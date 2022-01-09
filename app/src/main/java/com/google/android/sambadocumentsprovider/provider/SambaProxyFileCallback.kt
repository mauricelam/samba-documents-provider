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

import android.annotation.TargetApi
import android.os.ProxyFileDescriptorCallback
import android.system.ErrnoException
import android.system.OsConstants
import android.system.StructStat
import android.util.Log
import com.google.android.sambadocumentsprovider.nativefacade.SmbFile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import java.io.IOException

@TargetApi(26)
class SambaProxyFileCallback(
    private val uri: String,
    private val file: SmbFile,
    private val bufferPool: ByteBufferPool,
) : ProxyFileDescriptorCallback() {

    private val _deferred = CompletableDeferred<Unit>()
    val deferred: Deferred<Unit>
        get() = _deferred

    @Throws(ErrnoException::class)
    override fun onGetSize(): Long {
        val stat: StructStat
        try {
            stat = file.fstat()
            return stat.st_size
        } catch (e: IOException) {
            throwErrnoException(e)
        }
        return 0
    }

    @Throws(ErrnoException::class)
    override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
        val buffer = bufferPool.obtainBuffer()
        try {
            file.seek(offset)
            var readSize = 0
            var total = 0
            while (size > total && file.read(buffer, size - total).also { readSize = it } > 0) {
                buffer[data, total, readSize]
                buffer.clear()
                total += readSize
            }
            return total
        } catch (e: IOException) {
            throwErrnoException(e)
        }
        return 0
    }

    @Throws(ErrnoException::class)
    override fun onWrite(offset: Long, size: Int, data: ByteArray): Int {
        var written = 0
        val buffer = bufferPool.obtainBuffer()
        try {
            file.seek(offset)
            while (written < size) {
                val willWrite = Math.min(size - written, buffer.capacity())
                buffer.put(data, written, willWrite)
                val res = file.write(buffer, willWrite)
                written += res
                buffer.clear()
            }
        } catch (e: IOException) {
            throwErrnoException(e)
        } finally {
            bufferPool.recycleBuffer(buffer)
        }
        return written
    }

    @Throws(ErrnoException::class)
    override fun onFsync() {
        // Nothing to do
    }

    override fun onRelease() {
        try {
            file.close()
        } catch (e: IOException) {
            Log.e(TAG, "Failed to close file", e)
        }
        _deferred.complete(Unit)
    }

    @Throws(ErrnoException::class)
    private fun throwErrnoException(e: IOException) {
        // Hack around that SambaProxyFileCallback throws ErrnoException rather than IOException
        // assuming the underlying cause is an ErrnoException.
        when (val cause = e.cause) {
            is ErrnoException -> throw cause
            else -> throw ErrnoException("I/O", OsConstants.EIO, e)
        }
    }

    companion object {
        private const val TAG = "SambaProxyFileCallback"
    }
}