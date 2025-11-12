package com.github.chiamingmai.logrecorder

/** JSON mask rule for [LogRecorder]. */
fun interface JSONMaskRule {
    /** Mask strategy.
     *
     * @param key JSON key of the value.
     * @param value JSON value of the key.
     * @return a non null-string to mask this value or null to use original value.
     */
    fun mask(key: String, value: String): String?
}