// Точка входа ПК-порта «Толстого Тилкайо»: окно Swing + хост для android-совместимого GameView.
package com.tilcayo.pc

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Canvas
import android.view.MotionEvent
import com.tilcayo.fat.GameView
import com.tilcayo.fat.R
import java.awt.Color
import java.awt.Desktop
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GraphicsEnvironment
import java.awt.RenderingHints
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.image.BufferedImage
import java.io.File
import java.net.URI
import javax.imageio.ImageIO
import javax.swing.JFrame
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.WindowConstants

fun main(args: Array<String>) {
    if (args.isNotEmpty() && args.contains("--shot")) {
        System.setProperty("java.awt.headless", "true")
        runShotMode(args)
        return
    }
    SwingUtilities.invokeLater { runWindowMode() }
}

// =====================================================================
// Контекст Android для ПК
// =====================================================================

private class DesktopContext : Context() {
    override val packageName: String = "com.tilcayo.fat"
    override val packageManager: PackageManager = PackageManager()
    override val cacheDir: File =
        File(System.getProperty("java.io.tmpdir"), "tilcayo-pc-cache").apply { mkdirs() }

    override val resources: Resources = Resources(
        mapOf(
            R.drawable.tilcayo to "/tilcayo.png",
            R.drawable.boss to "/boss.png",
            R.raw.chunks to "/chunks.txt",
        ),
    )

    private val prefsDir: File = run {
        val appData = System.getenv("APPDATA")
        val base = if (!appData.isNullOrBlank()) File(appData, "tilcayo")
        else File(System.getProperty("user.home"), ".tilcayo")
        base.apply { mkdirs() }
    }

    private val prefs = HashMap<String, SharedPreferences>()

    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
        prefs.getOrPut(name) {
            val f = File(prefsDir, "$name.xml")
            if (!f.exists()) seedSave(f)
            FileSharedPreferences(f)
        }

    /** При первом запуске переносим сохранение игрока, снятое с телефона. */
    private fun seedSave(target: File) {
        runCatching {
            javaClass.getResourceAsStream("/save.xml")?.use { input ->
                target.outputStream().use { input.copyTo(it) }
            }
        }
    }

    override fun startActivity(intent: Intent) {
        val url = intent.data?.toString() ?: return
        runCatching { if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(URI(url)) }
    }
}

/** SharedPreferences поверх того же XML-формата, что использует Android (совместим с сохранением с телефона). */
private class FileSharedPreferences(private val file: File) : SharedPreferences {
    private val map = LinkedHashMap<String, Any>()
    private val lock = Any()

    init { load() }

    private fun load() {
        if (!file.exists()) return
        runCatching {
            val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = false
            val doc = factory.newDocumentBuilder().parse(file)
            val children = doc.documentElement.childNodes
            for (i in 0 until children.length) {
                val node = children.item(i)
                if (node.nodeType != org.w3c.dom.Node.ELEMENT_NODE) continue
                val el = node as org.w3c.dom.Element
                val key = el.getAttribute("name")
                when (el.tagName) {
                    "string" -> map[key] = el.textContent ?: ""
                    "int" -> map[key] = el.getAttribute("value").toIntOrNull() ?: 0
                    "long" -> map[key] = el.getAttribute("value").toLongOrNull() ?: 0L
                    "boolean" -> map[key] = el.getAttribute("value").toBoolean()
                    "float" -> map[key] = el.getAttribute("value").toFloatOrNull() ?: 0f
                }
            }
        }
    }

    override fun getLong(key: String, defValue: Long): Long = when (val v = map[key]) {
        is Long -> v
        is Int -> v.toLong()
        is String -> v.toLongOrNull() ?: defValue
        else -> defValue
    }

    override fun getInt(key: String, defValue: Int): Int = when (val v = map[key]) {
        is Int -> v
        is Long -> v.toInt()
        is String -> v.toIntOrNull() ?: defValue
        else -> defValue
    }

    override fun getBoolean(key: String, defValue: Boolean): Boolean = when (val v = map[key]) {
        is Boolean -> v
        is String -> v.toBoolean()
        else -> defValue
    }

