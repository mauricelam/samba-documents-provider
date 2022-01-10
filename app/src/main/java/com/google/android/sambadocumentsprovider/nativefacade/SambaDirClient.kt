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
import com.google.android.sambadocumentsprovider.base.DirectoryEntry
import java.io.IOException

internal class SambaDirClient(looper: Looper, smbDirImpl: SmbDir) : BaseClient(), SmbDir {

    override val handler: BaseHandler = SambaDirHandler(looper, smbDirImpl)

    @Throws(IOException::class)
    override fun readDir(): DirectoryEntry? {
        MessageValues.obtain<DirectoryEntry>().use { messageValues ->
            val msg = handler.obtainMessage(READ_DIR, messageValues)
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

    private class SambaDirHandler(
        looper: Looper,
        private val mSmbDirImpl: SmbDir
    ) : BaseHandler(looper) {
        override fun processMessage(msg: Message) {
            val messageValues = msg.obj as MessageValues<DirectoryEntry?>
            try {
                when (msg.what) {
                    READ_DIR -> messageValues.setObj(mSmbDirImpl.readDir())
                    CLOSE -> mSmbDirImpl.close()
                    else -> throw UnsupportedOperationException("Unknown operation ${msg.what}")
                }
            } catch (e: RuntimeException) {
                messageValues.setRuntimeException(e)
            } catch (e: IOException) {
                messageValues.setException(e)
            }
        }
    }

    companion object {
        private const val READ_DIR = 0
        private const val CLOSE = READ_DIR + 1
    }
}