// ПК-порт «Толстого Тилкайо»: мини-реализация android.graphics поверх стандартного Java2D.
// Игровой код (GameView.kt и др.) подключается как есть, без правок.
package android.graphics

import android.content.res.Resources
import java.awt.BasicStroke
import java.awt.Graphics2D
import java.awt.geom.AffineTransform
import java.awt.geom.Arc2D
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import java.awt.geom.Path2D
import java.awt.geom.Point2D
import java.awt.geom.Rectangle2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

// =====================================================================
// Геометрия
// =====================================================================

class Rect {
    var left = 0
    var top = 0
    var right = 0
    var bottom = 0
    constructor()
    constructor(l: Int, t: Int, r: Int, b: Int) { set(l, t, r, b) }
    fun set(l: Int, t: Int, r: Int, b: Int) { left = l; top = t; right = r; bottom = b }
}

class RectF {
    var left = 0f
    var top = 0f
    var right = 0f
    var bottom = 0f

    constructor()
    constructor(l: Float, t: Float, r: Float, b: Float) { set(l, t, r, b) }
    constructor(r: RectF) { set(r) }

    fun set(l: Float, t: Float, r: Float, b: Float) { left = l; top = t; right = r; bottom = b }
    fun set(r: RectF) { set(r.left, r.top, r.right, r.bottom) }
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    fun centerX(): Float = (left + right) / 2f
    fun centerY(): Float = (top + bottom) / 2f
    fun isEmpty(): Boolean = left >= right || top >= bottom
}

class Path {
    internal val path = Path2D.Float()
    fun reset() { path.reset() }
    fun moveTo(x: Float, y: Float) { path.moveTo(x.toDouble(), y.toDouble()) }
    fun lineTo(x: Float, y: Float) { path.lineTo(x.toDouble(), y.toDouble()) }
    fun close() { path.closePath() }
}

// =====================================================================
// Шейдеры (градиенты)
// =====================================================================

abstract class Shader {
    enum class TileMode { CLAMP, REPEAT, MIRROR }
    internal abstract fun awtPaint(): java.awt.Paint
}

class LinearGradient(
    x0: Float, y0: Float, x1: Float, y1: Float,
    color0: Int, color1: Int, tile: TileMode,
) : Shader() {
    private val paint: java.awt.Paint = run {
        val a = Point2D.Float(x0, y0)
        val b = Point2D.Float(x1, y1)
        if (a == b) b.y = y0 + 0.01f
        java.awt.LinearGradientPaint(
            a, b, floatArrayOf(0f, 1f),
            arrayOf(java.awt.Color(color0, true), java.awt.Color(color1, true)),
        )
    }
    override fun awtPaint(): java.awt.Paint = paint
}

class RadialGradient(
    cx: Float, cy: Float, radius: Float,
    colors: IntArray, stops: FloatArray, tile: TileMode,
) : Shader() {
    private val paint: java.awt.Paint = run {
        val safeR = if (radius <= 0.01f) 0.01f else radius
        java.awt.RadialGradientPaint(
            Point2D.Float(cx, cy), safeR, stops,
            Array(colors.size) { java.awt.Color(colors[it], true) },
        )
    }
    override fun awtPaint(): java.awt.Paint = paint
}

// =====================================================================
// Цветовые фильтры
// =====================================================================

open class ColorFilter

/** 4x5 матрица цвета (как в Android): смещения в масштабе 0..255. */
class ColorMatrix(val array: FloatArray) {
    constructor() : this(
        floatArrayOf(
            1f, 0f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f, 0f,
            0f, 0f, 1f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        ),
    )

    fun setSaturation(sat: Float) {
        val m = floatArrayOf(
            0.213f + 0.787f * sat, 0.715f - 0.715f * sat, 0.072f - 0.072f * sat, 0f, 0f,
            0.213f - 0.213f * sat, 0.715f + 0.285f * sat, 0.072f - 0.072f * sat, 0f, 0f,
            0.213f - 0.213f * sat, 0.715f - 0.715f * sat, 0.072f + 0.928f * sat, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        )
        System.arraycopy(m, 0, array, 0, 20)
    }