    override fun getString(key: String, defValue: String?): String? = map[key]?.toString() ?: defValue
    override fun contains(key: String): Boolean = map.containsKey(key)
    override fun edit(): SharedPreferences.Editor = Editor()

    private inner class Editor : SharedPreferences.Editor {
        private val pending = LinkedHashMap<String, Any?>()

        override fun putLong(key: String, value: Long): SharedPreferences.Editor { pending[key] = value; return this }
        override fun putInt(key: String, value: Int): SharedPreferences.Editor { pending[key] = value; return this }
        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor { pending[key] = value; return this }
        override fun putString(key: String, value: String?): SharedPreferences.Editor { pending[key] = value; return this }
        override fun remove(key: String): SharedPreferences.Editor { pending[key] = null; return this }
        override fun clear(): SharedPreferences.Editor { synchronized(lock) { map.clear() }; return this }

        override fun apply() { commit() }

        override fun commit(): Boolean {
            synchronized(lock) {
                for ((k, v) in pending) {
                    if (v == null) map.remove(k) else map[k] = v
                }
                pending.clear()
            }
            save()
            return true
        }
    }

    private fun save() {
        runCatching {
            file.parentFile?.mkdirs()
            val sb = StringBuilder()
            sb.append("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n")
            synchronized(lock) {
                for ((k, v) in map) {
                    when (v) {
                        is Boolean -> sb.append("    <boolean name=\"").append(esc(k)).append("\" value=\"").append(v).append("\" />\n")
                        is Int -> sb.append("    <int name=\"").append(esc(k)).append("\" value=\"").append(v).append("\" />\n")
                        is Long -> sb.append("    <long name=\"").append(esc(k)).append("\" value=\"").append(v).append("\" />\n")
                        is Float -> sb.append("    <float name=\"").append(esc(k)).append("\" value=\"").append(v).append("\" />\n")
                        else -> sb.append("    <string name=\"").append(esc(k)).append("\">").append(esc(v.toString())).append("</string>\n")
                    }
                }
            }
            sb.append("</map>\n")
            file.writeText(sb.toString())
        }
    }

    private fun esc(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}

// =====================================================================
// Окно
// =====================================================================

/** Логическое полотно игры: она спроектирована под портретные 360x800 (мир шириной 360). */
private const val LOGICAL_W = 360f
private const val LOGICAL_H = 800f

/**
 * Адаптация к окну: игра (логическое полотно 360x800) равномерно увеличивается настолько,
 * насколько влезает без искажений, и центрируется. Поля по краям не чёрные — они заполняются
 * растянутыми крайними колонками/строками кадра, поэтому фон игры визуально продолжается.
 */
private class Viewport(val scale: Float, val ox: Float, val oy: Float) {
    fun toLogicalX(screenX: Float): Float = (screenX - ox) / scale
    fun toLogicalY(screenY: Float): Float = (screenY - oy) / scale

    companion object {
        fun fit(w: Int, h: Int): Viewport {
            if (w <= 0 || h <= 0) return Viewport(1f, 0f, 0f)
            val s = minOf(w / LOGICAL_W, h / LOGICAL_H)
            return Viewport(s, (w - LOGICAL_W * s) / 2f, (h - LOGICAL_H * s) / 2f)
        }
    }
}

/**
 * Рисует кадр игры в промежуточный буфер и раскладывает его на окно.
 * Поля по краям не оставляем чёрными и не растягиваем стену: фон игры — это вертикальный
 * градиент, одинаковый в пределах строки, поэтому поля заливаем цветом фона этой строки
 * (медиана пикселей строки). Так фон продолжается без искажений и без полос при прокрутке.
 */
private class FrameRenderer(private val view: GameView) {
    private var buffer: BufferedImage? = null
    private var rowColor = IntArray(0)

