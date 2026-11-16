package no.lionflow.app.car

import no.lionflow.app.ui.l10n.tr

import android.annotation.SuppressLint
import android.app.Presentation
import android.graphics.Color
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.SearchTemplate
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.URLEncoder

/**
 * YouTube on the car screen — only while parked. A WebView limited to YouTube is drawn on the
 * surface Android Auto gives us. As soon as the car moves, the page is destroyed and the screen closes.
 */
class YouTubeScreen(ctx: CarContext, private val guard: ParkGuard) : Screen(ctx), SurfaceCallback, DefaultLifecycleObserver {
    private val main = Handler(Looper.getMainLooper())
    private var display: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private var web: WebView? = null
    private var root: FrameLayout? = null
    private var fullscreen: View? = null
    private var pendingUrl = HOME
    private var closing = false

    init {
        lifecycle.addObserver(this)
    }

    override fun onCreate(owner: LifecycleOwner) {
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(this)
        lifecycleScope.launch {
            // Re-check every second; leave immediately when the car is no longer parked.
            while (isActive) {
                guard.update()
                if (!guard.parked.value) { close("Video stopped — the car is moving"); break }
                delay(1000)
            }
        }
    }

    /** YouTube's start page is empty when nobody is signed in, so go straight to search the first time. */
    private var askedSearch = false
    override fun onStart(owner: LifecycleOwner) {
        if (askedSearch || closing || pendingUrl != HOME) return
        askedSearch = true
        main.post {
            if (!closing) try { screenManager.push(YouTubeSearchScreen(carContext) { q -> search(q) }) } catch (_: Exception) { }
        }
    }

    override fun onDestroy(owner: LifecycleOwner) {
        try { carContext.getCarService(AppManager::class.java).setSurfaceCallback(null) } catch (_: Exception) { }
        main.post { tearDown() }
    }

    private fun close(msg: String) {
        if (closing) return
        closing = true
        main.post { tearDown() }
        CarToast.makeText(carContext, tr(msg), CarToast.LENGTH_LONG).show()
        screenManager.pop()
    }

    fun search(q: String) {
        val url = "https://m.youtube.com/results?search_query=" + URLEncoder.encode(q, "UTF-8")
        pendingUrl = url
        main.post { web?.loadUrl(url) }
    }

    private fun ytIcon(res: Int) = androidx.car.app.model.CarIcon.Builder(androidx.core.graphics.drawable.IconCompat.createWithResource(carContext, res)).build()

    override fun onGetTemplate(): Template {
        val strip = ActionStrip.Builder()
            .addAction(Action.Builder().setTitle(tr("Search")).setOnClickListener {
                screenManager.push(YouTubeSearchScreen(carContext) { q -> search(q) })
            }.build())
            .addAction(Action.Builder().setIcon(ytIcon(no.lionflow.app.R.drawable.ic_car_back)).setOnClickListener {
                main.post { web?.let { if (it.canGoBack()) it.goBack() } }
            }.build())
            .addAction(Action.Builder().setIcon(ytIcon(no.lionflow.app.R.drawable.ic_car_close)).setOnClickListener { close("YouTube closed") }.build())
            .build()
        return NavigationTemplate.Builder()
            .setActionStrip(strip)
            .setMapActionStrip(ActionStrip.Builder().addAction(Action.PAN).build())
            .build()
    }

    // ---------------- surface ----------------

    override fun onSurfaceAvailable(c: SurfaceContainer) { main.post { setUp(c) } }
    override fun onSurfaceDestroyed(c: SurfaceContainer) { main.post { tearDown() } }
    private var visible: Rect? = null
    override fun onVisibleAreaChanged(visibleArea: Rect) { visible = Rect(visibleArea); main.post { applyPadding() } }

    /** Normal video stays inside the visible area; fullscreen covers the whole screen, behind the car's menus too. */
    private fun applyPadding() {
        val r = root ?: return
        val v = visible
        if (fullscreen != null || v == null) r.setPadding(0, 0, 0, 0)
        else r.setPadding(v.left, v.top, (r.width - v.right).coerceAtLeast(0), (r.height - v.bottom).coerceAtLeast(0))
    }

    // Scrolling is sent as a real finger drag, so Shorts (which swipes inside the page, not the window) works too.
    private var dragDown = 0L
    private var dragX = 0f
    private var dragY = 0f
    private val endDrag = Runnable {
        val w = web ?: return@Runnable
        if (dragDown != 0L) {
            val e = MotionEvent.obtain(dragDown, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, dragX, dragY, 0)
            try { w.dispatchTouchEvent(e) } finally { e.recycle() }
            dragDown = 0L
        }
    }

