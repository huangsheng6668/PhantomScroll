package com.phantom.scroll.util

import android.util.Log
import com.phantom.scroll.BuildConfig

/**
 * Gated logging utility for PhantomScroll.
 * Suppresses debug and warning logs in release builds (when [BuildConfig.DEBUG] is false).
 * Error level logs are always printed to ensure critical failures can be diagnosed.
 */
object PhantomLog {
    fun d(tag: String, message: String) {
        if (BuildConfig.DEBUG) {
            Log.d(tag, message)
        }
    }

    fun d(tag: String, message: String, throwable: Throwable) {
        if (BuildConfig.DEBUG) {
            Log.d(tag, message, throwable)
        }
    }

    fun i(tag: String, message: String) {
        if (BuildConfig.DEBUG) {
            Log.i(tag, message)
        }
    }

    fun w(tag: String, message: String) {
        if (BuildConfig.DEBUG) {
            Log.w(tag, message)
        }
    }

    fun w(tag: String, message: String, throwable: Throwable) {
        if (BuildConfig.DEBUG) {
            Log.w(tag, message, throwable)
        }
    }

    fun e(tag: String, message: String) {
        Log.e(tag, message)
    }

    fun e(tag: String, message: String, throwable: Throwable) {
        Log.e(tag, message, throwable)
    }
}
