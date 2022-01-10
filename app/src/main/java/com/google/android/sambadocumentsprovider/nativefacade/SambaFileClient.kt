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

import android.os.Looper
import android.os.Message
import android.system.StructStat
import java.io.IOException
import java.nio.ByteBuffer
internal class SambaFileClient(looper: Looper, smbFileImpl: SmbFile) : BaseClient(), SmbFile {

    override val handler: BaseHandler = SambaFileHandler(looper, smbFileImpl)

    @Throws(IOException::class)
    override fun read(buffer: ByteBuffer, len: Int): Int {
        MessageValues.obtain<ByteBuffer>().use { messageValues ->
            messageValues.obj = buffer
            messageValues.int = len
            val msg = handler.obtainMessage(READ, messageValues)
            enqueue(msg)
            return messageValues.int
        }
    }

    @Throws(IOException::class)
    override fun write(buffer: ByteBuffer, len: Int): Int {
        MessageValues.obtain<ByteBuffer>().use { messageValues ->
            messageValues.obj = buffer
            messageValues.int = len
            enqueue(handler.obtainMessage(WRITE, messageValues))
            return messageValues.int
        }
    }

    @Throws(IOException::class)
    override fun seek(offset: Long): Long {
        MessageValues.obtain<Any>().use { messageValues ->
            val msg = handler.obtainMessage(SEEK, messageValues)
            messageValues.long = offset
            enqueue(msg)
            return messageValues.long
        }
    }

    @Throws(IOException::class)
    override fun fstat(): StructStat {
        MessageValues.obtain<StructStat>().use { messageValues ->
            val msg = handler.obtainMessage(FSTAT, messageValues)
            enqueue(msg)
            return messageValues.obj
        }
    }

    @Throws(IOException::class)
    override fun close() {
        MessageValues.obtain<Any>().use { messageValues ->
            val msg = handler.obtainMessage(CLOSE, messageValues)
            enqueue(msg)
            messageValues.checkException()
        }
    }

    private class SambaFileHandler(
        looper: Looper,
        private val smbFileImpl: SmbFile
    ) : BaseHandler(looper) {
        override fun processMessage(msg: Message) {
            val messageValues = msg.obj as MessageValues<*>
            try {
                when (msg.what) {
                    READ -> {
                        val readBuffer = messageValues.obj as ByteBuffer
                        val len = messageValues.int
                        messageValues.setInt(smbFileImpl.read(readBuffer, len))
                    }
                    WRITE -> {
                        val writeBuffer = messageValues.obj as ByteBuffer
                        val len = messageValues.int
                        messageValues.setInt(smbFileImpl.write(writeBuffer, len))
                    }
                    CLOSE -> smbFileImpl.close()
                    SEEK -> {
                        val offset = smbFileImpl.seek(messageValues.long)
                        messageValues.setLong(offset)
                    }
                    FSTAT -> messageValues.obj = smbFileImpl.fstat()
                    else -> throw UnsupportedOperationException("Unknown operation ${msg.what}")
                }
            } catch (e: IOException) {
                messageValues.setException(e)
            } catch (e: RuntimeException) {
                messageValues.setRuntimeException(e)
            }
        }
    }

    companion object {
        private const val READ = 1
        private const val WRITE = 2
        private const val CLOSE = 3
        private const val SEEK = 4
        private const val FSTAT = 5
    }
}