    /** this = post * this (порядок как в Android). */
    fun postConcat(post: ColorMatrix) {
        val a = post.array
        val b = array
        val r = FloatArray(20)
        for (i in 0 until 4) {
            for (j in 0 until 5) {
                var s = 0f
                for (k in 0 until 4) s += a[i * 5 + k] * b[k * 5 + j]
                if (j == 4) s += a[i * 5 + 4]
                r[i * 5 + j] = s
            }
        }
        System.arraycopy(r, 0, array, 0, 20)
    }
}

class ColorMatrixColorFilter(val matrix: ColorMatrix) : ColorFilter() {
    private val cache = HashMap<Bitmap, BufferedImage>()

    internal fun apply(src: Bitmap): BufferedImage {
        cache[src]?.let { return it }
        val img = src.image
        val w = img.width
        val h = img.height
        val m = matrix.array
        val px = IntArray(w * h)
        img.getRGB(0, 0, w, h, px, 0, w)
        val outPx = IntArray(w * h)
        for (i in px.indices) {
            val c = px[i]
            val a = (c ushr 24) and 0xFF
            val r = (c ushr 16) and 0xFF
            val g = (c ushr 8) and 0xFF
            val b = c and 0xFF
            val nr = m[0] * r + m[1] * g + m[2] * b + m[3] * a + m[4]
            val ng = m[5] * r + m[6] * g + m[7] * b + m[8] * a + m[9]
            val nb = m[10] * r + m[11] * g + m[12] * b + m[13] * a + m[14]
            val na = m[15] * r + m[16] * g + m[17] * b + m[18] * a + m[19]
            outPx[i] = (clamp(na) shl 24) or (clamp(nr) shl 16) or (clamp(ng) shl 8) or clamp(nb)
        }
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        out.setRGB(0, 0, w, h, outPx, 0, w)
        cache[src] = out
        return out
    }

    private fun clamp(v: Float): Int = when {
        v <= 0f -> 0
        v >= 255f -> 255
        else -> v.toInt()
    }
}

// =====================================================================
// Bitmap
// =====================================================================

class Bitmap(val image: BufferedImage) {
    val width: Int get() = image.width
    val height: Int get() = image.height
}

object BitmapFactory {
    @JvmStatic
    fun decodeResource(res: Resources, id: Int): Bitmap {
        res.openRawResource(id).use { stream ->
            val img = ImageIO.read(stream)
                ?: throw IllegalArgumentException("Не удалось декодировать ресурс $id")
            return Bitmap(img)
        }
    }
}

// =====================================================================
// Шрифт
// =====================================================================

class Typeface internal constructor(val font: java.awt.Font) {
    companion object {
        const val NORMAL = 0
        const val BOLD = 1
        const val ITALIC = 2
        const val BOLD_ITALIC = 3

        private val cache = HashMap<String, Typeface>()

        @JvmStatic
        fun create(family: String?, style: Int): Typeface {
            val fam = family ?: ""
            return cache.getOrPut("$fam/$style") {
                // ВАЖНО: проверять "sans" раньше "serif" — строка "sans-serif"
                // тоже содержит подстроку "serif", иначе всё уедет в Serif.
                val name = when {
                    fam.contains("mono", ignoreCase = true) -> java.awt.Font.MONOSPACED
                    fam.contains("sans", ignoreCase = true) -> java.awt.Font.SANS_SERIF
                    fam.contains("serif", ignoreCase = true) -> java.awt.Font.SERIF
                    else -> java.awt.Font.SANS_SERIF
                }
                var s = style
                if (fam.contains("black", ignoreCase = true)) s = s or BOLD
                val awtStyle = when {
                    (s and BOLD) != 0 && (s and ITALIC) != 0 -> java.awt.Font.BOLD or java.awt.Font.ITALIC
                    (s and BOLD) != 0 -> java.awt.Font.BOLD
                    (s and ITALIC) != 0 -> java.awt.Font.ITALIC
                    else -> java.awt.Font.PLAIN
                }
                Typeface(java.awt.Font(name, awtStyle, 12))
            }
        }

        @JvmField
        val DEFAULT = create("sans-serif", NORMAL)
    }
}