    fun render(out: Graphics2D, w: Int, h: Int, vp: Viewport) {
        if (w <= 0 || h <= 0) return
        val boxW = maxOf(1, Math.round(LOGICAL_W * vp.scale))
        val boxH = maxOf(1, Math.round(LOGICAL_H * vp.scale))
        val img = buffer?.takeIf { it.width == boxW && it.height == boxH }
            ?: BufferedImage(boxW, boxH, BufferedImage.TYPE_INT_ARGB).also { buffer = it }

        val gi = img.createGraphics()
        try {
            applyHints(gi)
            gi.color = Color.BLACK
            gi.fillRect(0, 0, boxW, boxH)
            gi.scale(vp.scale.toDouble(), vp.scale.toDouble())
            view.drawForHost(Canvas(gi))
        } finally {
            gi.dispose()
        }

        applyHints(out)
        val ox = Math.round(vp.ox)
        val oy = Math.round(vp.oy)
        if (ox <= 0 && oy <= 0) {
            out.drawImage(img, ox, oy, null)
            return
        }

        // Фон игры — вертикальный линейный градиент (от цвета вверху к цвету внизу).
        // Берём цвет верхней и нижней кромок кадра (самый частый ТОЧНЫЙ цвет полосы —
        // так шум от HUD/стен/объектов не влияет) и линейно интерполируем по строкам.
        if (rowColor.size < boxH) rowColor = IntArray(boxH)
        val sampleStep = maxOf(1, boxW / 48)
        val strip = maxOf(1, boxH / 25)
        val cTop = modeOverRows(img, boxW, 0, strip, sampleStep)
        val cBot = modeOverRows(img, boxW, boxH - strip, boxH, sampleStep)
        for (y in 0 until boxH) {
            val t = if (boxH > 1) y.toFloat() / (boxH - 1) else 0f
            rowColor[y] = lerpArgb(cTop, cBot, t)
        }

        out.color = Color.BLACK
        out.fillRect(0, 0, w, h)
        // Верх/низ (если окно ниже пропорций игры) — цвет крайних строк.
        if (oy > 0) {
            out.color = Color(rowColor[0], true)
            out.fillRect(0, 0, w, oy)
            out.color = Color(rowColor[boxH - 1], true)
            out.fillRect(0, oy + boxH, w, maxOf(0, h - (oy + boxH)))
        }
        // Боковые поля — построчная заливка фоном (фон продолжается влево/вправо).
        if (ox > 0) {
            var y = 0
            while (y < boxH) {
                out.color = Color(rowColor[y], true)
                out.fillRect(0, oy + y, ox, 1)
                out.fillRect(ox + boxW, oy + y, w - (ox + boxW), 1)
                y++
            }
        }
        out.drawImage(img, ox, oy, null)
    }

    /** Самый частый точный цвет в горизонтальной полосе строк [y0, y1). */
    private fun modeOverRows(img: BufferedImage, boxW: Int, y0: Int, y1: Int, step: Int): Int {
        val counts = HashMap<Int, Int>()
        var best = 0xFF000000.toInt()
        var bestCount = 0
        var y = y0
        while (y < y1) {
            var x = 0
            while (x < boxW) {
                val c = img.getRGB(x, y)
                val n = (counts[c] ?: 0) + 1
                counts[c] = n
                if (n > bestCount) {
                    bestCount = n
                    best = c
                }
                x += step
            }
            y++
        }
        return best
    }

    /** Линейная интерполяция двух непрозрачных цветов. */
    private fun lerpArgb(a: Int, b: Int, t: Float): Int {
        val tt = t.coerceIn(0f, 1f)
        val ar = (a shr 16) and 0xFF; val ag = (a shr 8) and 0xFF; val ab = a and 0xFF
        val br = (b shr 16) and 0xFF; val bg = (b shr 8) and 0xFF; val bb = b and 0xFF
        val r = (ar + (br - ar) * tt).toInt()
        val g = (ag + (bg - ag) * tt).toInt()
        val bl = (ab + (bb - ab) * tt).toInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
    }
}

private class GamePanel(private val view: GameView) : JPanel() {
    private val renderer = FrameRenderer(view)

    init {
        background = Color.BLACK
        isOpaque = true
        isFocusable = true
    }

