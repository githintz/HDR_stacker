package com.hdrstacker

import android.app.Application

/**
 * Process-level init hook. The debugging ("asan") variant previously exported
 * ASAN_OPTIONS from here; the sanitizer approach was replaced by a guard-page
 * allocator linked directly into the native decoder (cpp/guarded-alloc.cpp),
 * which needs no process configuration. Kept as the anchor for future
 * startup work.
 */
class PhotoStudioApp : Application()
