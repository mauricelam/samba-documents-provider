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
package com.google.android.sambadocumentsprovider.nativefacade

import android.system.StructStat
import com.google.android.sambadocumentsprovider.provider.ByteBufferPool
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import kotlin.math.min

interface SmbFile : Closeable {
    @Throws(IOException::class)
    fun read(buffer: ByteBuffer, len: Int = buffer.remaining()): Int

    @Throws(IOException::class)
    fun write(buffer: ByteBuffer, len: Int = buffer.remaining()): Int

    @Throws(IOException::class)
    fun seek(offset: Long): Long

    @Throws(IOException::class)
    fun fstat(): StructStat
}

fun SmbFile.outputStream(byteBufferPool: ByteBufferPool): OutputStream {
    val smbFile = this
    return object : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()))
        override fun write(b: ByteArray) = write(b, 0, b.size)
        override fun write(b: ByteArray, off: Int, len: Int) {
            byteBufferPool.useBuffer { buffer ->
                for (range in ranges(off, upBy = len, step = buffer.remaining())) {
                    buffer.put(b, range.start, range.span)
                    buffer.flip()
                    smbFile.write(buffer)
                    buffer.clear()
                }
            }
        }
    }
}

fun SmbFile.inputStream(byteBufferPool: ByteBufferPool): InputStream {
    val smbFile = this
    return object : InputStream() {
        override fun read(): Int = byteArrayOf(1).apply { read(this) }[0].toInt()
        override fun read(b: ByteArray): Int = read(b, 0, b.size)
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            var bytesRead = 0
            byteBufferPool.useBuffer { buffer ->
                for (range in ranges(off, upBy = len, step = buffer.remaining())) {
                    bytesRead += smbFile.read(buffer, range.span)
                    buffer.flip()
                    buffer.get(b, range.start, buffer.limit())
                    buffer.clear()
                }
            }
            return bytesRead
        }
    }
}

fun ranges(start: Int, upBy: Int, step: Int): Iterable<IntRange> =
    start.until(start + upBy).stepRange(step)

infix fun IntRange.stepRange(stepSize: Int): Iterable<IntRange> =
    step(stepSize).map { it..min(it + stepSize - 1, endInclusive) }

val IntRange.span
    get(): Int = endInclusive - start + 1