    fun viewport(): Viewport = Viewport.fit(width, height)

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2 = g.create() as Graphics2D
        try {
            renderer.render(g2, width, height, viewport())
        } finally {
            g2.dispose()
        }
    }
}

/** Читает текущую сцену GameView через рефлексию, чтобы развести клавиши меню и забега. */
private class SceneProbe(private val view: GameView) {
    private val sceneField: java.lang.reflect.Field? =
        runCatching { GameView::class.java.getDeclaredField("scene").apply { isAccessible = true } }.getOrNull()

    fun sceneName(): String? = sceneField?.let {
        runCatching { (it.get(view) as? Enum<*>)?.name }.getOrNull()
    }

    fun isPlaying(): Boolean = sceneName() == "PLAY"
}


private fun applyHints(g2: Graphics2D) {
    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
    g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
    g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_SPEED)
    g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
}

private fun runWindowMode() {
    val ctx = DesktopContext()
    val view = GameView(ctx)
    val panel = GamePanel(view)
    val probe = SceneProbe(view)

    view.postHook = { r -> if (SwingUtilities.isEventDispatchThread()) r.run() else SwingUtilities.invokeLater(r) }
    view.invalidateHook = { panel.repaint() }

    // Игра всегда рисует в своём логическом полотне 360x800; окно лишь масштабирует его.
    view.width = LOGICAL_W.toInt()
    view.height = LOGICAL_H.toInt()
    view.onSizeChanged(LOGICAL_W.toInt(), LOGICAL_H.toInt(), 0, 0)

    // Последняя позиция мыши в логических координатах — по ней «кликает» Пробел/Enter в меню.
    var lastMouseX = LOGICAL_W / 2f
    var lastMouseY = LOGICAL_H / 2f
    fun trackMouse(e: MouseEvent) {
        val vp = panel.viewport()
        lastMouseX = vp.toLogicalX(e.x.toFloat())
        lastMouseY = vp.toLogicalY(e.y.toFloat())
    }

    panel.addMouseListener(object : MouseAdapter() {
        override fun mousePressed(e: MouseEvent) {
            panel.requestFocusInWindow()
            trackMouse(e)
            view.onTouchEvent(MotionEvent.obtain(MotionEvent.ACTION_DOWN, lastMouseX, lastMouseY, 0))
        }

        override fun mouseReleased(e: MouseEvent) {
            trackMouse(e)
            view.onTouchEvent(MotionEvent.obtain(MotionEvent.ACTION_UP, lastMouseX, lastMouseY, 0))
        }
    })
    panel.addMouseMotionListener(object : MouseMotionAdapter() {
        override fun mouseMoved(e: MouseEvent) {
            trackMouse(e)
        }

        override fun mouseDragged(e: MouseEvent) {
            trackMouse(e)
            view.onTouchEvent(MotionEvent.obtain(MotionEvent.ACTION_MOVE, lastMouseX, lastMouseY, 0))
        }
    })

    fun jumpKey() = tapAt(view, LOGICAL_W / 2f, LOGICAL_H * 0.25f)

    /** В меню Пробел/Enter «щёлкает» там, где сейчас мышь. */
    fun menuConfirm() = tapAt(view, lastMouseX, lastMouseY)

    val frame = JFrame("Толстый Тилкайо — ПК-порт")
    frame.defaultCloseOperation = WindowConstants.EXIT_ON_CLOSE
    frame.contentPane.add(panel)
    frame.minimumSize = Dimension(270, 600)
    frame.size = Dimension(450, 1000)
    frame.setLocationRelativeTo(null)
    frame.isVisible = true

    // F11 — истинный полноэкранный режим (без рамки, на всю область экрана).
    var fullscreen = false
    var windowedBounds: java.awt.Rectangle? = null
    fun toggleFullscreen() {
        if (!fullscreen) {
            windowedBounds = frame.bounds
            val bounds = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .defaultScreenDevice.defaultConfiguration.bounds
            frame.isVisible = false
            frame.dispose()
            frame.isUndecorated = true
            frame.isVisible = true
            frame.bounds = bounds
            fullscreen = true
        } else {
            frame.isVisible = false
            frame.dispose()
            frame.isUndecorated = false
            frame.isVisible = true
            windowedBounds?.let { frame.bounds = it }
            fullscreen = false
        }
        panel.requestFocusInWindow()
        panel.repaint()
    }

    // --- клавиатура ---
    // Забег: Пробел/W/↑ — прыжок, A/D/←/→ — рывок в сторону, Esc — пауза.
    // Меню: Пробел/Enter — нажать под мышью, Esc — назад. F11 — весь экран.
    panel.addKeyListener(object : KeyAdapter() {
        override fun keyPressed(e: KeyEvent) {
            val playing = probe.isPlaying()
            when (e.keyCode) {
                KeyEvent.VK_ESCAPE -> view.onBack()
                KeyEvent.VK_F11 -> toggleFullscreen()

                KeyEvent.VK_SPACE, KeyEvent.VK_ENTER ->
                    if (playing) jumpKey() else menuConfirm()

                KeyEvent.VK_UP, KeyEvent.VK_W ->
                    if (playing) jumpKey()

                KeyEvent.VK_LEFT, KeyEvent.VK_A ->
                    if (playing) dashAt(view, -1)

                KeyEvent.VK_RIGHT, KeyEvent.VK_D ->
                    if (playing) dashAt(view, 1)
            }
            panel.repaint()
        }
    })

    frame.addWindowListener(object : WindowAdapter() {
        override fun windowClosing(e: WindowEvent) = view.release()
    })

    panel.requestFocusInWindow()
    view.onHostResume()

    Timer(16) { panel.repaint() }.start()
}

