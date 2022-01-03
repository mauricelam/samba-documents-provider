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
package com.google.android.sambadocumentsprovider

import kotlin.jvm.Synchronized
import kotlin.Throws
import android.system.ErrnoException
import android.util.Log
import com.google.android.sambadocumentsprovider.base.BiResultTask
import java.io.*
import java.util.HashMap

internal class SambaConfiguration(private val mHomeFolder: File, shareFolder: File?) :
    Iterable<Map.Entry<String?, String?>?> {
    companion object {
        private const val TAG = "SambaConfiguration"
        private const val HOME_VAR = "HOME"
        private const val SMB_FOLDER_NAME = ".smb"
        private const val SMB_CONF_FILE = "smb.conf"
        private const val CONF_KEY_VALUE_SEPARATOR = " = "
        private fun getSmbFile(homeFolder: File): File {
            val smbFolder = File(homeFolder, SMB_FOLDER_NAME)
            if (!smbFolder.isDirectory && !smbFolder.mkdir()) {
                Log.e(TAG, "Failed to obtain .smb folder.")
            }
            return File(smbFolder, SMB_CONF_FILE)
        }

        private fun getExtSmbFile(shareFolder: File?): File {
            return File(shareFolder, SMB_CONF_FILE)
        }

        init {
            System.loadLibrary("samba_client")
        }
    }

    private var mShareFolder: File? = null
    private val mConfigurations: MutableMap<String, String> = HashMap()
    fun flushDefault(listener: () -> Unit) {
        // lmhosts are not used in SambaDocumentsProvider and prioritize bcast because sometimes in home
        // settings DNS will resolve unknown domain name to a specific IP for advertisement.
        //
        // lmhosts -- lmhosts file if existed side by side to smb.conf
        // wins -- Windows Internet Name Service
        // hosts -- hosts file and DNS resolution
        // bcast -- NetBIOS broadcast
        addConfiguration("name resolve order", "wins bcast hosts")

        // Urge from users to disable SMB1 by default.
        addConfiguration("client min protocol", "SMB2")
        addConfiguration("client max protocol", "SMB3")
        val smbFile = getSmbFile(mHomeFolder)
        if (!smbFile.exists()) {
            flush(listener)
        }
    }

    @Synchronized
    fun addConfiguration(key: String, value: String): SambaConfiguration {
        mConfigurations[key] = value
        return this
    }

    @Synchronized
    fun removeConfiguration(key: String): SambaConfiguration {
        mConfigurations.remove(key)
        return this
    }

    fun syncFromExternal(listener: () -> Unit): Boolean {
        val smbFile = getSmbFile(mHomeFolder)
        val extSmbFile = getExtSmbFile(mShareFolder)
        if (extSmbFile.isFile && extSmbFile.lastModified() > smbFile.lastModified()) {
            if (BuildConfig.DEBUG) Log.d(
                TAG, "Syncing " + SMB_CONF_FILE +
                        " from external source to internal source."
            )
            SyncTask(listener).execute(extSmbFile)
            return true
        }
        return false
    }

    private fun flush(listener: () -> Unit) {
        FlushTask(listener).execute()
    }

    @Synchronized
    @Throws(IOException::class)
    private fun read(smbFile: File) {
        mConfigurations.clear()
        BufferedReader(FileReader(smbFile)).use { reader ->
            var line: String
            while (reader.readLine().also { line = it } != null) {
                val conf = line.split(CONF_KEY_VALUE_SEPARATOR).toTypedArray()
                if (conf.size == 2) {
                    mConfigurations[conf[0]] = conf[1]
                }
            }
        }
    }

    @Synchronized
    @Throws(IOException::class)
    private fun write() {
        PrintStream(getSmbFile(mHomeFolder)).use { fs ->
            for ((key, value) in mConfigurations) {
                fs.print(key)
                fs.print(CONF_KEY_VALUE_SEPARATOR)
                fs.print(value)
                fs.println()
            }
            fs.flush()
        }
    }

    private fun setHomeEnv(absoluteFolder: String) {
        try {
            setEnv(HOME_VAR, absoluteFolder)
        } catch (e: ErrnoException) {
            Log.e(TAG, "Failed to set HOME environment variable.", e)
        }
    }

    @Throws(ErrnoException::class)
    private external fun setEnv(`var`: String, value: String)
    @Synchronized
    override fun iterator(): MutableIterator<Map.Entry<String, String>> {
        return mConfigurations.entries.iterator()
    }

    private inner class SyncTask(private val mListener: () -> Unit) :
        BiResultTask<File?, Void?, Void?>() {
        @Throws(IOException::class)
        override fun run(vararg params: File?): Void? {
            read(params[0]!!)
            write()
            return null
        }

        override fun onSucceeded(result: Void?) {
            mListener()
        }
    }

    private inner class FlushTask(private val mListener: () -> Unit) :
        BiResultTask<Void?, Void?, Void?>() {
        @Throws(IOException::class)
        override fun run(vararg params: Void?): Void? {
            write()
            return null
        }

        override fun onSucceeded(result: Void?) {
            mListener()
        }
    }

    init {
        mShareFolder =
            if (shareFolder != null && (shareFolder.isDirectory || shareFolder.mkdirs())) {
                shareFolder
            } else {
                Log.w(
                    TAG,
                    "Failed to create share folder. Only default value is supported."
                )

                // Use home folder as the share folder to avoid null checks everywhere.
                mHomeFolder
            }
        setHomeEnv(mHomeFolder.absolutePath)
    }
}