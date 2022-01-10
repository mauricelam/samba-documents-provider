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

import android.os.Handler
import android.os.Looper
import android.os.Message

internal abstract class BaseClient {
    abstract val handler: BaseHandler
    fun enqueue(msg: Message) {
        try {
            synchronized(msg.obj) {
                handler.sendMessage(msg)
                (msg.obj as java.lang.Object).wait()
            }
        } catch (e: InterruptedException) {
            // It should never happen.
            throw RuntimeException("Unexpected interruption.", e)
        }
    }

    internal abstract class BaseHandler(looper: Looper?) : Handler(looper!!) {
        abstract fun processMessage(msg: Message)
        override fun handleMessage(msg: Message) {
            synchronized(msg.obj) {
                processMessage(msg)
                (msg.obj as java.lang.Object).notify()
            }
        }
    }
}