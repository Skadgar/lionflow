package no.lionflow.app.car

import android.graphics.Canvas
import android.graphics.Picture
import android.graphics.Rect
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Surface
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * The one drawing surface Android Auto gives LionFlow, shared by every screen the app draws itself.
 *
 * A surface takes one drawer at a time, so there is exactly one of these per session and it is the only thing that
 * ever touches the surface. The screen that is showing is its [Client]: it is asked to draw on the main thread
 * (where the car's state lives), but only into a recording. The recording is then put on the surface by a thread of
 * its own. If the car is slow to take a picture, or stops taking them, only that thread waits: the rest of the app,
 * and the app on the phone, carry on.
 */
internal class CarSurface(private val ctx: CarContext, private val log: (String, Throwable?) -> Unit) : SurfaceCallback {

    /** A screen that draws itself. All of it is called on the main thread. */
    interface Client {
        /** Draw the whole screen. [c] only records, so this must be quick: no files, no network, no waiting. */
        fun paint(c: Canvas, f: Frame)
        fun onTap(x: Float, y: Float) {}
        fun onScroll(dx: Float, dy: Float) {}
        fun onFling(vx: Float, vy: Float) {}
    }

    /** The surface's size, and the parts of it Android Auto leaves free (null until it has said). */
    class Frame(val width: Int, val height: Int, val dpi: Int, val visible: Rect?, val stable: Rect?)

    private val main = Handler(Looper.getMainLooper())
    private val thread = HandlerThread("LionFlowCarDraw").apply { start() }
    private val render = Handler(thread.looper)

    // Main thread only.
    private var container: SurfaceContainer? = null
    private var visible: Rect? = null
    private var stable: Rect? = null
    private var client: Client? = null
    /** The screen the surface is lent to (a video page), which then gets everything Android Auto says about it. */
    private var borrower: SurfaceCallback? = null
    private val lent: Boolean get() = borrower != null
    private var closed = false
    private var queued = false
    private var surfaceAt = 0L

    // Draw thread only.
    private var surface: Surface? = null
    private var ownSurface = false
    /** The surface as Android Auto gave it, to draw through if our own handle to it turns out not to work. */
    private var given: Surface? = null
    private var ownWorked = false
    private var failures = 0

    /** The newest recording not yet on the surface. Older ones are simply dropped. */
    private val next = AtomicReference<Picture?>(null)

    /** Tells Android Auto that this is where the surface goes. Call once the session exists. */
    fun start() {
        if (closed) return
        try { ctx.getCarService(AppManager::class.java).setSurfaceCallback(this) }
        catch (e: Exception) { log("surface: couldn't ask for it", e) }
    }

    /** [c] is now the screen that draws. */
    fun attach(c: Client) { client = c; invalidate() }

    /** [c] no longer shows. Does nothing when another screen has taken over in the meantime. */
    fun detach(c: Client) { if (client === c) client = null }

    /** Draw again soon. Many calls in a row make one drawing. Safe from any thread. */
    fun invalidate() {
        if (Looper.myLooper() != main.looper) { main.post { invalidate() }; return }
        if (queued || closed) return
        queued = true
        main.post { queued = false; record() }
    }

    private fun record() {
        if (lent || closed) return
        val sc = container ?: return
        val cl = client ?: return
        val w = sc.width; val h = sc.height
        if (w <= 0 || h <= 0) return
        // Right after the surface comes, Android Auto says which part of it is free. Waiting a moment for that saves
        // drawing the first picture twice, in two layouts.
        if (visible == null && stable == null && System.currentTimeMillis() - surfaceAt < 150) {
            main.postDelayed({ invalidate() }, 150)
            return
        }
        val pic = Picture()
        try {
            val canvas = pic.beginRecording(w, h)
            // Whatever goes wrong while drawing, the app carries on: what was drawn so far is shown, and it is logged.
            try { cl.paint(canvas, Frame(w, h, sc.dpi, visible, stable)) }
            catch (e: Throwable) { log("drawing ${cl.javaClass.simpleName}", e) }
            pic.endRecording()
        } catch (e: Throwable) { log("recording", e); return }
        next.set(pic)
        render.removeCallbacks(draw)
        render.post(draw)
    }

    private val draw: Runnable = object : Runnable {
        override fun run() {
            val pic = next.getAndSet(null) ?: return
            val s = surface
            if (s == null || !s.isValid) { next.compareAndSet(null, pic); return }   // kept for when a surface comes
            var canvas: Canvas? = null
            var ok = false
            var error: Throwable? = null
            try {
                val locked = s.lockCanvas(null)
                canvas = locked
                locked.drawPicture(pic)
                ok = true
            } catch (e: Throwable) {
                error = e
            } finally {
                if (canvas != null) try { s.unlockCanvasAndPost(canvas) } catch (e: Throwable) { ok = false; error = error ?: e }
            }
            if (ok) { failures = 0; if (ownSurface) ownWorked = true; return }
            // The surface wasn't ready (something else may still be letting go of it): try again shortly, then give up
            // until the next change.
            failures++
            if (failures == 3 || failures == 25) log("surface: couldn't draw (try $failures)", error)
            // If a handle of our own has never drawn anything, don't insist on it for long.
            val g = given
            if (failures == 4 && ownSurface && !ownWorked && g != null && g.isValid) {
                log("surface: drawing through the given handle instead", null)
                val tries = failures
                adopt(g, own = false)
                failures = tries
            }
            if (failures <= 40) { next.compareAndSet(null, pic); render.postDelayed(this, (60L * failures).coerceAtMost(1000L)) }
        }
    }

    /**
     * A handle of our own to the surface Android Auto gave. Drawing connects a handle to the surface, and only one
     * can be connected at a time; with a handle of our own, letting go of it frees the surface for whoever borrows
     * it (see [lend]), while the one Android Auto gave stays untouched and can be handed on.
     */
    private fun ownHandle(original: Surface?): Surface? {
        if (original == null || !original.isValid) return null
        val p = android.os.Parcel.obtain()
        return try {
            original.writeToParcel(p, 0)
            p.setDataPosition(0)
            Surface.CREATOR.createFromParcel(p)
        } catch (e: Throwable) {
            log("surface: couldn't make a handle of our own", e)
            null
        } finally { p.recycle() }
    }

    /** Draw thread: [s] is the handle to draw through from now on; the one before is let go of (if it was our own). */
    private fun adopt(s: Surface?, own: Boolean = true) {
        val old = surface
        if (old != null && old !== s && ownSurface) try { old.release() } catch (_: Throwable) { }
        surface = s
        ownSurface = own
        if (s == null) given = null
        failures = 0
        if (s != null && next.get() != null) { render.removeCallbacks(draw); render.post(draw) }
    }

    /** Lets go of the surface on the draw thread, and waits a moment for that to have happened. */
    private fun dropNow() {
        next.set(null)
        val done = CountDownLatch(1)
        render.post { adopt(null); done.countDown() }
        try { done.await(250, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { }
    }

    /** Starts drawing on the surface Android Auto last gave (if there is one). */
    private fun takeSurface() {
        val given = container?.surface
        if (given != null) {
            val own = ownHandle(given)
            // Without a handle of our own (which shouldn't happen) the given one is drawn through, and never let go of.
            render.post { if (own != null) { adopt(own); this.given = given } else adopt(given, own = false) }
        }
        invalidate()
    }

    // ---------------- from Android Auto (main thread) ----------------

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        if (closed) return
        // The free parts Android Auto has told about are kept: it may say them before a new surface as well as after.
        if (container == null) surfaceAt = System.currentTimeMillis()
        container = surfaceContainer
        val b = borrower
        if (b != null) { forward("surface") { b.onSurfaceAvailable(surfaceContainer) }; return }
        takeSurface()
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        container = null
        val b = borrower
        if (b != null) { forward("surface gone") { b.onSurfaceDestroyed(surfaceContainer) }; return }
        dropNow()
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        if (visibleArea != visible) { visible = Rect(visibleArea); invalidate() }
        borrower?.let { b -> forward("visible area") { b.onVisibleAreaChanged(visibleArea) } }
    }

    override fun onStableAreaChanged(stableArea: Rect) {
        if (stableArea != stable) { stable = Rect(stableArea); invalidate() }
        borrower?.let { b -> forward("stable area") { b.onStableAreaChanged(stableArea) } }
    }

    override fun onClick(x: Float, y: Float) {
        val b = borrower
        if (b != null) forward("tap") { b.onClick(x, y) } else forward("tap") { client?.onTap(x, y) }
    }

    override fun onScroll(distanceX: Float, distanceY: Float) {
        val b = borrower
        if (b != null) forward("scroll") { b.onScroll(distanceX, distanceY) } else forward("scroll") { client?.onScroll(distanceX, distanceY) }
    }

    override fun onFling(velocityX: Float, velocityY: Float) {
        val b = borrower
        if (b != null) forward("fling") { b.onFling(velocityX, velocityY) } else forward("fling") { client?.onFling(velocityX, velocityY) }
    }

    override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) {
        borrower?.let { b -> forward("pinch") { b.onScale(focusX, focusY, scaleFactor) } }
    }

    /** Nothing a screen does with a tap or the surface may come back to Android Auto as a crash. */
    private inline fun forward(what: String, block: () -> Unit) { try { block() } catch (e: Exception) { log(what, e) } }

    // ---------------- lending the surface to the video screen ----------------

    /**
     * Hands the surface over to a screen that shows something else on it (a video page). This object lets go of it,
     * so the other one can connect, and passes on everything Android Auto says about the surface until [takeBack].
     */
    fun lend(to: SurfaceCallback) {
        if (closed) return
        borrower = to
        dropNow()
        forward("lending the surface") {
            container?.let { to.onSurfaceAvailable(it) }
            visible?.let { to.onVisibleAreaChanged(it) }
            stable?.let { to.onStableAreaChanged(it) }
        }
    }

    /** [from] is done with the surface: the drawn screens have it again. */
    fun takeBack(from: SurfaceCallback) {
        if (borrower !== from) return
        borrower = null
        if (closed) return
        takeSurface()
    }

    /** The session is over. */
    fun close() {
        if (closed) return
        closed = true
        client = null
        try { ctx.getCarService(AppManager::class.java).setSurfaceCallback(null) } catch (_: Exception) { }
        next.set(null)
        render.post { adopt(null) }
        thread.quitSafely()
    }
}
