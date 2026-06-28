package com.phantom.scroll.util

import android.util.Log
import com.phantom.scroll.BuildConfig

/**
 * Gated logging utility for PhantomScroll.
 * Suppresses debug and warning logs in release builds (when [BuildConfig.DEBUG] is false).
 * Error level logs are always printed to ensure critical failures can be diagnosed.
 * Automatically falls back to println in JVM unit tests where android.util.Log is not mocked.
 */
object PhantomLog {
    private val isUnitTest = runCatching { Log.isLoggable("test", Log.DEBUG); false }.getOrElse { true }

    fun d(tag: String, message: String) {
        if (BuildConfig.DEBUG) {
            if (isUnitTest) {
                println("[$tag] D: $message")
            } else {
                Log.d(tag, message)
            }
        }
    }

    fun d(tag: String, message: String, throwable: Throwable) {
        if (BuildConfig.DEBUG) {
            if (isUnitTest) {
                println("[$tag] D: $message")
                throwable.printStackTrace()
            } else {
                Log.d(tag, message, throwable)
            }
        }
    }

    fun i(tag: String, message: String) {
        if (BuildConfig.DEBUG) {
            if (isUnitTest) {
                println("[$tag] I: $message")
            } else {
                Log.i(tag, message)
            }
        }
    }

    fun w(tag: String, message: String) {
        if (BuildConfig.DEBUG) {
            if (isUnitTest) {
                println("[$tag] W: $message")
            } else {
                Log.w(tag, message)
            }
        }
    }

    fun w(tag: String, message: String, throwable: Throwable) {
        if (BuildConfig.DEBUG) {
            if (isUnitTest) {
                println("[$tag] W: $message")
                throwable.printStackTrace()
            } else {
                Log.w(tag, message, throwable)
            }
        }
    }

    fun e(tag: String, message: String) {
        if (isUnitTest) {
            println("[$tag] E: $message")
        } else {
            Log.e(tag, message)
        }
    }

    fun e(tag: String, message: String, throwable: Throwable) {
        if (isUnitTest) {
            println("[$tag] E: $message")
            throwable.printStackTrace()
        } else {
            Log.e(tag, message, throwable)
        }
    }
}