// =====================================================================
// Кисть
// =====================================================================

class Paint(flags: Int = 0) {
    enum class Style { FILL, STROKE, FILL_AND_STROKE }
    enum class Align { LEFT, CENTER, RIGHT }
    enum class Cap { BUTT, ROUND, SQUARE }
    enum class Join { MITER, ROUND, BEVEL }

    val flags: Int = flags

    var color: Int = 0xFF000000.toInt()
    var style: Style = Style.FILL
    var strokeWidth: Float = 0f
    var strokeCap: Cap = Cap.BUTT
    var strokeJoin: Join = Join.MITER
    var textSize: Float = 12f
    var textAlign: Align = Align.LEFT
    var shader: Shader? = null
    var colorFilter: ColorFilter? = null
    var typeface: Typeface? = null
    var isAntiAlias: Boolean = (flags and ANTI_ALIAS_FLAG) != 0
    var isFilterBitmap: Boolean = (flags and FILTER_BITMAP_FLAG) != 0

    /** В Android getAlpha/setAlpha меняют альфа-канал самого цвета. */
    var alpha: Int
        get() = (color ushr 24) and 0xFF
        set(value) { color = (color and 0x00FFFFFF) or ((value.coerceIn(0, 255)) shl 24) }

    internal fun resolveFont(): java.awt.Font =
        (typeface ?: Typeface.DEFAULT).font.deriveFont(if (textSize <= 0f) 1f else textSize)

    fun measureText(text: String): Float {
        val s = TextLocalize.apply(text)
        val runs = Shaper.runs(resolveFont(), s, EmojiFonts.forSize(textSize))
        var w = 0f
        for (r in runs) w += Shaper.width(r.first, r.second)
        return w
    }

    companion object {
        const val ANTI_ALIAS_FLAG = 0x01
        const val FILTER_BITMAP_FLAG = 0x02
        const val DITHER_FLAG = 0x04
    }
}

/**
 * Эмодзи-фолбэк: логические шрифты Java2D эмодзи не содержат, поэтому подбираем
 * подходящий установленный шрифт (Segoe UI Emoji и т.п.) для отсутствующих глифов.
 */
private object EmojiFonts {
    private val candidates = listOf(
        "Segoe UI Emoji", "Segoe UI Symbol", "Noto Color Emoji", "Noto Emoji",
        "Apple Color Emoji", "Symbola", "Android Emoji",
    )
    private val base: List<java.awt.Font> = candidates.mapNotNull { name ->
        val f = java.awt.Font(name, java.awt.Font.PLAIN, 12)
        if (f.family.equals(name, ignoreCase = true)) f else null
    }

    fun forSize(size: Float): List<java.awt.Font> =
        if (base.isEmpty()) emptyList() else base.map { it.deriveFont(if (size <= 0f) 1f else size) }

    /** Диагностика: какие фолбэк-шрифты реально найдены. */
    fun available(): List<String> = base.map { it.family }
}

/**
 * Перевод подсказок с «тач» на ПК-управление. Игровой исходник не правим —
 * подменяем строки на лету в слое рисования (и в замере, чтобы верстка совпадала).
 */