    override fun onScroll(distanceX: Float, distanceY: Float) {
        main.post {
            val w = web ?: return@post
            val now = SystemClock.uptimeMillis()
            main.removeCallbacks(endDrag)
            if (dragDown == 0L) {
                dragDown = now; dragX = w.width / 2f; dragY = w.height / 2f
                val d = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, dragX, dragY, 0)
                try { w.dispatchTouchEvent(d) } finally { d.recycle() }
            }
            // The car reports how far the content should move; the finger moves the opposite way.
            dragX = (dragX - distanceX).coerceIn(1f, w.width - 1f)
            dragY = (dragY - distanceY).coerceIn(1f, w.height - 1f)
            val m = MotionEvent.obtain(dragDown, now, MotionEvent.ACTION_MOVE, dragX, dragY, 0)
            try { w.dispatchTouchEvent(m) } finally { m.recycle() }
            main.postDelayed(endDrag, 120)
        }
    }

    override fun onClick(x: Float, y: Float) {
        main.post {
            val target: View = fullscreen ?: web ?: return@post
            val loc = IntArray(2); target.getLocationInWindow(loc)
            val lx = x - loc[0]; val ly = y - loc[1]
            // A real finger moves a little between down and up; YouTube's seek bar ignores a perfectly still tap.
            val t = SystemClock.uptimeMillis()
            fun send(action: Int, dt: Long, dx: Float) {
                main.postDelayed({
                    val e = MotionEvent.obtain(t, t + dt, action, lx + dx, ly, 0)
                    try { target.dispatchTouchEvent(e) } finally { e.recycle() }
                }, dt)
            }
            send(MotionEvent.ACTION_DOWN, 0, 0f)
            send(MotionEvent.ACTION_MOVE, 40, 1f)
            send(MotionEvent.ACTION_MOVE, 80, 2f)
            send(MotionEvent.ACTION_UP, 120, 2f)
        }
    }

    private var focus: android.media.AudioFocusRequest? = null
    private fun takeAudio() {
        try {
            val am = carContext.getSystemService(android.media.AudioManager::class.java)
            val req = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MOVIE).build())
                .setOnAudioFocusChangeListener { }.build()
            am.requestAudioFocus(req); focus = req
        } catch (_: Exception) { }
    }
    private fun dropAudio() {
        try { focus?.let { carContext.getSystemService(android.media.AudioManager::class.java).abandonAudioFocusRequest(it) } } catch (_: Exception) { }
        focus = null
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setUp(c: SurfaceContainer) {
        tearDown()
        if (closing || !guard.parked.value) return
        val surface = c.surface ?: return
        if (c.width <= 0 || c.height <= 0) return
        val vd = carContext.getSystemService(DisplayManager::class.java).createVirtualDisplay(
            "LionFlowYouTube", c.width, c.height, c.dpi, surface, DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY)
        display = vd
        val p = Presentation(carContext, vd.display)
        val r = FrameLayout(p.context).apply { setBackgroundColor(Color.BLACK) }
        val w = WebView(p.context).apply {
            setBackgroundColor(Color.BLACK)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.setSupportMultipleWindows(false)
            // YouTube treats the default "wv" WebView agent as a stripped-down embed and often won't play; look like Chrome.
            settings.userAgentString = settings.userAgentString.replace("; wv", "").replace(Regex(" Version/[0-9.]+"), "")
            val cm = android.webkit.CookieManager.getInstance()
            cm.setAcceptCookie(true); cm.setAcceptThirdPartyCookies(this, true)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean = !allowed(req.url)
                override fun onReceivedError(view: WebView, req: WebResourceRequest, err: android.webkit.WebResourceError) {
                    if (req.isForMainFrame) CarToast.makeText(carContext, "YouTube: ${err.description}", CarToast.LENGTH_LONG).show()
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                    fullscreen = view
                    applyPadding()
                    r.addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
                }
                override fun onHideCustomView() {
                    fullscreen?.let { r.removeView(it) }
                    fullscreen = null
                    applyPadding()
                }
            }
        }
        r.addView(w, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        p.setContentView(r)
        p.show()
        presentation = p; web = w; root = r
        takeAudio()
        r.post { applyPadding() }
        w.loadUrl(pendingUrl)
    }

    private fun tearDown() {
        web?.let { w ->
            pendingUrl = w.url ?: pendingUrl
            try { w.loadUrl("about:blank"); w.stopLoading(); w.destroy() } catch (_: Exception) { }
        }
        web = null; fullscreen = null; root = null
        try { presentation?.dismiss() } catch (_: Exception) { }
        presentation = null
        display?.release(); display = null
        dropAudio()
    }

    companion object {
        const val HOME = "https://m.youtube.com/"
        private val HOSTS = listOf("youtube.com", "youtu.be", "google.com", "gstatic.com", "ytimg.com", "googlevideo.com")

        /** Only YouTube (and the Google pages it needs, like the cookie consent page) may open. */
        fun allowed(u: Uri): Boolean {
            if (u.scheme != "https") return false
            val h = u.host ?: return false
            return HOSTS.any { h == it || h.endsWith(".$it") }
        }
    }
}

class YouTubeSearchScreen(ctx: CarContext, private val onSearch: (String) -> Unit) : Screen(ctx) {
    override fun onGetTemplate(): Template =
        SearchTemplate.Builder(object : SearchTemplate.SearchCallback {
            override fun onSearchSubmitted(searchText: String) {
                if (searchText.isNotBlank()) onSearch(searchText.trim())
                screenManager.pop()
            }
        })
            .setHeaderAction(Action.BACK)
            .setSearchHint(tr("Search YouTube"))
            .setShowKeyboardByDefault(true)
            .build()
}
