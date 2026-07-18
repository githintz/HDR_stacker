#!/system/bin/sh
# Preloads the AddressSanitizer runtime into the app process (asan build only).
# Reports are written both to logcat and to the app's files dir, where the app
# surfaces them in a dialog on next launch (no adb needed).
HERE="$(cd "$(dirname "$0")" && pwd)"
export ASAN_OPTIONS=log_to_syslog=true,allow_user_segv_handler=1,malloc_context_size=30,log_path=/data/data/com.hdrstacker.asan/files/asan_report
ASAN_LIB=$(ls "$HERE"/libclang_rt.asan-*-android.so)
if [ -f "$HERE/libc++_shared.so" ]; then
    # Workaround for https://github.com/android-ndk/ndk/issues/988
    export LD_PRELOAD="$ASAN_LIB $HERE/libc++_shared.so"
else
    export LD_PRELOAD="$ASAN_LIB"
fi
"$@"