internal object TextLocalize {
    private val rules = listOf(
        "ТАП — ВЫСТРЕЛ!" to "ЛКМ / ПРОБЕЛ — ВЫСТРЕЛ!",
        "ТАП — прыжок на другую стену" to "ПРОБЕЛ или ЛКМ — прыжок на другую стену",
        "в воздухе ещё 2 рывка:" to "в воздухе ещё 2 рывка (A/D):",
        "ТАП — вперёд  ·  СВАЙП ← → — в сторону" to "ПРОБЕЛ/ЛКМ — вперёд  ·  A/D или ←/→ — в сторону",
        "ТАП — прыжок со стены" to "ПРОБЕЛ или ЛКМ — прыжок со стены",
        "СВАЙП ← → в воздухе" to "A/D или ←/→ в воздухе",
        "коснись его" to "щёлкни по нему",
        "тапни — выбрать, потяни — кинуть" to "ЛКМ — выбрать, перетащи — кинуть",
        "Патроны — для стрельбы" to "Патроны — для стрельбы (ЛКМ)",
    )

    fun apply(text: String): String {
        var s = text
        for ((from, to) in rules) {
            if (s.contains(from)) s = s.replace(from, to)
        }
        return s
    }
}

/** Раскладка строки на прогоны шрифтов: основной шрифт + эмодзи-фолбэки. */
private object Shaper {
    private val scratch: java.awt.Graphics2D =
        BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics()

    fun width(font: java.awt.Font, text: String): Float {
        scratch.font = font
        return scratch.fontMetrics.stringWidth(text).toFloat()
    }

    fun runs(
        font: java.awt.Font,
        text: String,
        fallbacks: List<java.awt.Font>,
    ): List<Pair<java.awt.Font, String>> {
        if (fallbacks.isEmpty() || text.isEmpty()) return listOf(font to text)
        val out = ArrayList<Pair<java.awt.Font, String>>()
        val sb = StringBuilder()
        var cur: java.awt.Font? = null
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val chosen = when {
                font.canDisplay(cp) -> font
                else -> fallbacks.firstOrNull { it.canDisplay(cp) } ?: font
            }
            if (cur == null) cur = chosen
            if (chosen !== cur) {
                out.add(cur!! to sb.toString())
                sb.setLength(0)
                cur = chosen
            }
            sb.appendCodePoint(cp)
            i += Character.charCount(cp)
        }
        if (sb.isNotEmpty()) out.add(cur!! to sb.toString())
        return out
    }
}

// =====================================================================
// Канва
// =====================================================================

class Canvas(val g: Graphics2D) {
    private val transformStack = ArrayDeque<AffineTransform>()

    fun save(): Int {
        transformStack.addLast(g.transform)
        return transformStack.size
    }

    fun restore() {
        if (transformStack.isNotEmpty()) g.transform = transformStack.removeLast()
    }

    fun translate(dx: Float, dy: Float) { g.translate(dx.toDouble(), dy.toDouble()) }
    fun scale(sx: Float, sy: Float) { g.scale(sx.toDouble(), sy.toDouble()) }
    fun rotate(degrees: Float) { g.rotate(Math.toRadians(degrees.toDouble())) }

    private fun applyColor(paint: Paint) {
        val sh = paint.shader
        g.paint = sh?.awtPaint() ?: java.awt.Color(paint.color, true)
    }

    private fun applyStroke(paint: Paint) {
        val w = if (paint.strokeWidth <= 0f) 1f else paint.strokeWidth
        val cap = when (paint.strokeCap) {
            Paint.Cap.ROUND -> BasicStroke.CAP_ROUND
            Paint.Cap.SQUARE -> BasicStroke.CAP_SQUARE
            else -> BasicStroke.CAP_BUTT
        }
        val join = when (paint.strokeJoin) {
            Paint.Join.ROUND -> BasicStroke.JOIN_ROUND
            Paint.Join.BEVEL -> BasicStroke.JOIN_BEVEL
            else -> BasicStroke.JOIN_MITER
        }
        g.stroke = BasicStroke(w, cap, join)
    }

    private inline fun shape(paint: Paint, build: () -> java.awt.Shape) {
        if (paint.style == Paint.Style.STROKE) {
            applyStroke(paint)
            applyColor(paint)
            g.draw(build())
        } else {
            applyColor(paint)
            g.fill(build())
        }
    }