private fun tapAt(view: GameView, x: Float, y: Float) {
    view.onTouchEvent(MotionEvent.obtain(MotionEvent.ACTION_DOWN, x, y, 0))
    view.onTouchEvent(MotionEvent.obtain(MotionEvent.ACTION_UP, x, y, 0))
}

/** Диагностика для --shot: печатает состояние героя (только для ручной проверки управления). */
private fun dumpSim(view: GameView, tag: String) {
    runCatching {
        val simField = GameView::class.java.getDeclaredField("sim").apply { isAccessible = true }
        val sim = simField.get(view)
        val cls = sim.javaClass
        fun f(name: String): Any? = cls.getDeclaredField(name).apply { isAccessible = true }.get(sim)
        println(
            "[$tag] px=${f("px")} py=${f("py")} pvx=${f("pvx")} pvy=${f("pvy")} " +
                "jumpsLeft=${f("jumpsLeft")} onWall=${f("onWall")} side=${f("side")}",
        )
    }
}

/**
 * Диагностика для --audio: рендерит музыкальный луп в WAV (для проверки синтеза).
 * Музыка сочиняется в фоновом потоке игры, поэтому берём её приватный compose() рефлексией.
 */
private fun dumpMusic(dir: File) {
    runCatching {
        dir.mkdirs()
        val music = com.tilcayo.fat.Music()
        val compose = music.javaClass.getDeclaredMethod("compose").apply { isAccessible = true }
        val samples = compose.invoke(music) as FloatArray
        val out = File(dir, "music.wav")
        out.writeBytes(writeWav(samples, 32000))
        println("[audio] ${out.absolutePath} samples=${samples.size} (${"%.2f".format(samples.size / 32000f)} s)")
    }.onFailure { println("[audio] fail: $it") }
}

/** 16-bit mono WAV из float-буфера с мягкой нормализацией. */
private fun writeWav(src: FloatArray, sampleRate: Int): ByteArray {
    var peak = 0.0001f
    for (v in src) peak = maxOf(peak, kotlin.math.abs(v))
    val k = if (peak > 0.99f) 0.99f / peak else 1f
    val bytes = java.io.ByteArrayOutputStream(44 + src.size * 2)
    fun le32(v: Int) { bytes.write(v and 255); bytes.write(v shr 8 and 255); bytes.write(v shr 16 and 255); bytes.write(v shr 24 and 255) }
    fun le16(v: Int) { bytes.write(v and 255); bytes.write(v shr 8 and 255) }
    bytes.write("RIFF".toByteArray()); le32(36 + src.size * 2)
    bytes.write("WAVEfmt ".toByteArray()); le32(16); le16(1); le16(1)
    le32(sampleRate); le32(sampleRate * 2); le16(2); le16(16)
    bytes.write("data".toByteArray()); le32(src.size * 2)
    for (v in src) {
        val s = (v * k * 32767f).toInt().coerceIn(-32768, 32767)
        le16(s)
    }
    return bytes.toByteArray()
}

