package android.animation

/** Интерполяция двух ARGB-цветов — как android.animation.ArgbEvaluator. */
class ArgbEvaluator {
    fun evaluate(fraction: Float, startValue: Any?, endValue: Any?): Any {
        val start = startValue as Int
        val end = endValue as Int

        val startA = (start ushr 24) and 0xFF
        val startR = (start ushr 16) and 0xFF
        val startG = (start ushr 8) and 0xFF
        val startB = start and 0xFF
        val endA = (end ushr 24) and 0xFF
        val endR = (end ushr 16) and 0xFF
        val endG = (end ushr 8) and 0xFF
        val endB = end and 0xFF

        val a = startA + (fraction * (endA - startA)).toInt()
        val r = startR + (fraction * (endR - startR)).toInt()
        val g = startG + (fraction * (endG - startG)).toInt()
        val b = startB + (fraction * (endB - startB)).toInt()

        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }
}
