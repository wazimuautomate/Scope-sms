package com.tricreta.scopesms.diagnostics

import android.content.Context
import android.os.Build
import com.tricreta.scopesms.BuildConfig
import java.io.File

/**
 * Last-resort crash capture.
 *
 * Installs a default uncaught-exception handler that writes the fatal stack trace
 * to a file **before** the process dies, then chains to the platform handler so
 * Android still shows its "app has stopped" dialog. Settings surfaces the saved
 * report with a Share button.
 *
 * Why it exists: the Messages-tab force-close could not be reproduced off-device
 * (a Robolectric measure/layout pass over the same screen passes), so the only way
 * to see the real exception is from the agent's own handset. One crash now leaves a
 * shareable report instead of another guessing round.
 *
 * Deliberately tiny and defensive — it runs while the process is already crashing,
 * so it must never throw and must not do heavy work. It only *installs* a handler
 * at startup (cheap) and only writes on an actual fatal crash.
 */
object CrashReporter {

    private const val FILE = "last_crash.txt"

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val header = buildString {
                    append("Scope SMS ").append(BuildConfig.VERSION_NAME)
                    append(" (").append(BuildConfig.VERSION_CODE).append(")\n")
                    append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                    append(", Android ").append(Build.VERSION.RELEASE).append('\n')
                    append("thread=").append(thread.name).append("\n\n")
                }
                File(appContext.filesDir, FILE).writeText(header + throwable.stackTraceToString())
            }
            // Always let the platform (or a prior handler) finish the crash so the
            // OS still reports it — we only piggy-backed to save the trace.
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** The last captured crash report, or null if none was saved. */
    fun lastReport(context: Context): String? {
        val file = File(context.applicationContext.filesDir, FILE)
        return runCatching { if (file.exists()) file.readText() else null }.getOrNull()
    }

    /** Clears a saved report once the agent has shared/dismissed it. */
    fun clear(context: Context) {
        runCatching { File(context.applicationContext.filesDir, FILE).delete() }
    }
}
