package id.ifan.routtrans.payload.web

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import id.ifan.routtrans.payload.config.Config
import id.ifan.routtrans.payload.text.TextFilter
import id.ifan.routtrans.payload.translate.TranslatorClient
import org.json.JSONArray
import org.json.JSONObject
import java.util.WeakHashMap
import java.util.concurrent.Executors

/**
 * Menerjemahkan teks di dalam WebView.
 *
 * Halaman web tidak bisa dijangkau lewat pohon View, jadi skrip kecil
 * dijalankan di halaman untuk mengumpulkan text node. Hasil terjemahan
 * dikirim balik lewat jembatan JavaScript setelah provider selesai.
 */
class WebViewTranslator(
    private val config: Config,
    private val client: TranslatorClient,
) {

    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "RoutTransWeb").apply { isDaemon = true }
    }
    private val installed = WeakHashMap<WebView, Long>()
    private val attempted = HashMap<String, Long>()
    @Volatile
    private var busy = false
    private var lastItemLog = 0L

    fun scan(root: View) {
        // Stack dipakai agar pohon View yang dalam tidak memicu StackOverflowError
        // di proses aplikasi target.
        val stack = ArrayDeque<View>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val view = stack.removeLast()
            try {
                if (view is WebView) {
                    install(view)
                } else if (view is ViewGroup) {
                    for (index in view.childCount - 1 downTo 0) {
                        stack.addLast(view.getChildAt(index))
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "WebView dilewati", t)
            }
        }
    }

    private fun install(webView: WebView) {
        // Jangan mengaktifkan JavaScript yang memang dimatikan aplikasi.
        if (!webView.settings.javaScriptEnabled) return
        val now = SystemClock.uptimeMillis()
        val last = installed[webView] ?: 0L
        // Navigasi ke halaman baru membuang konteks JavaScript, jadi skrip
        // dipasang ulang berkala. Skripnya sendiri langsung berhenti bila
        // masih aktif, sehingga ini murah.
        if (now - last < INSTALL_INTERVAL_MS) return
        installed[webView] = now
        if (last == 0L) {
            Log.i(TAG, "WebView ditemukan: ${webView.javaClass.name}")
        }
        try {
            webView.addJavascriptInterface(Bridge(webView), BRIDGE)
            webView.evaluateJavascript(SCRIPT, null)
        } catch (t: Throwable) {
            Log.w(TAG, "Skrip WebView gagal dipasang", t)
        }
    }

    private inner class Bridge(private val webView: WebView) {

        @JavascriptInterface
        fun submit(json: String) {
            val items = parse(json)
            if (items.isEmpty() || busy) return

            val now = SystemClock.uptimeMillis()
            val texts = items.map { it.text }
                .distinct()
                .filter { TextFilter.shouldTranslate(it, config.minLength, config.maxLength) }
                .filter { canAttempt(it, now) }
            if (texts.isEmpty()) return
            logItems(texts.size)

            busy = true
            worker.execute {
                // Kegagalan provider tidak boleh membekukan WebView: busy
                // selalu dilepas di finally.
                val results = try {
                    client.translate(config.from, config.to, texts)
                } catch (t: Throwable) {
                    Log.w(TAG, "Batch WebView gagal", t)
                    null
                }
                handler.post {
                    try {
                        if (results == null) {
                            val now = SystemClock.uptimeMillis()
                            synchronized(attempted) { texts.forEach { attempted[it] = now } }
                        } else {
                            val bySource = texts.indices.associate { texts[it] to results[it] }
                            val response = JSONArray()
                            for (item in items) {
                                val translated = bySource[item.text]
                                response.put(
                                    JSONObject()
                                        .put("id", item.id)
                                        .put("text", translated ?: JSONObject.NULL)
                                )
                            }
                            try {
                                webView.evaluateJavascript("window.$APPLY($response)", null)
                            } catch (t: Throwable) {
                                Log.w(TAG, "Hasil WebView gagal dipasang", t)
                            }
                            synchronized(attempted) {
                                for ((text, value) in bySource) {
                                    if (value == null) attempted[text] = SystemClock.uptimeMillis()
                                }
                            }
                        }
                    } finally {
                        busy = false
                    }
                }
            }
        }
    }

    /** Mencatat jumlah teks yang dikirim halaman, dibatasi agar log tidak banjir. */
    private fun logItems(count: Int) {
        val now = SystemClock.uptimeMillis()
        if (now - lastItemLog < 5000L) return
        lastItemLog = now
        Log.i(TAG, "WebView mengirim $count teks")
    }

    private fun canAttempt(text: String, now: Long): Boolean = synchronized(attempted) {        val last = attempted[text] ?: return@synchronized true
        if (now - last < RETRY_MS) return@synchronized false
        attempted.remove(text)
        true
    }

    private fun parse(json: String): List<Item> = try {
        val array = JSONArray(json)
        val items = ArrayList<Item>(array.length())
        for (index in 0 until array.length()) {
            val obj = array.optJSONObject(index) ?: continue
            val text = obj.optString("text")
            if (text.isBlank()) continue
            items.add(Item(obj.optInt("id"), text))
        }
        items
    } catch (e: Exception) {
        emptyList()
    }

    private data class Item(val id: Int, val text: String)

    private companion object {
        const val TAG = "RoutTrans"
        const val BRIDGE = "RoutTransBridge"
        const val APPLY = "__routTransApply"
        const val RETRY_MS = 60_000L

        /** Jeda pemasangan ulang skrip per WebView untuk menangkap navigasi. */
        const val INSTALL_INTERVAL_MS = 1500L

        /**
         * Skrip sengaja kecil dan tanpa dependensi. Tiap text node diberi
         * penanda agar tidak dikirim berulang, dan penanda dibersihkan lagi
         * bila terjemahan gagal supaya bisa dicoba ulang nanti.
         */
        const val SCRIPT = """
(function () {
  if (window.__routTransInstalled) { return; }
  window.__routTransInstalled = true;
  window.__routTransNodes = {};
  var sequence = 0;

  window.__routTransApply = function (items) {
    for (var i = 0; i < items.length; i++) {
      var item = items[i];
      var node = window.__routTransNodes[item.id];
      if (!node) { continue; }
      if (item.text === null) {
        node.__routTransDone = null;
        continue;
      }
      node.nodeValue = item.text;
      node.__routTransDone = item.text;
    }
  };

  function collect() {
    if (!document.body) { return; }
    var walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, null);
    var output = [];
    var node;
    while ((node = walker.nextNode())) {
      var value = node.nodeValue;
      if (!value) { continue; }
      var text = value.trim();
      if (text.length < 2 || text.length > 400) { continue; }
      if (node.__routTransDone === text) { continue; }
      if (!/[A-Za-z\u00C0-\u024F\u0400-\u04FF\u3040-\u30FF\u4E00-\u9FFF\uAC00-\uD7AF]/.test(text)) { continue; }
      if (!node.__routTransId) { node.__routTransId = ++sequence; }
      window.__routTransNodes[node.__routTransId] = node;
      node.__routTransDone = text;
      output.push({ id: node.__routTransId, text: text });
      if (output.length >= 40) { break; }
    }
    if (output.length && window.RoutTransBridge) {
      window.RoutTransBridge.submit(JSON.stringify(output));
    }
  }

  setInterval(collect, 900);
  collect();
})();
"""
    }
}
