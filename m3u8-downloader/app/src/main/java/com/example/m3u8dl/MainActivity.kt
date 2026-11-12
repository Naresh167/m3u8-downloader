package com.example.m3u8dl

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.view.MotionEvent
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.MediaItem
import androidx.media3.common.util.Clock
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultAssetLoaderFactory
import androidx.media3.transformer.DefaultDecoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import org.json.JSONTokener
import java.io.File

@UnstableApi
class MainActivity : Activity() {

    private lateinit var urlInput: EditText
    private lateinit var goButton: Button
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var web: WebView

    private val main = Handler(Looper.getMainLooper())
    private var pageUrl = ""
    private var foundManifest: String? = null
    private var scanning = false
    private var scanDeadline = 0L
    private var tickCount = 0
    private var transformer: Transformer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        urlInput = findViewById(R.id.urlInput)
        goButton = findViewById(R.id.goButton)
        status = findViewById(R.id.status)
        progress = findViewById(R.id.progress)
        web = findViewById(R.id.web)

        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.mediaPlaybackRequiresUserGesture = false
        web.settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView, request: WebResourceRequest
            ): WebResourceResponse? {
                val u = request.url.toString()
                if (scanning && u.contains(".m3u8", ignoreCase = true)) {
                    main.post { onManifestFound(u) }
                }
                return null // let the request continue normally
            }

            override fun onPageFinished(view: WebView, url: String?) {
                if (scanning) view.evaluateJavascript(PLAY_JS, null)
            }
        }

        goButton.setOnClickListener { start(urlInput.text.toString()) }
        handleIncoming(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncoming(intent)
    }

    private fun handleIncoming(i: Intent?) {
        if (i?.action == Intent.ACTION_SEND) {
            val text = i.getStringExtra(Intent.EXTRA_TEXT) ?: return
            val link = Regex("https?://\\S+").find(text)?.value ?: return
            urlInput.setText(link)
            start(link)
        }
    }

    // ---------------------------------------------------------------- step 1: find manifest

    private fun start(raw: String) {
        val url = raw.trim().let { if (it.startsWith("http")) it else "https://$it" }
        if (url.length < 10) {
            say("Enter a valid URL.")
            return
        }
        cancelWork()
        pageUrl = url
        foundManifest = null
        progress.isIndeterminate = true
        goButton.isEnabled = false

        // The input may already be the manifest.
        if (url.contains(".m3u8", ignoreCase = true)) {
            onManifestFound(url)
            return
        }

        say("Loading page and looking for the .m3u8 manifest…")
        scanning = true
        tickCount = 0
        scanDeadline = System.currentTimeMillis() + 45_000
        web.loadUrl(url)
        main.postDelayed(scanTick, 2000)
    }

    /** Fallback scan: network entries the page made + any m3u8 string in the HTML. */
    private val scanTick = object : Runnable {
        override fun run() {
            if (!scanning) return
            tickCount++

            // Auto "tap to play": JS clicks every tick, plus a real screen tap
            // (works even inside cross-origin iframes) at a few likely spots.
            web.evaluateJavascript(PLAY_JS, null)
            if (tickCount in 2..9) {
                val spots = listOf(0.5f to 0.5f, 0.5f to 0.4f, 0.5f to 0.6f, 0.4f to 0.5f, 0.6f to 0.5f)
                val (fx, fy) = spots[(tickCount - 2) % spots.size]
                tapWeb(web.width * fx, web.height * fy)
            }

            web.evaluateJavascript(SCAN_JS) { raw ->
                val found = try {
                    (JSONTokener(raw).nextValue() as? String).orEmpty()
                } catch (e: Exception) {
                    ""
                }
                if (found.isNotEmpty() && scanning) {
                    onManifestFound(found.replace("&amp;", "&"))
                }
            }
            if (System.currentTimeMillis() > scanDeadline) {
                scanning = false
                finishWithError(
                    "No .m3u8 manifest found. If the page needs a tap on Play, " +
                        "try again and tap Play in the page below."
                )
            } else {
                main.postDelayed(this, 2000)
            }
        }
    }

    /** Simulates a finger tap on the WebView at (x, y). */
    private fun tapWeb(x: Float, y: Float) {
        val t = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0)
        web.dispatchTouchEvent(down)
        down.recycle()
        main.postDelayed({
            val t2 = SystemClock.uptimeMillis()
            val up = MotionEvent.obtain(t2 - 60, t2, MotionEvent.ACTION_UP, x, y, 0)
            web.dispatchTouchEvent(up)
            up.recycle()
        }, 60)
    }

    private fun onManifestFound(url: String) {
        if (foundManifest != null) return
        foundManifest = url
        scanning = false
        web.stopLoading()
        say("Manifest found:\n$url\n\nDownloading…")
        startExport(url)
    }

    // ---------------------------------------------------------------- step 2: download as MP4

    private fun startExport(manifestUrl: String) {
        val origin = Uri.parse(pageUrl).let { "${it.scheme}://${it.host}" }
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(web.settings.userAgentString)
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(mapOf("Referer" to pageUrl, "Origin" to origin))
        val mediaSourceFactory = DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http))

        val assetLoader = DefaultAssetLoaderFactory(
            this, DefaultDecoderFactory.Builder(this).build(), Clock.DEFAULT, mediaSourceFactory
        )

        val tmp = File(cacheDir, "dl_${System.currentTimeMillis()}.mp4")
        val t = Transformer.Builder(this)
            .setAssetLoaderFactory(assetLoader)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    main.removeCallbacks(progressTick)
                    saveToDownloads(tmp)
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException
                ) {
                    main.removeCallbacks(progressTick)
                    tmp.delete()
                    finishWithError("Download failed: ${exportException.message}")
                }
            })
            .build()
        transformer = t

        // No effects are applied, so compatible streams are copied without re-encoding.
        val item = EditedMediaItem.Builder(MediaItem.fromUri(manifestUrl)).build()
        t.start(item, tmp.absolutePath)
        main.post(progressTick)
    }

    private val progressHolder = ProgressHolder()
    private val progressTick = object : Runnable {
        override fun run() {
            val t = transformer ?: return
            if (t.getProgress(progressHolder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                progress.isIndeterminate = false
                progress.progress = progressHolder.progress
                status.text = "Downloading… ${progressHolder.progress}%"
            } else {
                progress.isIndeterminate = true
            }
            main.postDelayed(this, 500)
        }
    }

    private fun saveToDownloads(tmp: File) {
        say("Saving to Downloads…")
        Thread {
            val name = "video_${Uri.parse(pageUrl).host?.replace('.', '_')}_${System.currentTimeMillis()}.mp4"
            try {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, "video/mp4")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("Could not create file in Downloads")
                contentResolver.openOutputStream(uri)!!.use { out ->
                    tmp.inputStream().use { it.copyTo(out) }
                }
                tmp.delete()
                main.post {
                    progress.isIndeterminate = false
                    progress.progress = 100
                    goButton.isEnabled = true
                    say("Done. Saved to Downloads/$name")
                    Toast.makeText(this, "Saved: $name", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                main.post { finishWithError("Could not save file: ${e.message}") }
            }
        }.start()
    }

    // ---------------------------------------------------------------- helpers

    private fun say(msg: String) {
        status.text = msg
    }

    private fun finishWithError(msg: String) {
        progress.isIndeterminate = false
        progress.progress = 0
        goButton.isEnabled = true
        say(msg)
    }

    private fun cancelWork() {
        scanning = false
        main.removeCallbacks(scanTick)
        main.removeCallbacks(progressTick)
        transformer?.cancel()
        transformer = null
    }

    override fun onDestroy() {
        cancelWork()
        web.destroy()
        super.onDestroy()
    }

    companion object {
        // Nudge players to start so the page requests its manifest.
        private const val PLAY_JS = """
            (function(){
              var SEL = 'video, [class*="play" i], [id*="play" i], [aria-label*="play" i], ' +
                        '[title*="play" i], .vjs-big-play-button, .jw-icon-display, ' +
                        '.plyr__control--overlaid, .ytp-large-play-button, button[class*="big" i]';
              function fire(el) {
                ['pointerdown','mousedown','pointerup','mouseup','click'].forEach(function(t){
                  try { el.dispatchEvent(new MouseEvent(t, {bubbles:true, cancelable:true, view:window})); } catch(e) {}
                });
                try { el.click(); } catch(e) {}
              }
              function run(doc) {
                doc.querySelectorAll('video').forEach(function(v){
                  v.muted = true; try { var p = v.play(); if (p && p.catch) p.catch(function(){}); } catch(e) {}
                });
                doc.querySelectorAll(SEL).forEach(fire);
                doc.querySelectorAll('iframe').forEach(function(f){
                  try { if (f.contentDocument) run(f.contentDocument); } catch(e) {} // same-origin only
                });
              }
              run(document);
            })();
        """

        private const val SCAN_JS = """
            (function(){
              var e = performance.getEntriesByType('resource');
              for (var i = 0; i < e.length; i++) {
                if (e[i].name.indexOf('.m3u8') > -1) return e[i].name;
              }
              var h = document.documentElement.outerHTML.replace(/\\\//g, '/');
              var m = h.match(/https?:\/\/[^"'\s\\<>]+?\.m3u8[^"'\s\\<>]*/i);
              return m ? m[0] : '';
            })();
        """
    }
}
