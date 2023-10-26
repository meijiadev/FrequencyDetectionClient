package com.example.frequencydetectionclient.view

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import com.example.frequencydetectionclient.R

class AttendanceRateView(context: Context, attrs: AttributeSet? = null) :
    View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rectF = RectF()
    private var percentage: Int = 0
    private val text = "测试"

    private val percentageTextColor: Int
    private val attendanceTextColor: Int
    private val circleBackgroundColor: Int
    private val circleProgressColor: Int

    init {
        val typedArray = context.obtainStyledAttributes(attrs, R.styleable.AttendanceRateView)
        percentageTextColor = typedArray.getColor(R.styleable.AttendanceRateView_percentageTextColor, Color.BLACK)
        attendanceTextColor = typedArray.getColor(R.styleable.AttendanceRateView_attendanceTextColor, Color.BLACK)
        circleBackgroundColor = typedArray.getColor(R.styleable.AttendanceRateView_circleBackgroundColor, resources.getColor(R.color.circle_view_background_color))
        circleProgressColor = typedArray.getColor(R.styleable.AttendanceRateView_circleProgressColor, resources.getColor(R.color.circle_view_progress_color))
        typedArray.recycle()

        paint.isAntiAlias = true
    }

    fun setPercentage(targetPercentage: Int) {
        this.percentage = targetPercentage
        invalidate()
    }

    fun animatePercentage(targetPercentage: Int, duration: Long = 1000) {
        val animator = ValueAnimator.ofInt(percentage, targetPercentage)
        animator.duration = duration
        animator.addUpdateListener { animation ->
            percentage = animation.animatedValue as Int
            invalidate()
        }
        animator.start()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val width = width
        val height = height
        val strokeWidth = 20
        val padding = 30


        // Draw the light blue background circle
        paint.color = circleBackgroundColor
        paint.strokeWidth = strokeWidth.toFloat()
        paint.style = Paint.Style.STROKE
        rectF.set(padding.toFloat(), padding.toFloat(), (width - padding).toFloat(), (height - padding).toFloat())
        canvas.drawOval(rectF, paint)

        // Draw the dark blue progress arc
        paint.color = circleProgressColor
        paint.strokeWidth = strokeWidth.toFloat() * 1.5f
        paint.strokeCap = Paint.Cap.ROUND // 设置笔尖样式为圆角
        val shadowColor = Color.argb(90, 49, 119, 248) // #503177F8
        paint.setShadowLayer(15f, 0f, 0f, shadowColor)
        canvas.drawArc(rectF, -90f, percentage * 3.6f, false, paint)
        paint.clearShadowLayer()

        // Draw the percentage text
//        paint.textSize = width / 6f
//        paint.color = percentageTextColor
//        paint.typeface = Typeface.DEFAULT
//        paint.style = Paint.Style.FILL
//        val percentageText = "正在采集中..."
//        val percentageTextWidth = paint.measureText(percentageText)
//        canvas.drawText(percentageText, (width - percentageTextWidth) / 2, height / 2f, paint)

        // Draw the "出勤率" text
//        paint.textSize = width / 13f
//        paint.color = attendanceTextColor
//        val textWidth = paint.measureText(text)
//        canvas.drawText(text, (width - textWidth) / 2, height / 2f + width / 7f, paint)
    }
}