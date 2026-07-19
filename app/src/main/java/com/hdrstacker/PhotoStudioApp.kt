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
            Os.setenv(
                "ASAN_OPTIONS",
                "verify_asan_link_order=0,log_to_syslog=true,malloc_context_size=30," +
                    "log_path=${base.filesDir.absolutePath}/asan_report",
                true,
            )
        }
    }
}
