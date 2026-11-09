// Мини-реализация android.view (View / MotionEvent / HapticFeedbackConstants) для ПК-порта.
package android.view

import android.content.Context
import android.content.res.Resources
import android.graphics.Canvas

/**
 * База для игрового [com.tilcayo.fat.GameView]. Кадры рисуются вручную хостом (Swing),
 * поэтому никакого менеджера окон нет: хост сам вызывает [drawForHost] и кормит события.
 */
open class View(val context: Context) {
    var width: Int = 0
    var height: Int = 0

    val resources: Resources get() = context.resources

    /** Хуки, которые ставит десктопный хост (перерисовка кадра и постановка задач в UI-поток). */
    internal var invalidateHook: (() -> Unit)? = null
    internal var postHook: ((Runnable) -> Unit)? = null

    open fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {}
    protected open fun onDraw(canvas: Canvas) {}
    open fun onTouchEvent(event: MotionEvent): Boolean = false

    fun postInvalidateOnAnimation() { invalidateHook?.invoke() }
    fun postInvalidate() { invalidateHook?.invoke() }
    fun invalidate() { invalidateHook?.invoke() }

    /** Android-овский post: выполняет задачу в UI-потоке (у нас — поток диспетчеризации Swing). */
    fun post(action: Runnable): Boolean {
        val hook = postHook
        if (hook != null) hook(action) else action.run()
        return true
    }

    fun performHapticFeedback(feedbackConstant: Int): Boolean = false
    fun performHapticFeedback(feedbackConstant: Int, flags: Int): Boolean = false

    /** Вызывается хостом: рисует кадр (обращается к переопределённому onDraw). */
    fun drawForHost(canvas: Canvas) { onDraw(canvas) }
}

/**
 * Упрощённый MotionEvent: поддерживает один указатель (мышь/тач на ПК),
 * чего достаточно — игра работает с id/индексом указателя.
 */
class MotionEvent private constructor(
    val actionMasked: Int,
    private val ids: IntArray,
    private val xs: FloatArray,
    private val ys: FloatArray,
    val actionIndex: Int,
) {
    val pointerCount: Int get() = ids.size
    fun getPointerId(index: Int): Int = ids[index]
    fun getX(index: Int): Float = xs[index]
    fun getY(index: Int): Float = ys[index]
    fun findPointerIndex(id: Int): Int = ids.indexOf(id)

    companion object {
        const val ACTION_DOWN = 0
        const val ACTION_UP = 1
        const val ACTION_MOVE = 2
        const val ACTION_CANCEL = 3
        const val ACTION_OUTSIDE = 4
        const val ACTION_POINTER_DOWN = 5
        const val ACTION_POINTER_UP = 6

        @JvmStatic
        fun obtain(actionMasked: Int, x: Float, y: Float, pointerId: Int): MotionEvent =
            MotionEvent(actionMasked, intArrayOf(pointerId), floatArrayOf(x), floatArrayOf(y), 0)
    }
}

object HapticFeedbackConstants {
    const val LONG_PRESS = 0
    const val VIRTUAL_KEY = 1
    const val KEYBOARD_TAP = 3
    const val CLOCK_TICK = 4
    const val CONTEXT_CLICK = 6
    const val TEXT_HANDLE_MOVE = 9
    const val GESTURE_START = 12
    const val GESTURE_END = 13
    const val CONFIRM = 16
    const val REJECT = 17
}
