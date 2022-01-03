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

import android.os.ParcelFileDescriptor
import android.util.Log
import com.google.android.sambadocumentsprovider.nativefacade.SmbClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

suspend fun writeFile(
    uri: String,
    client: SmbClient,
    pfd: ParcelFileDescriptor,
    bufferPool: ByteBufferPool
) {
    val buffer = bufferPool.obtainBuffer()
    withContext(Dispatchers.IO) {
        try {
            ParcelFileDescriptor.AutoCloseInputStream(pfd).use { inputStream ->
                client.openFile(uri, "w").use { file ->
                    var size: Int
                    val buf = ByteArray(buffer.capacity())
                    while (inputStream.read(buf).also { size = it } > 0) {
                        buffer.put(buf, 0, size)
                        file.write(buffer, size)
                        buffer.clear()
                    }
                }
            }
        } catch (e: IOException) {
            Log.e("WriteFileTask", "Failed to write file.", e)
            try {
                pfd.closeWithError(e.message)
            } catch (exc: IOException) {
                Log.e("WriteFileTask", "Can't even close PFD with error.", exc)
            }
            throw e
        }
    }
    bufferPool.recycleBuffer(buffer)
}