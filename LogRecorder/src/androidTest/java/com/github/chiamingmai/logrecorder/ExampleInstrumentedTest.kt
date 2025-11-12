package com.github.chiamingmai.logrecorder

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented test, which will execute on an Android device.
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {
    @Test
    fun useAppContext() {
        // Context of the app under test.
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.github.chiamingmai.logrecorder.test", appContext.packageName)
    }

    @Test
    fun jsonMaskPartialTest() {
        val rule = MaskRules.partial(listOf("NAME", "phone", "email"))
        val rule1 = MaskRules.partial(listOf("NAME", "phone", "email"), ignoreCase = true)

        val json = JSONObject()
        json.put("name", "John Doe")
        json.put("phone", "1234567890")
        json.put("phone1", "1234567890")
        json.put("email", "james.c.mcreynolds@example-pet-store.com")

        var maskCnt = 0
        var maskCnt1 = 0

        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            when (val value = json.opt(key)) {
                is String -> {
                    var result = rule.mask(key, value)
                    if (result != null) {
                        maskCnt++
                    }
                    result = rule1.mask(key, value)
                    if (result != null) {
                        maskCnt1++
                    }
                }
            }
        }

        assertEquals(2, maskCnt)
        assertEquals(3, maskCnt1)
    }

    @Test
    fun emailJsonMaskTest() {
        val emailRule = MaskRules.email(listOf("email", "email1", "email2"))
        val emailRule1 =
            MaskRules.email(listOf("email", "EMAIL", "EMAIL1", "email2"), ignoreCase = true)

        val emailJson = JSONObject()
        emailJson.put("email", "1234567892220@gmail.com")
        emailJson.put("email1", "email10@gmail.com")
        emailJson.put("email2", "1234567890")
        emailJson.put("email3", "james.c.mcreynolds@example-pet-store.com")

        var emailMaskCnt = 0
        var emailMaskCnt1 = 0
        val emailKeys = emailJson.keys()
        while (emailKeys.hasNext()) {
            val key = emailKeys.next()
            when (val value = emailJson.opt(key)) {
                is String -> {
                    var result = emailRule.mask(key, value)
                    if (result != null) {
                        emailMaskCnt++
                    }
                    result = emailRule1.mask(key, value)
                    if (result != null) {
                        emailMaskCnt1++
                    }
                }
            }
        }
        assertEquals(3, emailMaskCnt)
        assertEquals(3, emailMaskCnt1)
    }
}