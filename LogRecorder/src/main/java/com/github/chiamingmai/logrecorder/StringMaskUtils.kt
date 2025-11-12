package com.github.chiamingmai.logrecorder

/**
 * Utility class for masking strings.
 *
 * Provides functions to mask portions of strings, including:
 * - Emails
 * - Phone numbers (mobile and landline)
 * - User identities
 * - Custom portions of any string
 */
object StringMaskUtils {
    /**
     * Masks a portion of the given string.
     *
     * @param input The original string to mask. Returns `""` if null or empty.
     * @param start Starting index to begin masking. Negative values treated as 0.
     * @param length Number of characters to mask. Negative values treated as 1.
     * @param symbol Character used for masking, default is `*`.
     */
    @JvmStatic
    @JvmOverloads
    fun mask(
        input: String,
        start: Int = 0,
        length: Int = Int.MAX_VALUE,
        symbol: Char = '*'
    ): String {
        if (input.isEmpty()) return ""
        val mStart = start.coerceAtLeast(0)
        val mLen = length.coerceAtLeast(1)
        val endIndex = (mStart + mLen).coerceAtMost(input.length)

        return input.mapIndexed { index, c -> if (index in mStart until endIndex) symbol else c }
            .joinToString("")
    }

    /**
     * Masks the middle portion of a string while keeping start and end characters.
     *
     * @param input The original string. Returns `""` if null or empty.
     * @param keepStart Number of characters to keep at the start.
     * @param keepEnd Number of characters to keep at the end.
     * @param symbol Character used for masking, default is `*`.
     */
    @JvmStatic
    @JvmOverloads
    fun maskMiddle(
        input: String,
        keepStart: Int = 2,
        keepEnd: Int = 2,
        symbol: Char = '*'
    ): String {
        if (input.isEmpty()) return ""

        if (input.length <= keepStart + keepEnd) return input
        return input.take(keepStart) +
                symbol.toString().repeat(input.length - keepStart - keepEnd) +
                input.takeLast(keepEnd)
    }

    /**
     * Masks an email address, keeping the first and last character of the username.
     *
     * Example: `test@example.com` -> `t**t@example.com`
     *
     * @param mailAddress The email address to mask. Returns `""` if null or empty.
     * @param symbol Character used for masking, default is `*`.
     */
    @JvmStatic
    @JvmOverloads
    fun maskEmail(mailAddress: String, symbol: Char = '*'): String {
        if (mailAddress.isEmpty()) return ""

        val parts = mailAddress.split('@')
        val name = parts.firstOrNull() ?: return mailAddress
        val domain = parts.getOrNull(1)?.let { "@$it" } ?: ""

        val maskedName = name.mapIndexed { i, c ->
            if (i == 0 || i == name.lastIndex) c else symbol
        }.joinToString("")

        return maskedName + domain
    }

    /**
     * Masks a user identity string.
     *
     * Example: `A123456789` -> `A12*****89`
     *
     * @param identity The identity string to mask.
     * @param symbol Character used for masking, default is `*`.
     */
    @JvmStatic
    @JvmOverloads
    fun maskUserIdentity(identity: String, symbol: Char = '*'): String =
        mask(identity, 3, 5, symbol = symbol)

    /**
     * Masks a landline number.
     *
     * Example:
     * - `07-5555555` -> `07-****555`
     * - `5555555` -> `****555`
     *
     * @param number The landline number to mask.
     * @param symbol Character used for masking, default is `*`.
     */
    @JvmStatic
    @JvmOverloads
    fun maskLandlineNumber(number: String, symbol: Char = '*'): String {
        if (number.isEmpty()) return ""

        val parts = number.split('-', limit = 2)
        return if (parts.size == 2) {
            // With area code
            val area = parts[0]
            val maskedNum = mask(parts[1], 0, 4, symbol = symbol)
            "$area-$maskedNum"
        } else {
            mask(number, 0, 4, symbol = symbol)
        }
    }

    /**
     * Masks a mobile phone number.
     *
     * Example: `0912345678` -> `091****678`
     *
     * @param inputText The mobile number to mask.
     * @param symbol Character used for masking, default is `*`.
     */
    @JvmStatic
    @JvmOverloads
    fun maskMobilePhoneNumber(inputText: String, symbol: Char = '*'): String =
        mask(inputText, 3, 4, symbol = symbol)
}
