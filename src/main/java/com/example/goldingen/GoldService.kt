package com.example.goldingen

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.GestureResultCallback
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PointF
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import rikka.shizuku.Shizuku

/**
 * Goldingen - MU Immortal sari (golden) boss avi.
 *
 * Dongu:
 *  1) (buff acik ise) ATK ve DEF okunur; ikisi de minimumun altindaysa buff noktasina gidilir.
 *  2) Harita acilir, canli (parlak) sari kafataslari ve oyuncu imleci bulunur.
 *  3) Yaricap icinde canli boss varsa en yakina dokunulur, imlec kafatasinin ustune gelince
 *     harita kapanir ve oto atak acilir.
 *  4) Sag ustteki mini haritada boss kafatasi soluklasana kadar savasilir.
 *  5) Yaricap icinde boss yoksa Random dugmesine basilir ve basa donulur.
 */
class GoldService : AccessibilityService() {

    private class StopEx : RuntimeException()
    private class Restart : RuntimeException()

    private class Scan(val skulls: List<PointF>, val player: PointF?)

    private class MarkView(
        ctx: Context,
        val skulls: List<PointF>,
        val player: PointF?,
        val radius: Float,
        val dots: List<Triple<String, Float, Float>>,
        val rect: FloatArray?
    ) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onDraw(c: Canvas) {
            p.style = Paint.Style.STROKE
            p.strokeWidth = 6f
            p.color = Color.RED
            for (s in skulls) c.drawCircle(s.x, s.y, 34f, p)
            if (player != null) {
                p.color = Color.GREEN
                c.drawCircle(player.x, player.y, 22f, p)
                p.color = Color.YELLOW
                c.drawCircle(player.x, player.y, radius, p)
            }
            if (rect != null) {
                p.color = Color.CYAN
                c.drawRect(rect[0], rect[1], rect[2], rect[3], p)
            }
            p.style = Paint.Style.FILL
            p.textSize = 26f
            for (d in dots) {
                p.color = Color.MAGENTA
                c.drawCircle(d.second, d.third, 9f, p)
                p.color = Color.WHITE
                c.drawText(d.first, d.second + 12f, d.third - 8f, p)
            }
        }
    }

    companion object {
        val NAMES = listOf(
            "minimap", "closeMap", "random", "autoAtk", "miniTL", "miniBR",
            "revive", "chat", "chatTab", "buffLink", "closeChat"
        )

        val LABELS = mapOf(
            "minimap" to "Sağ üstteki mini harita (haritayı açar)",
            "closeMap" to "Harita ekranı X (kapat) düğmesi",
            "random" to "Haritadaki Random düğmesi",
            "autoAtk" to "Oto saldırı düğmesi (Golden Monster Auto-Play)",
            "miniTL" to "Mini haritanın SOL ÜST köşesi",
            "miniBR" to "Mini haritanın SAĞ ALT köşesi",
            "revive" to "Ölüm ekranında Free Revival düğmesi (ölüm ekranı açıkken işaretle)",
            "chat" to "Chat'i açan düğme/alan",
            "chatTab" to "Chat'te ilk sekme",
            "buffLink" to "Chat'te buff noktasının konum bağlantısı",
            "closeChat" to "Chat'i kapatma (isteğe bağlı, boş bırakılabilir)"
        )

        val DEFAULTS = mapOf(
            "minimap" to Pair(0.922f, 0.046f),
            "closeMap" to Pair(0.894f, 0.157f),
            "random" to Pair(0.868f, 0.814f),
            "miniTL" to Pair(0.865f, 0.012f),
            "miniBR" to Pair(0.985f, 0.190f)
        )

        // harita cizim alani (ekran orani). Random dugmesi ve yakinlastirma cubugu disarida kalir.
        const val MAP_X0 = 0.30f
        const val MAP_X1 = 0.84f
        const val MAP_Y0 = 0.18f
        const val MAP_Y1 = 0.84f
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager

    private var panel: LinearLayout? = null
    private var body: LinearLayout? = null
    private var menu: LinearLayout? = null
    private var extra: View? = null
    private var statusView: TextView? = null
    private var startBtn: Button? = null
    private var statsView: TextView? = null
    private var dotView: View? = null

    private val GOLD = 0xFFF5B83D.toInt()
    private val PANEL_BG = 0xF2161920.toInt()
    private val CARD_BG = 0xFF272C3A.toInt()
    private val MISSING_BG = 0xFF4A2429.toInt()
    private val TEXT_DIM = 0xFF9AA3B5.toInt()
    private val OK = 0xFF3DDC84.toInt()
    private val BAD = 0xFFE5484D.toInt()

    @Volatile
    private var running = false
    private var worker: Thread? = null

    private var sent = 0
    private var cancelled = 0
    private var kills = 0
    private var deaths = 0
    private var randoms = 0
    private var buffs = 0
    private var lastShot = 0L
    private var screenW = 1
    private var screenH = 1
    private var lastOcr = ""

    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    // ------------------------------------------------------------------ yasam dongusu

    override fun onServiceConnected() {
        super.onServiceConnected()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        refreshScreen()
        buildPanel()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        running = false
        worker?.interrupt()
        try {
            removeExtra()
            panel?.let { wm.removeView(it) }
            menu?.let { wm.removeView(it) }
        } catch (e: Exception) {
        }
        super.onDestroy()
    }

    // ------------------------------------------------------------------ yardimcilar

    private fun ui(block: () -> Unit) {
        main.post { block() }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun say(msg: String) {
        ui {
            statusView?.text = msg
            statsView?.text = "Boss " + kills + "  ·  Ölüm " + deaths + "  ·  Random " + randoms + "  ·  Buff " + buffs
        }
    }

    private fun refreshScreen() {
        val b = wm.maximumWindowMetrics.bounds
        screenW = b.width()
        screenH = b.height()
    }

    private fun prefs() = getSharedPreferences("goldingen", Context.MODE_PRIVATE)

    private fun cfgF(key: String, def: Float): Float =
        prefs().getString("cfg_" + key, null)?.toFloatOrNull() ?: def

    private fun cfgI(key: String, def: Int): Int = cfgF(key, def.toFloat()).toInt()

    private fun cfgB(key: String, def: Boolean): Boolean = prefs().getBoolean("cfg_" + key, def)

    private fun hasPt(name: String): Boolean = prefs().contains(name + "_x") || DEFAULTS.containsKey(name)

    private fun pt(name: String): Pair<Float, Float> {
        val d = DEFAULTS[name] ?: Pair(0.5f, 0.5f)
        val p = prefs()
        return Pair(p.getFloat(name + "_x", d.first), p.getFloat(name + "_y", d.second))
    }

    private fun savePt(name: String, x: Float, y: Float) {
        prefs().edit().putFloat(name + "_x", x).putFloat(name + "_y", y).apply()
    }

    private fun pause(ms: Int) {
        var left = ms
        while (left > 0) {
            if (!running) throw StopEx()
            val s = if (left < 100) left else 100
            Thread.sleep(s.toLong())
            left -= s
        }
        if (!running) throw StopEx()
    }

    private fun fail(msg: String): Nothing {
        say(msg)
        throw StopEx()
    }

    private fun dist(a: PointF, b: PointF): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
    }

    // ------------------------------------------------------------------ ekran okuma

    private fun grab(): Bitmap? {
        val gap = 1100L - (System.currentTimeMillis() - lastShot)
        if (gap > 0) Thread.sleep(gap)
        lastShot = System.currentTimeMillis()
        val latch = CountDownLatch(1)
        var result: Bitmap? = null
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    try {
                        val hb = screenshot.hardwareBuffer
                        val hw = Bitmap.wrapHardwareBuffer(hb, screenshot.colorSpace)
                        if (hw != null) {
                            result = hw.copy(Bitmap.Config.ARGB_8888, false)
                            hw.recycle()
                        }
                        hb.close()
                    } catch (e: Exception) {
                    }
                    latch.countDown()
                }

                override fun onFailure(errorCode: Int) {
                    say("Ekran görüntüsü alınamadı (kod " + errorCode + ")")
                    latch.countDown()
                }
            })
        } catch (e: Exception) {
            say("Ekran görüntüsü hatası: " + e.message)
            return null
        }
        latch.await(3, TimeUnit.SECONDS)
        return result
    }

    /** Ekran goruntusu al ve olum ekranindaysa canlan. */
    private fun look(): Bitmap? {
        val b = grab() ?: return null
        if (isDeadScreen(b)) {
            // tek bir kareye güvenme: biraz bekleyip tekrar bak
            pause(1200)
            val b2 = grab()
            if (b2 != null && isDeadScreen(b2)) {
                deaths++
                say("Öldün, Free Revival'a basılıyor")
                tapPoint("revive")
                pause(4000)
                throw Restart()
            }
            return b2 ?: b
        }
        return b
    }

    private fun isGold(c: Int): Boolean {
        val r = Color.red(c)
        val g = Color.green(c)
        val b = Color.blue(c)
        return r >= 150 && g >= 110 && b <= 120 && r - b >= 70 && r >= g && g > b
    }

    private fun isGreen(c: Int): Boolean {
        val r = Color.red(c)
        val g = Color.green(c)
        val b = Color.blue(c)
        return r < 120 && g > 170 && b < 120 && g - r > 80
    }

    private fun isMiniGold(c: Int): Boolean {
        val r = Color.red(c)
        val g = Color.green(c)
        val b = Color.blue(c)
        return r > 150 && g > 120 && b < 80 && r - b > 90
    }

    private fun isOrange(c: Int): Boolean {
        val r = Color.red(c)
        val g = Color.green(c)
        val b = Color.blue(c)
        return r > 200 && g in 110..210 && b < 90
    }

    private fun orangeAt(b: Bitmap, name: String): Boolean {
        val p = pt(name)
        val cx = p.first * b.width
        val cy = p.second * b.height
        val hw = 0.012f * b.width
        val hh = 0.020f * b.height
        val x0 = maxOf(0, (cx - hw).toInt())
        val x1 = minOf(b.width - 1, (cx + hw).toInt())
        val y0 = maxOf(0, (cy - hh).toInt())
        val y1 = minOf(b.height - 1, (cy + hh).toInt())
        var hit = 0
        var tot = 0
        for (y in y0..y1) {
            for (x in x0..x1) {
                tot++
                if (isOrange(b.getPixel(x, y))) hit++
            }
        }
        return tot > 0 && hit.toFloat() / tot >= 0.12f
    }

    /** Acik haritada canli (parlak) sari kafataslarini ve yesil oyuncu imlecini bulur. */
    private fun scanMap(b: Bitmap): Scan {
        val bw = b.width
        val bh = b.height
        val s = bw / 2560f
        val x0 = (MAP_X0 * bw).toInt()
        val x1 = (MAP_X1 * bw).toInt()
        val y0 = (MAP_Y0 * bh).toInt()
        val y1 = (MAP_Y1 * bh).toInt()
        val w = x1 - x0
        val h = y1 - y0
        val cell = maxOf(2, Math.round(3 * s))
        val cw = w / cell
        val ch = h / cell
        val cnt = IntArray(cw * ch)
        var gx = 0L
        var gy = 0L
        var gn = 0
        val row = IntArray(w)
        for (y in 0 until h) {
            b.getPixels(row, 0, w, x0, y0 + y, w, 1)
            val cy = y / cell
            for (x in 0 until w) {
                val c = row[x]
                if (isGold(c)) {
                    val cx = x / cell
                    if (cx < cw && cy < ch) cnt[cy * cw + cx]++
                } else if (isGreen(c)) {
                    gx += x
                    gy += y
                    gn++
                }
            }
        }
        val need = maxOf(2, cell * cell / 4)
        val hit = BooleanArray(cw * ch)
        for (i in hit.indices) hit[i] = cnt[i] >= need

        val seen = BooleanArray(cw * ch)
        val stack = IntArray(cw * ch)
        val skulls = ArrayList<PointF>()
        for (start in hit.indices) {
            if (!hit[start] || seen[start]) continue
            var sp = 0
            stack[sp++] = start
            seen[start] = true
            var minX = cw
            var maxX = -1
            var minY = ch
            var maxY = -1
            var n = 0
            while (sp > 0) {
                val cur = stack[--sp]
                val cx = cur % cw
                val cy = cur / cw
                n++
                if (cx < minX) minX = cx
                if (cx > maxX) maxX = cx
                if (cy < minY) minY = cy
                if (cy > maxY) maxY = cy
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        val nx = cx + dx
                        val ny = cy + dy
                        if (nx < 0 || ny < 0 || nx >= cw || ny >= ch) continue
                        val ni = ny * cw + nx
                        if (hit[ni] && !seen[ni]) {
                            seen[ni] = true
                            stack[sp++] = ni
                        }
                    }
                }
            }
            val pw = (maxX - minX + 1) * cell
            val ph = (maxY - minY + 1) * cell
            val area = n * cell * cell
            if (pw >= 24 * s && pw <= 46 * s && ph >= 27 * s && ph <= 48 * s && area >= 450 * s * s) {
                skulls.add(
                    PointF(
                        x0 + (minX + maxX + 1) * cell / 2f,
                        y0 + (minY + maxY + 1) * cell / 2f
                    )
                )
            }
        }
        var player: PointF? = null
        if (gn >= 15 * s * s) player = PointF(x0 + gx.toFloat() / gn, y0 + gy.toFloat() / gn)
        return Scan(skulls, player)
    }

    /** Mini haritanin ortasinda (oyuncunun yaninda) parlak sari kafatasi var mi? Oran dondurur. */
    private fun miniFrac(b: Bitmap): Float {
        val a = pt("miniTL")
        val c = pt("miniBR")
        val cxm = (a.first + c.first) / 2f
        val cym = (a.second + c.second) / 2f
        val hw = (c.first - a.first) / 2f * 0.6f
        val hh = (c.second - a.second) / 2f * 0.6f
        val x0 = maxOf(0, ((cxm - hw) * b.width).toInt())
        val x1 = minOf(b.width - 1, ((cxm + hw) * b.width).toInt())
        val y0 = maxOf(0, ((cym - hh) * b.height).toInt())
        val y1 = minOf(b.height - 1, ((cym + hh) * b.height).toInt())
        if (x1 <= x0 || y1 <= y0) return 0f
        val w = x1 - x0 + 1
        val row = IntArray(w)
        var hit = 0
        for (y in y0..y1) {
            b.getPixels(row, 0, w, x0, y, w, 1)
            for (x in 0 until w) if (isMiniGold(row[x])) hit++
        }
        return hit.toFloat() / (w * (y1 - y0 + 1))
    }

    private fun miniAlive(b: Bitmap): Boolean = miniFrac(b) >= 0.0012f

    // ------------------------------------------------------------------ ATK / DEF okuma

    private fun readStats(b: Bitmap): Pair<Int, Int>? {
        return try {
            val w = (0.27f * b.width).toInt()
            val h = (0.07f * b.height).toInt()
            val crop = Bitmap.createBitmap(b, 0, 0, w, h)
            val big = Bitmap.createScaledBitmap(crop, w * 3, h * 3, true)
            val res = Tasks.await(recognizer.process(InputImage.fromBitmap(big, 0)), 10, TimeUnit.SECONDS)
            lastOcr = res.text.replace("\n", " ")
            val s = res.text.replace(Regex("[\\s,.]"), "")
            val nums = Regex("\\d{3,6}").findAll(s).map { it.value.toInt() }.toList()
            if (nums.size >= 2) Pair(nums[0], nums[1]) else null
        } catch (e: Exception) {
            lastOcr = "hata: " + e.message
            null
        }
    }

    // ------------------------------------------------------------------ olum ekrani

    /** Free Revival duğmesi bölgesini 9x5 ızgaraya bölüp her hücrenin ortalama rengini (r,g,b) çıkarır. */
    private fun reviveGrid(b: Bitmap): IntArray {
        val cols = 9
        val rows = 5
        val p = pt("revive")
        val out = IntArray(cols * rows * 3)
        val hw = 0.06f * b.width
        val hh = 0.03f * b.height
        val px = p.first * b.width
        val py = p.second * b.height
        var k = 0
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val cx = (px - hw + (c + 0.5f) * (2f * hw / cols)).toInt()
                val cy = (py - hh + (r + 0.5f) * (2f * hh / rows)).toInt()
                var sr = 0
                var sg = 0
                var sb = 0
                var n = 0
                for (y in maxOf(0, cy - 2)..minOf(b.height - 1, cy + 2)) {
                    for (x in maxOf(0, cx - 2)..minOf(b.width - 1, cx + 2)) {
                        val col = b.getPixel(x, y)
                        sr += Color.red(col)
                        sg += Color.green(col)
                        sb += Color.blue(col)
                        n++
                    }
                }
                if (n == 0) n = 1
                out[k++] = sr / n
                out[k++] = sg / n
                out[k++] = sb / n
            }
        }
        return out
    }

    /** Kayıtlı ölüm ekranı referansına ortalama renk farkı (0 = aynı). Referans yoksa null. */
    private fun reviveDiff(b: Bitmap): Float? {
        val s = prefs().getString("reviveG", null) ?: return null
        val ref = s.split(",").mapNotNull { it.toIntOrNull() }
        val cur = reviveGrid(b)
        if (ref.size != cur.size) return null
        var sum = 0L
        for (i in cur.indices) sum += Math.abs(cur[i] - ref[i])
        return sum.toFloat() / cur.size
    }

    private fun isDeadScreen(b: Bitmap): Boolean {
        val d = reviveDiff(b) ?: return false
        return d < 14f
    }

    // ------------------------------------------------------------------ dokunma

    private fun tapPx(x: Float, y: Float): Boolean {
        if (shizukuReady() && shell(arrayOf("input", "tap", x.toInt().toString(), y.toInt().toString()))) {
            sent++
            return true
        }
        val p = Path()
        p.moveTo(x, y)
        return runGesture(p, 70L)
    }

    private fun shizukuReady(): Boolean {
        return try {
            Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Throwable) {
            false
        }
    }

    private fun shizukuAsk() {
        try {
            if (!Shizuku.pingBinder()) {
                say("Shizuku çalışmıyor. Shizuku uygulamasını aç ve 'Başlat'a bas.")
                return
            }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                say("Shizuku izni tamam, dokunmalar Shizuku ile yapılacak")
                return
            }
            Shizuku.requestPermission(1001)
            say("Shizuku izin penceresinde 'İzin ver'e bas")
        } catch (e: Throwable) {
            say("Shizuku hatası: " + e.message)
        }
    }

    private fun shell(args: Array<String>): Boolean {
        return try {
            val m = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            m.isAccessible = true
            val proc = m.invoke(null, args, null, null) as Process
            proc.waitFor() == 0
        } catch (e: Throwable) {
            false
        }
    }

    private fun runGesture(p: Path, dur: Long): Boolean {
        for (i in 0 until 3) {
            val g = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(p, 0, dur))
                .build()
            val latch = CountDownLatch(1)
            var ok = false
            val accepted = dispatchGesture(g, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    ok = true
                    latch.countDown()
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    latch.countDown()
                }
            }, null)
            if (accepted) latch.await(2, TimeUnit.SECONDS)
            if (ok) {
                sent++
                return true
            }
            cancelled++
            Thread.sleep(300)
        }
        return false
    }

    private fun tapPoint(name: String) {
        refreshScreen()
        val p = pt(name)
        tapPx(p.first * screenW, p.second * screenH)
    }

    // ------------------------------------------------------------------ bot adimlari

    /** Haritayi acar ve harita acikken alinmis ekran goruntusunu dondurur. */
    private fun ensureMap(): Bitmap {
        for (i in 0 until 4) {
            val b = look()
            if (b != null && orangeAt(b, "closeMap")) return b
            tapPoint("minimap")
            pause(1500)
        }
        fail("Harita açılamadı. 'Ayar' menüsünden minimap ve closeMap noktalarını kontrol et.")
    }

    private fun closeMapIfOpen() {
        val b = look()
        if (b != null && orangeAt(b, "closeMap")) {
            tapPoint("closeMap")
            pause(700)
        }
    }

    private fun runLoop() {
        while (running) {
            try {
                cycle()
            } catch (e: Restart) {
                say("Canlandın, döngü yeniden başlıyor")
            }
        }
    }

    private fun cycle() {
        if (cfgB("buffOn", true)) checkBuff()

        val b = ensureMap()
        val sc = scanMap(b)
        val me = sc.player
        if (me == null) {
            say("Oyuncu imleci bulunamadı (harita açık mı?)")
            pause(1500)
            return
        }
        val radius = cfgI("radius", 150).toFloat()
        var best: PointF? = null
        var bd = Float.MAX_VALUE
        for (s in sc.skulls) {
            val d = dist(me, s)
            if (d < bd) {
                bd = d
                best = s
            }
        }
        if (best == null || bd > radius) {
            randoms++
            say(
                if (best == null) "Canlı boss görünmüyor, Random"
                else "En yakın boss " + bd.toInt() + " px uzakta (yarıçap " + radius.toInt() + "), Random"
            )
            tapPoint("random")
            pause((cfgF("randomWait", 2.5f) * 1000).toInt())
            return
        }
        goAndFight(best)
    }

    private fun goAndFight(target: PointF) {
        say("Boss'a gidiliyor (" + (target.x).toInt() + "," + (target.y).toInt() + ")")
        tapPx(target.x, target.y)
        pause(800)
        val arrive = cfgI("arrive", 45).toFloat()
        val limit = cfgI("walkTimeout", 60) * 1000L
        var t0 = System.currentTimeMillis()
        var retapped = false
        var lastD = Float.MAX_VALUE
        while (true) {
            pause(300)
            val b = look() ?: continue
            if (!orangeAt(b, "closeMap")) {
                tapPoint("minimap")
                pause(1200)
                continue
            }
            val sc = scanMap(b)
            val me = sc.player
            var tgt: PointF? = null
            var td = 30f
            for (s in sc.skulls) {
                val d = dist(s, target)
                if (d < td) {
                    td = d
                    tgt = s
                }
            }
            if (me != null) {
                if (tgt != null) {
                    lastD = dist(me, tgt)
                    if (lastD <= arrive) break
                } else {
                    if (lastD <= arrive * 2.5f) break
                    say("Hedef kayboldu, yeni tarama")
                    return
                }
            }
            if (System.currentTimeMillis() - t0 > limit) {
                if (!retapped) {
                    retapped = true
                    tapPx(target.x, target.y)
                    t0 = System.currentTimeMillis()
                } else {
                    say("Boss'a varılamadı, yeni tarama")
                    return
                }
            }
        }
        say("Vardı, oto saldırı açılıyor")
        tapPoint("closeMap")
        pause(700)
        tapPoint("autoAtk")
        fight(target)
    }

    private fun fight(target: PointF) {
        say("Savaş başladı")
        val t0 = System.currentTimeMillis()
        val limit = cfgI("fightTimeout", 300) * 1000L
        var seen = false
        var dead = 0
        var lastMap = t0
        while (true) {
            pause(1500)
            val b = look() ?: continue
            if (orangeAt(b, "closeMap")) {
                tapPoint("closeMap")
                pause(600)
                continue
            }
            val now = System.currentTimeMillis()
            if (miniAlive(b)) {
                seen = true
                dead = 0
            } else if (seen) {
                dead++
                if (dead >= 3) break
            } else if (now - t0 > 8000 && now - lastMap > 10000) {
                // mini harita hic canli boss gostermedi: harita uzerinden kontrol et
                lastMap = now
                say("Mini harita boss göstermiyor, haritadan kontrol")
                val mb = ensureMap()
                val sc = scanMap(mb)
                var still = false
                for (s in sc.skulls) if (dist(s, target) < 35f) still = true
                val me = sc.player
                val onTop = me != null && dist(me, target) <= cfgI("arrive", 45) * 1.5f
                tapPoint("closeMap")
                pause(700)
                if (!still && !onTop) break
            }
            if (now - t0 > limit) {
                say("Savaş zaman aşımı, boss'tan vazgeçildi")
                break
            }
        }
        kills++
        say("Boss öldü")
        if (cfgB("stopAuto", false)) tapPoint("autoAtk")
        pause(1000)
    }

    private fun checkBuff() {
        closeMapIfOpen()
        val b = look() ?: return
        val st = readStats(b)
        if (st == null) {
            say("ATK/DEF okunamadı: " + lastOcr)
            return
        }
        val minA = cfgI("minAtk", 0)
        val minD = cfgI("minDef", 0)
        if (st.first < minA && st.second < minD) {
            say("ATK " + st.first + " DEF " + st.second + " düşük, buff noktasına gidiliyor")
            doBuff(minA, minD)
        } else {
            say("ATK " + st.first + " DEF " + st.second + " tamam")
        }
    }

    private fun doBuff(minA: Int, minD: Int) {
        tapPoint("chat")
        pause(1000)
        tapPoint("chatTab")
        pause(700)
        tapPoint("buffLink")
        pause(600)
        if (prefs().contains("closeChat_x")) {
            tapPoint("closeChat")
            pause(500)
        }
        pause(cfgI("buffTravel", 12) * 1000)
        val t0 = System.currentTimeMillis()
        val limit = cfgI("buffWait", 90) * 1000L
        while (System.currentTimeMillis() - t0 < limit) {
            val b = look()
            if (b != null) {
                val st = readStats(b)
                if (st != null && st.first >= minA && st.second >= minD) {
                    buffs++
                    say("Buff alındı: ATK " + st.first + " DEF " + st.second)
                    return
                }
            }
            pause(3000)
        }
        say("Buff süresi doldu, devam ediliyor")
    }

    private fun start() {
        if (running) return
        if (worker?.isAlive == true) {
            say("Önceki işlem bitiyor, biraz bekle")
            return
        }
        val need = ArrayList<String>()
        if (!prefs().contains("autoAtk_x")) need.add("autoAtk")
        if (cfgB("buffOn", true)) {
            for (n in listOf("chat", "chatTab", "buffLink")) if (!prefs().contains(n + "_x")) need.add(n)
        }
        if (need.isNotEmpty()) {
            say("Önce 'Ayar' menüsünden işaretle: " + need.joinToString(", "))
            return
        }
        running = true
        updateUi()
        val t = Thread {
            try {
                runLoop()
            } catch (e: StopEx) {
            } catch (e: InterruptedException) {
            } catch (e: Exception) {
                say("Hata: " + e.message)
            }
            running = false
            ui { updateUi() }
        }
        worker = t
        t.start()
    }

    private fun stop() {
        running = false
        worker?.interrupt()
        say("Durduruldu")
        updateUi()
    }

    private fun toggle() {
        if (running) stop() else start()
    }

    private fun updateUi() {
        val btn = startBtn
        if (btn != null) {
            btn.text = if (running) "■  Durdur" else "▶  Başlat"
            btn.background = rounded(if (running) BAD else GOLD, 12)
            btn.setTextColor(if (running) Color.WHITE else 0xFF1A1300.toInt())
        }
        dotView?.background = oval(if (running) OK else GOLD)
    }

    // ------------------------------------------------------------------ test

    private fun runTest() {
        say("Test: ekran okunuyor")
        Thread {
            try {
                val b = grab()
                if (b == null) {
                    say("Ekran görüntüsü alınamadı")
                    return@Thread
                }
                val open = orangeAt(b, "closeMap")
                val sc = scanMap(b)
                val mf = miniFrac(b)
                val st = readStats(b)
                val rd = reviveDiff(b)
                val dead = if (rd == null) "referans yok" else String.format("fark %.1f (eşik 14, %s)", rd, if (rd < 14f) "ÖLÜ SANIYOR" else "normal")
                val radius = cfgI("radius", 150).toFloat()
                var near = "-"
                val me = sc.player
                if (me != null && sc.skulls.isNotEmpty()) {
                    var bd = Float.MAX_VALUE
                    for (s in sc.skulls) bd = minOf(bd, dist(me, s))
                    near = bd.toInt().toString() + " px"
                }
                say(
                    "Harita açık: " + open + "  canlı boss: " + sc.skulls.size +
                        "  imleç: " + (me != null) + "  en yakın: " + near +
                        "\nMini harita oranı: " + String.format("%.4f", mf) + " (eşik 0.0012, " +
                        (if (miniAlive(b)) "CANLI" else "yok") + ")" +
                        "\nATK/DEF: " + (if (st != null) st.first.toString() + "/" + st.second else "okunamadı") +
                        "  OCR: '" + lastOcr + "'  ölüm ekranı: " + dead +
                        "\nyarıçap " + radius.toInt()
                )
                ui { showMarkers(sc, radius) }
            } catch (e: Exception) {
                say("Test hatası: " + e.message)
            }
        }.start()
    }

    // ------------------------------------------------------------------ panel ve menu

    private fun rounded(color: Int, r: Int): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(color)
        g.cornerRadius = dp(r).toFloat()
        return g
    }

    private fun oval(color: Int): GradientDrawable {
        val g = GradientDrawable()
        g.shape = GradientDrawable.OVAL
        g.setColor(color)
        return g
    }

    private fun mkBtn(text: String, bg: Int = CARD_BG, fg: Int = Color.WHITE, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = text
        b.textSize = 12f
        b.isAllCaps = false
        b.setTextColor(fg)
        b.setPadding(dp(10), dp(4), dp(10), dp(4))
        b.minHeight = 0
        b.minimumHeight = 0
        b.minWidth = 0
        b.minimumWidth = 0
        b.background = rounded(bg, 10)
        b.setOnClickListener { onClick() }
        return b
    }

    private fun addBtn(col: LinearLayout, btn: Button, h: Int = LinearLayout.LayoutParams.WRAP_CONTENT) {
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, h)
        lp.topMargin = dp(6)
        col.addView(btn, lp)
    }

    private fun overlayParams(w: Int): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            w,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
    }

    private fun buildPanel() {
        val outer = LinearLayout(this)
        outer.orientation = LinearLayout.VERTICAL
        outer.background = rounded(PANEL_BG, 16)
        outer.setPadding(dp(12), dp(10), dp(12), dp(12))

        // ust satir: durum noktasi, baslik, gizle
        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        val dot = View(this)
        dotView = dot
        head.addView(dot, LinearLayout.LayoutParams(dp(10), dp(10)))
        val title = TextView(this)
        title.text = "Goldingen"
        title.textSize = 15f
        title.setTextColor(Color.WHITE)
        title.typeface = android.graphics.Typeface.DEFAULT_BOLD
        title.setPadding(dp(8), 0, 0, 0)
        head.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val hide = TextView(this)
        hide.text = "▾"
        hide.textSize = 18f
        hide.setTextColor(TEXT_DIM)
        hide.setPadding(dp(10), 0, dp(4), 0)
        head.addView(hide)
        outer.addView(head)

        val inner = LinearLayout(this)
        inner.orientation = LinearLayout.VERTICAL
        body = inner

        val st = TextView(this)
        st.textSize = 12f
        st.setTextColor(Color.WHITE)
        st.maxLines = 7
        st.setPadding(0, dp(8), 0, dp(2))
        st.text = "Hazır"
        statusView = st
        inner.addView(st)

        val stats = TextView(this)
        stats.textSize = 11f
        stats.setTextColor(TEXT_DIM)
        stats.text = "Boss 0  ·  Ölüm 0  ·  Random 0  ·  Buff 0"
        statsView = stats
        inner.addView(stats)

        val sb = mkBtn("▶  Başlat") { toggle() }
        sb.textSize = 15f
        sb.typeface = android.graphics.Typeface.DEFAULT_BOLD
        startBtn = sb
        addBtn(inner, sb, dp(44))

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        val names = listOf("Shizuku", "Test", "Ayar")
        val acts = listOf({ shizukuAsk() }, { runTest() }, { toggleMenu() })
        for (i in names.indices) {
            val bt = mkBtn(names[i]) { acts[i]() }
            val lp = LinearLayout.LayoutParams(0, dp(36), 1f)
            if (i > 0) lp.leftMargin = dp(6)
            row.addView(bt, lp)
        }
        val rlp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        rlp.topMargin = dp(8)
        inner.addView(row, rlp)
        outer.addView(inner)

        hide.setOnClickListener {
            val open = inner.visibility == View.VISIBLE
            inner.visibility = if (open) View.GONE else View.VISIBLE
            hide.text = if (open) "▸" else "▾"
        }

        val lp = overlayParams(dp(220))
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = dp(6)
        lp.y = (screenH * 0.22f).toInt()
        wm.addView(outer, lp)
        panel = outer
        updateUi()
    }

    private fun toggleMenu() {
        val old = menu
        if (old != null) {
            try {
                wm.removeView(old)
            } catch (e: Exception) {
            }
            menu = null
            return
        }
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.background = rounded(PANEL_BG, 16)
        card.setPadding(dp(12), dp(10), dp(12), dp(12))

        val title = TextView(this)
        title.text = "Nokta işaretleme"
        title.textSize = 15f
        title.setTextColor(Color.WHITE)
        title.typeface = android.graphics.Typeface.DEFAULT_BOLD
        card.addView(title)
        val hint = TextView(this)
        hint.text = "Oyunda ilgili ekranı aç, aşağıdan bir satıra dokun, sonra oyun ekranında o noktaya dokun. Kırmızı satırlar henüz işaretlenmedi."
        hint.textSize = 11f
        hint.setTextColor(TEXT_DIM)
        hint.setPadding(0, dp(2), 0, dp(4))
        card.addView(hint)

        val list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL
        for (n in NAMES) {
            val set = prefs().contains(n + "_x") || DEFAULTS.containsKey(n)
            val btn = mkBtn((if (set) "✓  " else "✗  ") + n + "\n" + (LABELS[n] ?: n), if (set) CARD_BG else MISSING_BG) {
                beginCapture(n)
            }
            btn.textSize = 11f
            btn.gravity = Gravity.START or Gravity.CENTER_VERTICAL
            btn.setPadding(dp(12), dp(8), dp(12), dp(8))
            addBtn(list, btn)
        }
        val sv = android.widget.ScrollView(this)
        sv.addView(list)
        card.addView(sv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (screenH * 0.55f).toInt()))

        val bottom = LinearLayout(this)
        bottom.orientation = LinearLayout.HORIZONTAL
        val b1 = mkBtn("Ölüm referansını sil", MISSING_BG) {
            prefs().edit().remove("reviveG").remove("reviveC").apply()
            say("Ölüm referansı silindi, ölüm tespiti kapalı")
        }
        val b2 = mkBtn("Kapat", GOLD, 0xFF1A1300.toInt()) { toggleMenu() }
        val l1 = LinearLayout.LayoutParams(0, dp(38), 1f)
        val l2 = LinearLayout.LayoutParams(0, dp(38), 1f)
        l2.leftMargin = dp(6)
        bottom.addView(b1, l1)
        bottom.addView(b2, l2)
        val blp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        blp.topMargin = dp(8)
        card.addView(bottom, blp)

        val lp = overlayParams(dp(330))
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = dp(236)
        lp.y = dp(8)
        wm.addView(card, lp)
        menu = card
    }

    private fun removeExtra() {
        val v = extra
        if (v != null) {
            try {
                wm.removeView(v)
            } catch (e: Exception) {
            }
            extra = null
        }
    }

    /** Sonraki dokunusu kaydeden tam ekran saydam katman. Oyunda ilgili ekrani acik tutup kullan. */
    private fun beginCapture(name: String) {
        if (menu != null) toggleMenu()
        removeExtra()
        val fl = FrameLayout(this)
        fl.setBackgroundColor(0x33FFFFFF)
        val tv = TextView(this)
        tv.text = "'" + name + "' için dokun:  " + (LABELS[name] ?: name)
        tv.textSize = 18f
        tv.setTextColor(Color.BLACK)
        tv.setBackgroundColor(0xDDFFFF00.toInt())
        tv.setPadding(dp(10), dp(6), dp(10), dp(6))
        fl.addView(
            tv,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            )
        )
        fl.setOnTouchListener { _, ev ->
            if (ev.action == MotionEvent.ACTION_UP) {
                refreshScreen()
                savePt(name, ev.rawX / screenW, ev.rawY / screenH)
                removeExtra()
                Toast.makeText(this, "Kaydedildi: " + name, Toast.LENGTH_SHORT).show()
                if (name == "revive") {
                    // ölüm ekranının o noktadaki rengini referans olarak sakla
                    Thread {
                        try {
                            Thread.sleep(700)
                            val b = grab()
                            if (b != null) {
                                prefs().edit().putString("reviveG", reviveGrid(b).joinToString(",")).apply()
                                say("Ölüm ekranı referansı kaydedildi")
                            }
                        } catch (e: Exception) {
                        }
                    }.start()
                }
            }
            true
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        wm.addView(fl, lp)
        extra = fl
    }

    /** Bulunan boss'lari, oyuncuyu, yaricap cemberini ve kayitli noktalari 6 saniye gosterir. */
    private fun showMarkers(sc: Scan, radius: Float) {
        removeExtra()
        refreshScreen()
        val dots = ArrayList<Triple<String, Float, Float>>()
        for (n in NAMES) {
            val p = pt(n)
            dots.add(Triple(n, p.first * screenW, p.second * screenH))
        }
        val a = pt("miniTL")
        val c = pt("miniBR")
        val rect = floatArrayOf(a.first * screenW, a.second * screenH, c.first * screenW, c.second * screenH)
        val v = MarkView(this, sc.skulls, sc.player, radius, dots, rect)
        val wlp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        wm.addView(v, wlp)
        extra = v
        main.postDelayed({
            if (extra === v) removeExtra()
        }, 6000)
    }
}
