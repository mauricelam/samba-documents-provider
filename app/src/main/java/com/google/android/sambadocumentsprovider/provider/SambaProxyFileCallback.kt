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
import com.google.android.sambadocumentsprovider.nativefacade.inputStream
import com.google.android.sambadocumentsprovider.nativefacade.outputStream
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
        try {
            return file.inputStream(bufferPool).read(data, 0, size)
        } catch (e: IOException) {
            throwErrnoException(e)
        }
        return 0
    }

    @Throws(ErrnoException::class)
    override fun onWrite(offset: Long, size: Int, data: ByteArray): Int {
        try {
            file.outputStream(bufferPool).write(data, 0, size)
            return size
        } catch (e: IOException) {
            Log.e(TAG, "onWrite errno", e)
            throwErrnoException(e)
        } catch (e: Exception) {
            Log.e(TAG, "onWrite error", e)
        }
        return 0
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