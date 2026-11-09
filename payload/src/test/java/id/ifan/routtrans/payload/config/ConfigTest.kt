package id.ifan.routtrans.payload.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigTest {

    @Test
    fun parsesJsonFromController() {
        val config = Config.parse(
            """{"from":"auto","to":"id","webview":false,"minLength":3,"maxLength":120,"scanIntervalMs":500}"""
        )

        assertEquals("auto", config?.from)
        assertEquals("id", config?.to)
        assertFalse(config?.webView ?: true)
        assertEquals(3, config?.minLength)
        assertEquals(120, config?.maxLength)
        assertEquals(500, config?.scanIntervalMs)
        assertTrue(config?.autoDetect ?: false)
    }

    @Test
    fun clampsValuesToSafeRange() {
        val config = Config.parse(
            """{"from":"en","to":"ko","minLength":0,"maxLength":999999,"scanIntervalMs":1}"""
        )

        assertEquals(1, config?.minLength)
        assertEquals(2000, config?.maxLength)
        assertEquals(150, config?.scanIntervalMs)
        assertFalse(config?.autoDetect ?: true)
    }

    @Test
    fun rejectsInvalidJson() {
        assertNull(Config.parse(null))
        assertNull(Config.parse(""))
        assertNull(Config.parse("bukan json"))
    }
}
