package id.ifan.routtrans.payload.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TranslationCacheTest {

    @Test
    fun storesAndReadsPerLanguagePair() {
        val cache = TranslationCache()
        cache.put("en", "id", "Hello", "Halo")

        assertEquals("Halo", cache.get("en", "id", "Hello"))
        assertNull(cache.get("en", "ko", "Hello"))
        assertNull(cache.get("id", "en", "Hello"))
    }

    @Test
    fun evictsLeastRecentlyUsedEntry() {
        val cache = TranslationCache(maxEntries = 2)
        cache.put("en", "id", "one", "satu")
        cache.put("en", "id", "two", "dua")

        // Sentuh "one" supaya "two" menjadi entri paling lama.
        assertEquals("satu", cache.get("en", "id", "one"))
        cache.put("en", "id", "three", "tiga")

        assertEquals(2, cache.size())
        assertEquals("satu", cache.get("en", "id", "one"))
        assertEquals("tiga", cache.get("en", "id", "three"))
        assertNull(cache.get("en", "id", "two"))
    }
}
