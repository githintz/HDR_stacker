package com.hdrstacker

import android.app.Application
import android.content.Context
import android.system.Os

/**
 * Sets ASAN_OPTIONS before any native library can load.
 *
 * The asan build's libhdrstacker.so links the AddressSanitizer runtime as a
 * shared-library dependency, so ASan initialises lazily the first time the
 * decoder is loaded — no wrap.sh process wrapper (which hangs app startup on
 * many devices) is involved. These options tell the runtime that late loading
 * is intentional (verify_asan_link_order=0) and where to write crash reports
 * so the app can surface them on the next launch.
 *
 * Harmless in normal builds: no ASan runtime is present to read the variable.
 */
class PhotoStudioApp : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        runCatching {
            // abort_on_error=1 makes ASan die via abort() so the system also
            // captures a tombstone — which the app can read back through
            // ApplicationExitInfo even if the report file was never written.
            // handle_sigill & co. make ASan write its own report file (with
            // the faulting thread's stack) for hard faults it didn't detect
            // itself, instead of dying silently.
            Os.setenv(
                "ASAN_OPTIONS",
                "verify_asan_link_order=0,abort_on_error=1,log_to_syslog=true," +
                    "handle_sigill=1,handle_abort=1,handle_sigbus=1,handle_sigfpe=1," +
                    "malloc_context_size=30," +
                    "log_path=${base.filesDir.absolutePath}/asan_report",
                true,
            )
        }
    }
}
