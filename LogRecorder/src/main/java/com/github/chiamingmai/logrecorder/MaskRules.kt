package com.github.chiamingmai.logrecorder

/**
 * [MaskRules] provides a set of JSON masking rules for [LogRecorder],
 * allowing sensitive information such as phone numbers, emails, ID numbers, etc.,
 * to be automatically masked when writing logs.
 */
object MaskRules {
    private fun matchKey(keys: List<String>, currentKey: String, ignoreCase: Boolean): Boolean =
        keys.any { it.equals(currentKey, ignoreCase) }

    /**
     * Full masking rule.
     * Replaces the values of the specified keys with the given mask string.
     *
     * @param keys The JSON keys to mask.
     * @param ignoreCase `true` to ignore JSON key case when comparing, `false` by default.
     * @param symbol Character used for masking, default is `*`.
     */
    @JvmStatic
    @JvmOverloads
    fun full(
        keys: List<String>,
        ignoreCase: Boolean = false,
        symbol: Char = '*'
    ): JSONMaskRule =
        JSONMaskRule { key, value ->
            if (matchKey(keys, key, ignoreCase))
                symbol.toString().repeat(value.length) else null
        }

    /**
     * Partial masking rule.
     * Keeps the first [prefix] and last [suffix] characters, and masks the rest with given [symbol]".
     *
     * @param keys The JSON keys to mask.
     * @param ignoreCase `true` to ignore JSON key case when comparing, `false` by default.
     * @param prefix Number of characters to keep at the start.
     * @param suffix Number of characters to keep at the end.
     * @param symbol Character used for masking, default is `*`.
     */
    @JvmStatic
    @JvmOverloads
    fun partial(
        keys: List<String>,
        ignoreCase: Boolean = false,
        prefix: Int = 2,
        suffix: Int = 2,
        symbol: Char = '*'
    ): JSONMaskRule =
        JSONMaskRule { key, value ->
            if (matchKey(keys, key, ignoreCase)) {
                if (value.length > prefix + suffix) {
                    value.take(prefix) +
                            symbol.toString().repeat(value.length - prefix - suffix) +
                            value.takeLast(suffix)
                } else symbol.toString().repeat(value.length)
            } else null
        }

    /**
     * Email masking rule.
     * Keeps the first 2 characters of the account, masks the rest with "***",
     * and keeps the full domain intact.
     *
     * @param keys The JSON keys to mask.
     * @param ignoreCase `true` to ignore JSON key case when comparing, `false` by default.
     * @param symbol Character used for masking, default is `*`.
     */
    @JvmStatic
    @JvmOverloads
    fun email(keys: List<String>, ignoreCase: Boolean = false, symbol: Char = '*'): JSONMaskRule =
        JSONMaskRule { key, value ->
            if (matchKey(keys, key, ignoreCase))
                StringMaskUtils.maskEmail(value, symbol)
            else null
        }

    /**
     * User ID number masking rule.
     * Keeps the first 3 and last 2 characters, and masks all characters in between with "*".
     *
     * @param keys The JSON keys to mask.
     * @param ignoreCase `true` to ignore JSON key case when comparing, `false` by default.
     * @param symbol Character used for masking, default is `*`.
     */
    @JvmStatic
    @JvmOverloads
    fun userIDNumber(
        keys: List<String>,
        ignoreCase: Boolean = false,
        symbol: Char = '*'
    ): JSONMaskRule =
        JSONMaskRule { key, value ->
            if (matchKey(keys, key, ignoreCase)) {
                StringMaskUtils.maskUserIdentity(value, symbol)
            } else null
        }

    /**
     * Regex masking rule.
     * Masks keys that match the given [pattern] using repeated [symbol].
     *
     * @param pattern Regex pattern to match JSON keys.
     * @param symbol Character used for masking, `*` by default.
     * @param ignoreCase `true` to ignore key case, `false` by default.
     */
    @JvmStatic
    @JvmOverloads
    fun regex(
        pattern: Regex,
        symbol: Char = '*',
        ignoreCase: Boolean = false
    ): JSONMaskRule = JSONMaskRule { key, value ->
        val patternToUse =
            if (ignoreCase) Regex(pattern.pattern, RegexOption.IGNORE_CASE) else pattern
        if (patternToUse.containsMatchIn(key))
            symbol.toString().repeat(value.length)
        else null
    }
}
