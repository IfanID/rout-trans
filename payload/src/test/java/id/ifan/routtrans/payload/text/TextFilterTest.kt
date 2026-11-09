package id.ifan.routtrans.payload.text

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextFilterTest {

    @Test
    fun translatesNormalSentences() {
        assertTrue(TextFilter.shouldTranslate("Hello world", 2, 400))
        assertTrue(TextFilter.shouldTranslate("안녕하세요", 2, 400))
        assertTrue(TextFilter.shouldTranslate("Halo dunia", 2, 400))
    }

    @Test
    fun skipsNonLanguageText() {
        assertFalse(TextFilter.shouldTranslate("12345", 2, 400))
        assertFalse(TextFilter.shouldTranslate("!!! ---", 2, 400))
        assertFalse(TextFilter.shouldTranslate("{0}", 2, 400))
    }

    @Test
    fun skipsUrlsAndEmails() {
        assertFalse(TextFilter.shouldTranslate("https://example.com/path", 2, 400))
        assertFalse(TextFilter.shouldTranslate("www.example.com", 2, 400))
        assertFalse(TextFilter.shouldTranslate("user@example.com", 2, 400))
    }

    @Test
    fun respectsLengthLimits() {
        assertFalse(TextFilter.shouldTranslate("a", 2, 400))
        assertFalse(TextFilter.shouldTranslate("abcd", 2, 3))
    }
}
