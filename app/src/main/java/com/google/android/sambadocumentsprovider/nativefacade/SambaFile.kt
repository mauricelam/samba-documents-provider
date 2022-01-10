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

import android.system.ErrnoException
import android.system.StructStat
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.math.min

internal class SambaFile(private val nativeHandler: Long, private var nativeFd: Int) : SmbFile {
    private var offset: Long = 0
    @Throws(IOException::class)
    override fun read(buffer: ByteBuffer, len: Int): Int {
        check(buffer.isDirect) { "ByteBuffer passed to native layer must be direct" }
        return try {
            val bytesRead =
                read(nativeHandler, nativeFd, buffer, min(buffer.remaining(), len))
            buffer.position(buffer.position() + bytesRead)
            offset += bytesRead.toLong()
            bytesRead
        } catch (e: ErrnoException) {
            throw IOException("Failed to read file. Fd: $nativeFd", e)
        }
    }

    @Throws(IOException::class)
    override fun write(buffer: ByteBuffer, len: Int): Int {
        check(buffer.isDirect) { "ByteBuffer passed to native layer must be direct" }
        return try {
            val bytesWritten = write(nativeHandler, nativeFd, buffer, min(buffer.remaining(), len))
            buffer.position(buffer.position() + bytesWritten)
            offset += bytesWritten.toLong()
            bytesWritten
        } catch (e: ErrnoException) {
            throw IOException("Failed to write file. Fd: $nativeFd", e)
        }
    }

    @Throws(IOException::class)
    override fun seek(offset: Long): Long {
        return if (this.offset == offset) {
            this.offset
        } else try {
            this.offset = seek(nativeHandler, nativeFd, offset, 0)
            this.offset
        } catch (e: ErrnoException) {
            throw IOException("Failed to move to offset in file. Fd: $nativeFd", e)
        }
    }

    @Throws(IOException::class)
    override fun fstat(): StructStat {
        return try {
            fstat(nativeHandler, nativeFd)
        } catch (e: ErrnoException) {
            throw IOException("Failed to get stat of $nativeFd", e)
        }
    }

    @Throws(IOException::class)
    override fun close() {
        try {
            val fd = nativeFd
            nativeFd = -1
            close(nativeHandler, fd)
        } catch (e: ErrnoException) {
            throw IOException("Failed to close file. Fd: $nativeFd", e)
        }
    }

    @Throws(ErrnoException::class)
    private external fun read(handler: Long, fd: Int, buffer: ByteBuffer, capacity: Int): Int
    @Throws(ErrnoException::class)
    private external fun write(handler: Long, fd: Int, buffer: ByteBuffer, length: Int): Int
    @Throws(ErrnoException::class)
    private external fun seek(handler: Long, fd: Int, offset: Long, whence: Int): Long
    @Throws(ErrnoException::class)
    private external fun fstat(handler: Long, fd: Int): StructStat
    @Throws(ErrnoException::class)
    private external fun close(handler: Long, fd: Int)
}