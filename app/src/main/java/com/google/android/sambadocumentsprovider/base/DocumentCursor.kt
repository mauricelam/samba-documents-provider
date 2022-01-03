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
package com.google.android.sambadocumentsprovider.base

import android.database.MatrixCursor
import android.os.Bundle
import android.util.Log
import com.google.android.sambadocumentsprovider.BuildConfig
import kotlinx.coroutines.Job

/**
 * Use this class to avoid using [Cursor.setExtras] on API level < 23.
 */
class DocumentCursor(projection: Array<String>) : MatrixCursor(projection) {
    private var extras: Bundle? = null
    var loadingJob: Job? = null

    override fun setExtras(extras: Bundle?) {
        this.extras = extras
    }

    override fun getExtras(): Bundle? {
        return extras
    }

    override fun close() {
        super.close()
        loadingJob?.let { job ->
            if (!job.isCompleted) {
                if (BuildConfig.DEBUG) Log.d(TAG, "Cursor is closed. Cancel loading job $job")
                // Interrupting the job is not a good choice as it's waiting for the Samba client
                // thread returning the result. Interrupting the job only frees the job from waiting
                // for the result, rather than freeing the Samba client thread doing the hard work.
                job.cancel()
            }
        }
    }

    companion object {
        private const val TAG = "DocumentCursor"
    }
}