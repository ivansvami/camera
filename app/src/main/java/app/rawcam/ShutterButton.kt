package app.rawcam

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/**
 * Кнопка затвора в стиле iOS: белое кольцо и внутренний круг.
 * В режиме видео круг красный, а во время записи превращается в красный скруглённый квадрат.
 */
class ShutterButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val density = context.resources.displayMetrics.density
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = 3f * density
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

    // 0 = круг, 1 = квадрат (идёт запись)
    private var t = 0f

    var videoMode = false
        set(value) {
            field = value
            fillPaint.color = if (value) RED else Color.WHITE
            invalidate()
        }

    var recording = false
        set(value) {
            if (field == value) return
            field = value
            ValueAnimator.ofFloat(t, if (value) 1f else 0f).apply {
                duration = 180
                addUpdateListener {
                    t = it.animatedValue as Float
                    invalidate()
                }
            }.start()
        }

    init {
        isClickable = true
        isFocusable = true
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f
        val ringW = ringPaint.strokeWidth

        canvas.drawCircle(cx, cy, r - ringW / 2f, ringPaint)

        val innerMax = r - ringW - 5f * density
        val pressedScale = if (isPressed) 0.88f else 1f
        val half = lerp(innerMax, innerMax * 0.5f, t) * pressedScale
        val corner = lerp(half, 7f * density, t)
        canvas.drawRoundRect(cx - half, cy - half, cx + half, cy + half, corner, corner, fillPaint)
    }

    private fun lerp(a: Float, b: Float, f: Float) = a + (b - a) * f

    private companion object {
        val RED = Color.parseColor("#FF3B30")
    }
}
