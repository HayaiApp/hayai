package dev.ahmedmohamed.hayai.novel.reader

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.MotionEvent
import androidx.appcompat.widget.AppCompatSeekBar

internal class NovelVerticalProgressView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : AppCompatSeekBar(context, attrs) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(heightMeasureSpec, widthMeasureSpec)
        setMeasuredDimension(measuredHeight, measuredWidth)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.rotate(-90f)
        canvas.translate(-height.toFloat(), 0f)
        super.onDraw(canvas)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> parent?.requestDisallowInterceptTouchEvent(true)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> parent?.requestDisallowInterceptTouchEvent(false)
        }
        val transformed = MotionEvent.obtain(event)
        transformed.setLocation(height - event.y, event.x)
        val handled = super.onTouchEvent(transformed)
        transformed.recycle()
        return handled
    }
}