    fun drawRect(left: Float, top: Float, right: Float, bottom: Float, paint: Paint) {
        shape(paint) {
            Rectangle2D.Float(min(left, right), min(top, bottom), abs(right - left), abs(bottom - top))
        }
    }

    fun drawRoundRect(left: Float, top: Float, right: Float, bottom: Float, rx: Float, ry: Float, paint: Paint) {
        shape(paint) {
            RoundRectangle2D.Float(
                min(left, right), min(top, bottom), abs(right - left), abs(bottom - top),
                rx * 2f, ry * 2f,
            )
        }
    }

    fun drawOval(rectF: RectF, paint: Paint) {
        shape(paint) { Ellipse2D.Float(rectF.left, rectF.top, rectF.width, rectF.height) }
    }

    fun drawCircle(cx: Float, cy: Float, radius: Float, paint: Paint) {
        shape(paint) { Ellipse2D.Float(cx - radius, cy - radius, radius * 2f, radius * 2f) }
    }

    fun drawLine(x0: Float, y0: Float, x1: Float, y1: Float, paint: Paint) {
        applyStroke(paint)
        applyColor(paint)
        g.draw(Line2D.Float(x0, y0, x1, y1))
    }

    fun drawArc(rectF: RectF, startAngle: Float, sweepAngle: Float, useCenter: Boolean, paint: Paint) {
        // Java2D считает углы против часовой стрелки, Android — по часовой: знак инвертируем.
        val type = when {
            useCenter -> Arc2D.PIE
            paint.style == Paint.Style.STROKE -> Arc2D.OPEN
            else -> Arc2D.CHORD
        }
        val arc = Arc2D.Float(
            rectF.left, rectF.top, rectF.width, rectF.height,
            -startAngle, -sweepAngle, type,
        )
        shape(paint) { arc }
    }

    fun drawPath(path: Path, paint: Paint) = shape(paint) { path.path }

    fun drawText(text: String, x: Float, y: Float, paint: Paint) {
        val base = paint.resolveFont()
        val localized = TextLocalize.apply(text)
        val runs = Shaper.runs(base, localized, EmojiFonts.forSize(paint.textSize))
        var total = 0f
        for (r in runs) total += Shaper.width(r.first, r.second)
        val dx = when (paint.textAlign) {
            Paint.Align.CENTER -> -total / 2f
            Paint.Align.RIGHT -> -total
            else -> 0f
        }
        g.paint = java.awt.Color(paint.color, true)
        var cx = x + dx
        for (r in runs) {
            g.font = r.first
            g.drawString(r.second, cx, y)
            cx += Shaper.width(r.first, r.second)
        }
    }

    fun drawBitmap(bitmap: Bitmap, src: Rect?, dst: RectF, paint: Paint) {
        val filter = paint.colorFilter
        val img = if (filter is ColorMatrixColorFilter) filter.apply(bitmap) else bitmap.image
        val a = paint.alpha
        val prev = g.composite
        if (a < 255) {
            g.composite = java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, (a / 255f).coerceIn(0f, 1f))
        }
        try {
            if (src == null) {
                // Дробное позиционирование и масштаб сохраняем через AffineTransform
                // (у Graphics.drawImage нет float-перегрузки).
                val at = AffineTransform()
                at.translate(dst.left.toDouble(), dst.top.toDouble())
                if (img.width > 0 && img.height > 0) {
                    at.scale(dst.width / img.width.toDouble(), dst.height / img.height.toDouble())
                }
                g.drawImage(img, at, null)
            } else {
                g.drawImage(
                    img,
                    dst.left.toInt(), dst.top.toInt(), dst.right.toInt(), dst.bottom.toInt(),
                    src.left, src.top, src.right, src.bottom,
                    null,
                )
            }
        } finally {
            g.composite = prev
        }
    }
}

private fun min(a: Float, b: Float) = if (a < b) a else b
private fun abs(v: Float) = if (v < 0f) -v else v
