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

import android.annotation.TargetApi
import android.os.Looper
import android.os.Message
import android.os.ParcelFileDescriptor
import android.os.storage.StorageManager
import android.system.StructStat
import com.google.android.sambadocumentsprovider.provider.ByteBufferPool
import com.google.android.sambadocumentsprovider.provider.SambaProxyFileCallback
import kotlinx.coroutines.Deferred
import java.io.IOException

internal class SambaFacadeClient(looper: Looper, clientImpl: SmbClient) : BaseClient(), SmbFacade {

    private fun obtainMessage(what: Int, messageValues: MessageValues<*>, uri: String?): Message {
        val msg = mHandler.obtainMessage(what, messageValues)
        val args = msg.data
        args.putString(URI, uri)
        return msg
    }

    override fun reset() {
        MessageValues.obtain<Void>().use { messageValues ->
            val msg = obtainMessage(RESET, messageValues, null)
            enqueue(msg)
        }
    }

    @Throws(IOException::class)
    override fun openDir(uri: String): SmbDir {
        MessageValues.obtain<SmbDir>().use { messageValues ->
            val msg = obtainMessage(READ_DIR, messageValues, uri)
            enqueue(msg)
            return SambaDirClient(mHandler.looper, messageValues.obj)
        }
    }

    @Throws(IOException::class)
    override fun stat(uri: String): StructStat {
        MessageValues.obtain<StructStat>().use { messageValues ->
            val msg = obtainMessage(STAT, messageValues, uri)
            enqueue(msg)
            return messageValues.obj
        }
    }

    @Throws(IOException::class)
    override fun createFile(uri: String) {
        MessageValues.obtain<Any>().use { messageValues ->
            val msg = obtainMessage(CREATE_FILE, messageValues, uri)
            enqueue(msg)
            messageValues.checkException()
        }
    }

    @Throws(IOException::class)
    override fun mkdir(uri: String) {
        MessageValues.obtain<Any>().use { messageValues ->
            val msg = obtainMessage(MKDIR, messageValues, uri)
            enqueue(msg)
            messageValues.checkException()
        }
    }

    @Throws(IOException::class)
    override fun rename(uri: String, newUri: String) {
        MessageValues.obtain<Any>().use { messageValues ->
            val msg = obtainMessage(RENAME, messageValues, uri)
            msg.peekData().putString(NEW_URI, newUri)
            enqueue(msg)
            messageValues.checkException()
        }
    }

    @Throws(IOException::class)
    override fun unlink(uri: String) {
        MessageValues.obtain<Any>().use { messageValues ->
            val msg = obtainMessage(UNLINK, messageValues, uri)
            enqueue(msg)
            messageValues.checkException()
        }
    }

    @Throws(IOException::class)
    override fun rmdir(uri: String) {
        MessageValues.obtain<Any>().use { messageValues ->
            val msg = obtainMessage(RMDIR, messageValues, uri)
            enqueue(msg)
            messageValues.checkException()
        }
    }

    @Throws(IOException::class)
    override fun openFile(uri: String, mode: String): SmbFile {
        return SambaFileClient(mHandler.looper, openFileRaw(uri, mode))
    }

    @TargetApi(26)
    @Throws(IOException::class)
    override fun openProxyFile(
        uri: String,
        mode: String,
        storageManager: StorageManager,
        bufferPool: ByteBufferPool
    ): Pair<ParcelFileDescriptor, Deferred<Unit>> {
        val file = openFileRaw(uri, mode)
        val sambaProxyFileCallback = SambaProxyFileCallback(uri, file, bufferPool)
        return storageManager.openProxyFileDescriptor(
            ParcelFileDescriptor.parseMode(mode),
            sambaProxyFileCallback,
            mHandler
        ) to sambaProxyFileCallback.deferred
    }

    @Throws(IOException::class)
    private fun openFileRaw(uri: String, mode: String): SambaFile {
        MessageValues.obtain<SambaFile>().use { messageValues ->
            enqueue(obtainMessageForOpenFile(uri, mode, messageValues))
            return messageValues.obj
        }
    }

    private fun obtainMessageForOpenFile(
        uri: String, mode: String, messageValues: MessageValues<SambaFile>
    ): Message {
        val msg = obtainMessage(OPEN_FILE, messageValues, uri)
        msg.peekData().putString(MODE, mode)
        return msg
    }

    private class SambaServiceHandler constructor(
        looper: Looper,
        private val mClientImpl: SmbClient
    ) : BaseHandler(looper) {
        public override fun processMessage(msg: Message) {
            val args = msg.peekData()
            val uri = args.getString(URI)
            val messageValues = msg.obj as MessageValues<Any>
            try {
                when (msg.what) {
                    RESET -> mClientImpl.reset()
                    READ_DIR -> messageValues.setObj(mClientImpl.openDir(uri))
                    STAT -> messageValues.setObj(mClientImpl.stat(uri))
                    CREATE_FILE -> mClientImpl.createFile(uri)
                    MKDIR -> mClientImpl.mkdir(uri)
                    RENAME -> {
                        val newUri = args.getString(NEW_URI)
                        mClientImpl.rename(uri, newUri)
                    }
                    UNLINK -> mClientImpl.unlink(uri)
                    RMDIR -> mClientImpl.rmdir(uri)
                    OPEN_FILE -> {
                        val mode = args.getString(MODE)
                        messageValues.setObj(mClientImpl.openFile(uri, mode))
                    }
                    else -> throw UnsupportedOperationException("Unknown operation " + msg.what)
                }
            } catch (e: IOException) {
                messageValues.setException(e)
            } catch (e: RuntimeException) {
                messageValues.setRuntimeException(e)
            }
        }
    }

    companion object {
        const val RESET = 1
        const val READ_DIR = RESET + 1
        const val STAT = READ_DIR + 1
        const val CREATE_FILE = STAT + 1
        const val MKDIR = CREATE_FILE + 1
        const val RENAME = MKDIR + 1
        const val UNLINK = RENAME + 1
        const val RMDIR = UNLINK + 1
        const val OPEN_FILE = RMDIR + 1
        private const val URI = "URI"
        private const val NEW_URI = "NEW_URI"
        private const val MODE = "MODE"
    }

    init {
        mHandler = SambaServiceHandler(looper, clientImpl)
    }
}