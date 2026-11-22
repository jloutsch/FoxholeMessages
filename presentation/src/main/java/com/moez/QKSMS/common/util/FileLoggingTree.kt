/*
 * Copyright (C) 2017 Moez Bhatti <moez.bhatti@gmail.com>
 *
 * This file is part of QKSMS.
 *
 * QKSMS is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * QKSMS is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with QKSMS.  If not, see <http://www.gnu.org/licenses/>.
 */
package dev.octoshrimpy.quik.common.util

import dev.octoshrimpy.quik.util.tryOrNull
import java.io.File
import android.provider.MediaStore
import android.os.Environment
import android.os.Build
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.util.Log
import dev.octoshrimpy.quik.util.FileUtils
import dev.octoshrimpy.quik.util.Preferences
import timber.log.Timber
import java.io.FileNotFoundException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Based off Vipin Kumar's FileLoggingTree: https://medium.com/@vicky7230/file-logging-with-timber-4e63a1b86a66
 */
@Singleton
class FileLoggingTree @Inject constructor(
    private val prefs: Preferences,
    private val context: Context
) : Timber.DebugTree() {
    companion object {
        val TAG: String? = FileLoggingTree::class.simpleName
    }

    private var logFileUri: Uri? = null

    // One thread writes every line, in order. A task per line on Schedulers.io() started a new
    // thread whenever the previous lines were still waiting on the file, and a full sync logs one
    // line per message: thousands of threads, until Android refused more and the app crashed.
    private val writer = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "FileLoggingTree") }

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        if (!prefs.logging.get()) return

        // Built now, so the time is when the line was logged rather than when it was written
        val logItem =
                    "${    // date/time
                        SimpleDateFormat(
                            "yyyy-MM-dd HH:mm:ss:SSS",
                            Locale.getDefault()
                        ).format(System.currentTimeMillis())
                    } ${    // priority
                        when (priority) {
                            Log.VERBOSE -> "V"
                            Log.DEBUG -> "D"
                            Log.INFO -> "I"
                            Log.WARN -> "W"
                            Log.ERROR -> "E"
                            else -> "?"
                        }
                    }/${    // tag
                        tag
                    }: ${    // message
                        message
                    }${    // stack trace
                        Log.getStackTraceString(t)
                    }\n"

        writer.execute {
                // if uri of log file not yet determined, get one now
                if (logFileUri == null) {
                    val filename = "FoxholeMessages-log-${
                        SimpleDateFormat(
                            "yyyy-MM-dd",
                            Locale.getDefault()
                        ).format(System.currentTimeMillis())
                    }.log"

                    // Every app start used to create a new file of the same name; Downloads
                    // numbered the copies until it refused another ("Failed to build unique file")
                    // and logging stopped. Today's file is reused instead.
                    // Not logged on failure: that would log from inside the logger
                    logFileUri = tryOrNull(false) { existingLogFile(filename) }
                    if (logFileUri == null) {
                        val (uri, e) = FileUtils.create(
                            FileUtils.Location.Downloads,
                            context,
                            filename,
                            "text/plain"
                        )
                        if (e is Exception)
                            Log.e(TAG, "Error opening log file", e)
                        else
                            logFileUri = uri
                    }
                }

                logFileUri?.let {
                    val e = FileUtils.append(context, it, logItem.toByteArray())
                    if (e is FileNotFoundException)
                        Log.e(TAG, "Log file went away. Lost log file item: $logItem", e)
                    else if (e is Exception)
                        Log.e(TAG, "Error while logging into file", e)
                }
        }
    }

    /** This app's log file of that name in Downloads, if it already made one. */
    private fun existingLogFile(filename: String): Uri? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Without storage permissions an app only sees its own files here
            context.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
                arrayOf(filename),
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cursor.getLong(0))
                else null
            }
        } else {
            @Suppress("DEPRECATION") // Android 7-9 only; the same folder FileUtils writes to there
            File(Environment.getExternalStorageDirectory(), filename).takeIf { it.exists() }?.let(Uri::fromFile)
        }
}