/**
 * Рывок в сторону клавишей = тот же жест, что и свайп мышью: DOWN в центре,
 * MOVE за порог SWIPE_U (16 «мировых» единиц, порог в пикселях = 16 * scale), UP.
 */
private fun dashAt(view: GameView, dir: Int) {
    val scale = if (view.width > 0) view.width / 360f else 1f
    val cx = view.width / 2f
    val cy = view.height / 2f
    val tx = cx + dir * (16f * scale + 40f)
    view.onTouchEvent(MotionEvent.obtain(MotionEvent.ACTION_DOWN, cx, cy, 0))
    view.onTouchEvent(MotionEvent.obtain(MotionEvent.ACTION_MOVE, tx, cy, 0))
    view.onTouchEvent(MotionEvent.obtain(MotionEvent.ACTION_UP, tx, cy, 0))
}

// =====================================================================
// Автономные снимки кадров (--shot): рендер в PNG без окна — для проверки порта.
// =====================================================================

private fun runShotMode(args: Array<String>) {
    var w = 450
    var h = 1000
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--w" -> w = args.getOrNull(++i)?.toIntOrNull() ?: w
            "--h" -> h = args.getOrNull(++i)?.toIntOrNull() ?: h
        }
        i++
    }

    val ctx = DesktopContext()
    val view = GameView(ctx)
    val vp = Viewport.fit(w, h)
    // Игра рисует в логическом полотне 360x800, а снимок проходит через тот же рендер, что и окно.
    view.width = LOGICAL_W.toInt()
    view.height = LOGICAL_H.toInt()
    view.onSizeChanged(LOGICAL_W.toInt(), LOGICAL_H.toInt(), 0, 0)
    view.onHostResume()

    val image = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
    val renderer = FrameRenderer(view)

    fun frame() {
        val g2 = image.createGraphics()
        try {
            renderer.render(g2, w, h, vp)
        } finally {
            g2.dispose()
        }
    }

    i = 0
    while (i < args.size) {
        when (args[i]) {
            "--frames" -> {
                val n = args.getOrNull(++i)?.toIntOrNull() ?: 1
                repeat(n) { frame(); Thread.sleep(4) }
            }
            "--shot" -> {
                val path = args.getOrNull(++i) ?: "shot.png"
                frame()
                ImageIO.write(image, "png", File(path))
                println("saved $path")
            }
            "--tap" -> {
                // Координаты задаются в пикселях снимка (w x h) и переводятся в логические.
                val xy = args.getOrNull(++i)?.split(",") ?: emptyList()
                val x = vp.toLogicalX(xy.getOrNull(0)?.toFloatOrNull() ?: 0f)
                val y = vp.toLogicalY(xy.getOrNull(1)?.toFloatOrNull() ?: 0f)
                tapAt(view, x, y)
            }
            "--key" -> {
                when (args.getOrNull(++i)?.lowercase()) {
                    "esc" -> view.onBack()
                    "space" -> tapAt(view, LOGICAL_W / 2f, LOGICAL_H * 0.08f)
                }
            }
            "--jump" -> tapAt(view, LOGICAL_W / 2f, LOGICAL_H * 0.25f)
            "--dump" -> dumpSim(view, args.getOrNull(++i) ?: "state")
            "--audio" -> {
                val dir = File(args.getOrNull(++i) ?: ".")
                dumpMusic(dir)
            }
            "--dash" -> {
                val dir = when (args.getOrNull(++i)?.lowercase()) {
                    "right", "r" -> 1
                    else -> -1
                }
                dashAt(view, dir)
            }
        }
        i++
    }

    view.release()
}
