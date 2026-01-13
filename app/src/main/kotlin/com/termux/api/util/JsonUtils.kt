package com.termux.api.util

import android.util.JsonWriter
import com.termux.shared.logger.Logger
import java.io.IOException

object JsonUtils {

    private const val LOG_TAG = "JsonUtils"

    @JvmStatic
    fun putBooleanValueIfSet(out: JsonWriter?, key: String?, value: Boolean?) {
        if (out == null || key.isNullOrEmpty() || value == null) return

        try {
            out.name(key).value(value)
        } catch (e: IOException) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to put \"$key\" with boolean value \"$value\"", e)
        }
    }

    @JvmStatic
    fun putIntegerIfSet(out: JsonWriter?, key: String?, value: Int?) {
        if (out == null || key.isNullOrEmpty() || value == null) return

        try {
            out.name(key).value(value.toLong())
        } catch (e: IOException) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to put \"$key\" with integer value \"$value\"", e)
        }
    }

    @JvmStatic
    fun putLongIfSet(out: JsonWriter?, key: String?, value: Long?) {
        if (out == null || key.isNullOrEmpty() || value == null) return

        try {
            out.name(key).value(value)
        } catch (e: IOException) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to put \"$key\" with long value \"$value\"", e)
        }
    }

    @JvmStatic
    fun putDoubleIfSet(out: JsonWriter?, key: String?, value: Double?) {
        if (out == null || key.isNullOrEmpty() || value == null) return

        try {
            out.name(key).value(value)
        } catch (e: IOException) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to put \"$key\" with double value \"$value\"", e)
        }
    }

    @JvmStatic
    fun putStringIfSet(out: JsonWriter?, key: String?, value: String?) {
        if (out == null || key.isNullOrEmpty() || value == null) return

        try {
            out.name(key).value(value)
        } catch (e: IOException) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to put \"$key\" with string value \"$value\"", e)
        }
    }
}
