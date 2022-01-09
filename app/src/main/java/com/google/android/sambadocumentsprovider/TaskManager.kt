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

import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.ConcurrentHashMap

class TaskManager {
    private val tasks = ConcurrentHashMap<Uri, Deferred<Unit>>()

    suspend fun runTask(uri: Uri, task: suspend () -> Unit) {
        coroutineScope {
            if (tasks[uri]?.isActive != true) {
                tasks[uri] = async { task() }
            } else {
                Log.i(TAG, "Ignore task for $uri to avoid multiple concurrent updates.")
            }
        }
    }

    companion object {
        private const val TAG = "TaskManager"
    }
}