package com.example.frequencydetectionclient.view

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.SweepGradient
import android.os.Build
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ScaleGestureDetector.OnScaleGestureListener
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.example.frequencydetectionclient.R
import com.example.frequencydetectionclient.iq.RFControlInterface
import com.example.frequencydetectionclient.hackrf.HackrfSource
import com.example.frequencydetectionclient.iq.IQSourceInterface
import com.example.frequencydetectionclient.rtlsdr.RtlsdrSource
import com.orhanobut.logger.Logger
import org.w3c.dom.Attr

/**
 * Module:      AnalyzerSurface.java
 * Description: 这是一个扩展SurfaceView的自定义视图。它将显示频谱和瀑布图表
 */
class AnalyzerSurface @JvmOverloads constructor(context: Context?, attrs: AttributeSet? = null, defStyleAttr: Int = 0) :
    SurfaceView(context, attrs, defStyleAttr), SurfaceHolder.Callback, OnScaleGestureListener,
    GestureDetector.OnGestureListener {
    // 手势检测器检测缩放、滚动。。。
    private var scaleGestureDetector: ScaleGestureDetector? = null
    private var gestureDetector: GestureDetector? = null
    private var source: IQSourceInterface? = null                              // 引用IQ源来调优和检索属性
    private var rfControlInterface: RFControlInterface? =
        null                // 对RFControlInterface处理程序的引用
    private var defaultPaint: Paint                                // 绘制对象以在画布上绘制位图
    private var blackPaint: Paint                                 // 将对象绘制为黑色(擦除)
    private var fftPaint: Paint                                    // 绘制对象来绘制fft线
    private var peakHoldPaint: Paint                           // 绘制对象以绘制峰值保持点
    private var waterfallLinePaint: Paint                   // 绘制对象以绘制一个瀑布像素
    private var textPaint: Paint                              // 绘制对象以在画布上绘制文本
    private var textSmallPaint: Paint                          //绘制对象以在画布上绘制小文本
    private var channelSelectorPaint: Paint                  //绘制对象以绘制通道的区域
    private var channelWidthSelectorPaint: Paint           // 绘制对象以绘制通道的边界
    private var squelchPaint: Paint                       // 绘制对象以绘制静音选择器
    private var width = 0                                          // SurfaceView的当前宽度(以像素为单位)
    private var height = 0                                        //SurfaceView当前高度(以像素为单位)
    private var doAutoscaleInNextDraw = false                    //会导致draw()根据样品调整minDB和maxDB吗
    private var verticalZoomEnabled = true                      // 启用垂直缩放（dB比例）
    private var verticalScrollEnabled =
        true                   // Enables vertical scrolling (dB scale)
    private var decoupledAxis = true                          // 将分隔垂直和缩放敏感区域
    private lateinit var waterfallColorMap: IntArray          // 用于绘制瀑布图的颜色。

    // idx 0 -> weak signal   idx max -> strong signal
    private var waterfallLines: Array<Bitmap?>? = null            // 每个数组元素在瀑布图中保存一行
    private var waterfallLinesTopIndex =
        0                       // 指示waterfallLines中的哪个数组索引是最近的(圆形数组)
    private var waterfallColorMapType = COLORMAP_GQRX
    private var fftDrawingType = FFT_DRAWING_TYPE_LINE         // 指示应如何绘制fft
    private var averageLength = 0                             // 指示是否应绘制峰值保持点
    private var historySamples: Array<FloatArray>? = null    // 保存最后一个averageLength fft采样数据包的数组
    private var oldesthistoryIndex = 0                      // historySamples中保存最旧样本的索引
    private var peakHoldEnabled = false                    // 指示应启用还是禁用峰值保持
    private var peaks: FloatArray? = null                 // peak hold points 峰值保持点

    private var channelFrequency: Long = -1 // 解调器的中心频率

    // 虚拟频率和采样率指示fft的当前可见视口。当用户进行滚动和缩放时，它们与实际值不同
    @JvmField
    var virtualFrequency: Long = -1 // fft（基带）的中心频率如屏幕所示


    @JvmField
    var virtualSampleRate = -1 // 屏幕上显示的fft采样率

    /**
     * @return 垂直刻度上的最低dB值
     */
    var minDB = -50f // 刻度上的最低dB

    /**
     * @return 垂直刻度上的最高dB值
     */
    var maxDB = -5f // 刻度上的最高dB

    private var lastFrequency: Long = 0 // fft样本最后一个数据包的中心频率
    private var lastSampleRate = 0 // fft采样的最后一个数据包的采样率

    // true如果水平轴上的频率是相对于中心freq显示的；如果为绝对值，则为false
    var isDisplayRelativeFrequencies = false

    // 相对于中心频率（真）或绝对频率（假）
    private var recordingEnabled = false        // 指示录制当前是否正在运行
    private var demodulationEnabled = false    // 指示解调是启用还是禁用

    // 通道滤波器的当前通道宽度（截止频率-单侧）（Hz）
    @JvmField
    var channelWidth = -1                  // 解调器的信道滤波器的（一半）宽度
    private var squelch = Float.NaN       // 以dB为单位的噪声阈值
    private var squelchSatisfied = false // 指示当前信号是否足够强以跨过静噪阈值
    private var showLowerBand = true    // 指示频道选择器的下侧带是否可见
    private var showUpperBand = true   // 指示频道选择器的上侧带是否可见

    // 滚动类型存储用户对指针向下事件的意图：
    private var scrollType = 0
    private var fftRatio = 0.5f // fft在曲面上消耗的高度百分比
    private var fontSize = FONT_SIZE_MEDIUM // 指示网格标签的字体大小

    //      采集用的画笔参数
    private var strokeWhitePaint: Paint          // 描边的笔
    private var bgPaint: Paint
    private var sweepPaint: Paint               // 绘制圆形画笔
    private var sweepAngle = 8f                // 扫描的角度
    private var enableCollect = false          // 开启采集周围环境

    // private var matrix: Matrix? = null    // view 的矩阵参数，用于旋转圆形
    private var matrix: Matrix

    /**
     * @param showDebugInformation true 将在屏幕上启用调试输出
     */
    var isShowDebugInformation = false

    /**
     * Constructor.将初始化Paint实例并注册SurfaceHolder的回调函数
     * @param context
     */
    init {
        matrix = Matrix()
        defaultPaint = Paint()
        blackPaint = Paint()
        blackPaint.color = Color.BLACK
        fftPaint = Paint()
        fftPaint.color = Color.BLUE
        fftPaint.style = Paint.Style.FILL
        peakHoldPaint = Paint()
        peakHoldPaint.color = Color.YELLOW
        textPaint = Paint()
        textPaint.color = Color.WHITE
        textPaint.setAntiAlias(true)
        textSmallPaint = Paint()
        textSmallPaint.color = Color.WHITE
        textSmallPaint.setAntiAlias(true)
        waterfallLinePaint = Paint()
        channelSelectorPaint = Paint()
        channelSelectorPaint.color = Color.WHITE
        channelWidthSelectorPaint = Paint()
        channelWidthSelectorPaint.color = Color.WHITE
        squelchPaint = Paint()
        squelchPaint.color = Color.RED

        // 绘制采集动画的笔
        strokeWhitePaint = Paint()
        strokeWhitePaint.color = Color.WHITE
        strokeWhitePaint.isAntiAlias = true
        strokeWhitePaint.strokeWidth = 1f
        strokeWhitePaint.style = Paint.Style.STROKE
        bgPaint = Paint()
        bgPaint.color = context!!.getColor(R.color.bg_color)
        bgPaint.isAntiAlias = true
        bgPaint.style = Paint.Style.FILL
        sweepPaint = Paint()
        sweepPaint.isAntiAlias = true
        sweepPaint.style = Paint.Style.FILL_AND_STROKE
        val sweepGradient = SweepGradient(0f, 0f, intArrayOf(0X10000000, Color.RED), null)
        sweepPaint.setShader(sweepGradient) // 设置 shader

        // 添加回调以在SurfaceView的尺寸发生更改时获得通知：
        this.holder.addCallback(this)

        // 为瀑布图创建颜色图（稍后可以自定义）
        createWaterfallColorMap()

        // 实例化手势检测器：
        scaleGestureDetector = ScaleGestureDetector(context, this)
        gestureDetector = GestureDetector(context, this)
    }

    fun setRFListener(rfControlInterface: RFControlInterface?) {
        this.rfControlInterface = rfControlInterface

    }

    /**
     * 设置分析器视图的源属性。像max。采样率，…是从源实例派生的。它也将用于设置采样率和频率上的双抽头。
     *
     * @param source IQSource instance
     */
    fun setSource(source: IQSourceInterface?) {
        if (source == null) return
        this.source = source
        virtualFrequency = source.frequency
        virtualSampleRate = source.sampleRate
    }

    /**
     * 设置功率范围(刻度上的minDB和maxDB)。注意:我们必须确保这是一个原子操作，不会干扰处理/绘图线程。
     *
     * @param minDB new lowest dB value on the scale
     * @param maxDB new highest dB value on the scale
     */
    fun setDBScale(minDB: Float, maxDB: Float) {
        synchronized(this.holder) {
            this.minDB = minDB
            this.maxDB = maxDB
        }
    }

    /**
     * 是否开始采集
     */
    fun setCollectEnable(enable: Boolean) {
        enableCollect = enable
        //post(runnable)

    }

    /**
     *
     *
     * 会导致表面自动调整dB比例在下次调用draw()，使其适合传入的fft样本完美
     */
    fun autoscale() {
        doAutoscaleInNextDraw = true
    }

    /**
     * 将启用/禁用垂直滚动(dB刻度)
     *
     * @param enable true表示已启用滚动；false表示禁用
     */
    fun setVerticalScrollEnabled(enable: Boolean) {
        verticalScrollEnabled = enable
    }

    /**
     * 将启用禁用垂直缩放（dB刻度）
     *
     * @param enable true for zooming enabled; false for disabled
     */
    fun setVerticalZoomEnabled(enable: Boolean) {
        verticalZoomEnabled = enable
    }

    /**
     * 将分离轴缩放/滚动(垂直仅在左轴区域)和默认模式之间切换:垂直和水平缩放/滚动在同一时间
     *
     * @param decoupledAxis true: vertical and horizontal zoom/scroll are decoupled
     */
    fun setDecoupledAxis(decoupledAxis: Boolean) {
        this.decoupledAxis = decoupledAxis
    }

    /**
     * 将创建一个对应于给定类型的新颜色映射
     *
     * @param type COLORMAP_JET, _HOT, _OLD, _GQRX
     */
    fun setWaterfallColorMapType(type: Int) {
        if (waterfallColorMapType != type) {
            waterfallColorMapType = type
            createWaterfallColorMap()
        }
        //        this.waterfallColorMapType = 4;
//        this.createWaterfallColorMap();
        Logger.i("设置瀑布流的主题色类型：$type")
    }

    /**
     * @return The waterfall color map type: COLORMAP_JET, _HOT, _OLD, _GQRX
     */
    fun getWaterfallColorMapType(): Int {
        return waterfallColorMapType
    }

    /**
     * 将fft的绘图类型更改为给定类型
     *
     * @param fftDrawingType FFT_DRAWING_TYPE_BAR, FFT_DRAWING_TYPE_LINE
     */
    fun setFftDrawingType(fftDrawingType: Int) {
        this.fftDrawingType = fftDrawingType
    }

    /**
     * 将更改用于计算平均值的历史数据包的数量。
     *
     * @param length 历史数据包的数量；0表示无平均值
     */
    fun setAverageLength(length: Int) {
        averageLength = length
        Logger.i("averageLength:$length")
    }

    /**
     * @param enable true turns peak hold on; false turns it off
     */
    fun setPeakHoldEnabled(enable: Boolean) {
        peakHoldEnabled = enable
        Logger.i("peakHoldEnabled：$enable")
    }

    /**
     * @return current channel frequency as set in the UI
     */
    fun getChannelFrequency(): Long {
        return channelFrequency
    }

    /**
     * @return current squelch threshold in dB
     */
    fun getSquelch(): Float {
        return squelch
    }

    /**
     * @param squelch 新的以dB为单位的噪声阈值
     */
    fun setSquelch(squelch: Float) {
        this.squelch = squelch
        Logger.i("设置的噪音阈值：$squelch")
    }

    /**
     * @param channelFrequency new channel frequency in Hz
     */
    fun setChannelFrequency(channelFrequency: Long) {
        this.channelFrequency = channelFrequency
        Logger.i("channelFrequency:$channelFrequency")
    }

    /**
     * @param showLowerBand if true: draw the lower side band of the channel selector (if demodulation is enabled)
     */
    fun setShowLowerBand(showLowerBand: Boolean) {
        this.showLowerBand = showLowerBand
    }

    /**
     * @param showUpperBand if true: draw the upper side band of the channel selector (if demodulation is enabled)
     */
    fun setShowUpperBand(showUpperBand: Boolean) {
        this.showUpperBand = showUpperBand
    }

    /**
     * 设置字体大小
     *
     * @param fontSize FONT_SIZE_SMALL, *_MEDIUM or *_LARGE
     */
    fun setFontSize(fontSize: Int) {
        val normalTextSize: Int
        val smallTextSize: Int
        when (fontSize) {
            FONT_SIZE_SMALL -> {
                normalTextSize = (gridSize * 0.3).toInt()
                smallTextSize = (gridSize * 0.2).toInt()
            }

            FONT_SIZE_MEDIUM -> {
                normalTextSize = (gridSize * 0.476).toInt()
                smallTextSize = (gridSize * 0.25).toInt()
            }

            FONT_SIZE_LARGE -> {
                normalTextSize = (gridSize * 0.7).toInt()
                smallTextSize = (gridSize * 0.35).toInt()
            }

            else -> {
                Logger.e("setFontSize: Invalid font size: $fontSize")
                return
            }
        }
        this.fontSize = fontSize
        textPaint.textSize = normalTextSize.toFloat()
        textSmallPaint.textSize = smallTextSize.toFloat()
        Logger.i(
            "setFontSize: X-dpi=" + resources.displayMetrics.xdpi + " X-width=" +
                    resources.displayMetrics.widthPixels +
                    "  fontSize=" + fontSize + "  normalTextSize=" + normalTextSize + "  smallTextSize=" + smallTextSize
        )
    }

    /**
     * @return current font size: FONT_SIZE_SMALL, *_MEDIUM, *_LARGE
     */
    fun getFontSize(): Int {
        return fontSize
    }

    /**
     * @param enabled true: will prevent the analyzerSurface from re-tune the frequency or change the sample rate.
     */
    fun setRecordingEnabled(enabled: Boolean) {
        recordingEnabled = enabled
        // The source sample rate and frequency might have been changed due to starting recording. fix the view:
        if (enabled) {
            virtualFrequency = source!!.frequency
            virtualSampleRate = source!!.sampleRate
        }
    }

    /**
     * 如果使用true调用，则会将UI设置为解调模式 :
     * -不再更改采样率
     * -显示频道选择器
     * 这也将把信道频率、宽度和静噪的当前值传递给控制接口，以便与解调器同步。
     *
     * @param demodulationEnabled true:设置为解调模式;  false: 设置为常规模式
     */
    fun setDemodulationEnabled(demodulationEnabled: Boolean) {
        synchronized(this.holder) {
            if (demodulationEnabled) {
                // set viewport correctly:  虚拟采样率
                if (virtualSampleRate > source!!.sampleRate * 0.9) virtualSampleRate =
                    (source!!.sampleRate * 0.9).toInt()
                if (virtualFrequency - virtualSampleRate / 2 < source!!.frequency - source!!.sampleRate / 2
                    || virtualFrequency + virtualSampleRate / 2 > source!!.frequency + source!!.sampleRate / 2
                )
                    source!!.frequency = virtualFrequency

                // 如果通道频率、宽度和静噪超出范围，则初始化它们：
                if (channelFrequency < virtualFrequency - virtualSampleRate / 2 || channelFrequency > virtualFrequency + virtualSampleRate / 2) {
                    channelFrequency = virtualFrequency
                    rfControlInterface!!.updateChannelFrequency(channelFrequency)
                }
                if (!rfControlInterface!!.updateChannelWidth(channelWidth)) // 尝试设置通道宽度
                    channelWidth =
                        rfControlInterface!!.requestCurrentChannelWidth() // 宽度不受支持；从解调器继承
                if (java.lang.Float.isNaN(squelch) || squelch < minDB || squelch > maxDB) {
                    squelch = minDB + (maxDB - minDB) / 4
                }
                rfControlInterface?.updateSquelchSatisfied(squelchSatisfied) // just to make sure the scheduler is still in sync with the gui
            }
            this.demodulationEnabled = demodulationEnabled
        }
    }

    /**
     * 设置fft与瀑布的比率
     *
     * @param fftRatio 屏幕上fft的百分比（0->0%；1->100%）
     */
    fun setFftRatio(fftRatio: Float) {
        if (fftRatio != this.fftRatio) {
            this.fftRatio = fftRatio
            createWaterfallLineBitmaps() // recreate the waterfall bitmaps
            // Recreate the shaders:
            fftPaint.setShader(
                LinearGradient(
                    0f,
                    0f,
                    0f,
                    fftHeight.toFloat(),
                    Color.WHITE,
                    Color.BLUE,
                    Shader.TileMode.MIRROR
                )
            )
        }
    }

    /**
     * 将为瀑布图的给定宽度和高度初始化waterfallLines数组。如果数组不为空，它将首先被回收。
     */
    private fun createWaterfallLineBitmaps() {
        synchronized(this.holder) {

            // 如果位图不为空，则回收位图：
            waterfallLines?.let {
                for (b in it) b?.recycle()
            }

            // 创建新阵列：
            waterfallLinesTopIndex = 0
            waterfallLines = arrayOfNulls(waterfallHeight / pixelPerWaterfallLine)
            waterfallLines?.let {
                for (i in it.indices)
                    it[i] =
                        Bitmap.createBitmap(width, pixelPerWaterfallLine, Bitmap.Config.ARGB_8888)
            }

        }
    }

    /**
     * 用颜色实例填充waterfallColorMap数组
     */
    private fun createWaterfallColorMap() {
        synchronized(this.holder) {
            when (waterfallColorMapType) {
                COLORMAP_JET -> {
                    waterfallColorMap = IntArray(256 * 4)
                    run {
                        var i = 0
                        while (i < 256) {
                            waterfallColorMap[i] = Color.argb(0xff, 0, i, 255) // blue-light blue
                            i++
                        }
                    }
                    run {
                        var i = 0
                        while (i < 256) {
                            waterfallColorMap[256 + i] =
                                Color.argb(0xff, 0, 255, 255 - i) // light blue - green
                            i++
                        }
                    }
                    run {
                        var i = 0
                        while (i < 256) {
                            waterfallColorMap[512 + i] =
                                Color.argb(0xff, i, 255, 0) // green-yellow
                            i++
                        }
                    }
                    var i = 0
                    while (i < 256) {
                        waterfallColorMap[768 + i] =
                            Color.argb(0xff, 255, 255 - i, 0) // yellow-rea
                        i++
                    }
                }

                COLORMAP_HOT -> {
                    waterfallColorMap = IntArray(256 * 3)
                    run {
                        var i = 0
                        while (i < 256) {
                            waterfallColorMap[i] = Color.argb(0xff, i, 0, 0) // black-red
                            i++
                        }
                    }
                    run {
                        var i = 0
                        while (i < 256) {
                            waterfallColorMap[256 + i] =
                                Color.argb(0xff, 255, i, 0) // read -yellow
                            i++
                        }
                    }
                    var i = 0
                    while (i < 256) {
                        waterfallColorMap[512 + i] =
                            Color.argb(0xff, 255, 255, i) // yellow - white
                        i++
                    }
                }

                COLORMAP_OLD -> {
                    waterfallColorMap = IntArray(512)
                    var i = 0
                    while (i < 512) {
                        val blue = if (i <= 255) i else 511 - i
                        val red = if (i <= 255) 0 else i - 256
                        waterfallColorMap[i] = Color.argb(0xff, red, 0, blue)
                        i++
                    }
                }

                COLORMAP_GQRX -> {
                    waterfallColorMap = IntArray(256)
                    var i = 0
                    while (i < 256) {
                        if (i < 20) waterfallColorMap[i] =
                            Color.argb(0xff, 0, 0, 0) // level 0: black background
                        else if (i < 70) waterfallColorMap[i] =
                            Color.argb(0xff, 0, 0, 140 * (i - 20) / 50) // level 1: black -> blue
                        else if (i < 100) waterfallColorMap[i] = Color.argb(
                            0xff,
                            60 * (i - 70) / 30,
                            125 * (i - 70) / 30,
                            115 * (i - 70) / 30 + 140
                        ) // level 2: blue -> light-blue / greenish
                        else if (i < 150) waterfallColorMap[i] = Color.argb(
                            0xff,
                            195 * (i - 100) / 50 + 60,
                            130 * (i - 100) / 50 + 125,
                            255 - 255 * (i - 100) / 50
                        ) // level 3: light blue -> yellow
                        else if (i < 250) waterfallColorMap[i] = Color.argb(
                            0xff,
                            255,
                            255 - 255 * (i - 150) / 100,
                            0
                        ) // level 4: yellow -> red
                        else waterfallColorMap[i] = Color.argb(
                            0xff,
                            255,
                            255 * (i - 250) / 5,
                            255 * (i - 250) / 5
                        ) // level 5: red -> white
                        i++
                    }
                }

                else -> Logger.e("createWaterfallColorMap: Unknown color map type: $waterfallColorMapType")
            }
        }
    }
    //------------------- <SurfaceHolder.Callback> ------------------------------//
    /**
     * SurfaceHolder.回调函数。在创建曲面视图时调用。
     * We do all the work in surfaceChanged()...
     *
     * @param holder reference to the surface holder
     */
    override fun surfaceCreated(holder: SurfaceHolder) {}

    /**
     * SurfaceHolder.回调函数。每次维度更改时都会调用此操作
     * (and after the SurfaceView is created).
     *
     * @param holder reference to the surface holder
     * @param format
     * @param width  current width of the surface view
     * @param height current height of the surface view
     */
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (this.width != width || this.height != height) {
            this.width = width
            this.height = height

            // Recreate the shaders:
            fftPaint.setShader(
                LinearGradient(
                    0f,
                    0f,
                    0f,
                    fftHeight.toFloat(),
                    Color.WHITE,
                    Color.BLUE,
                    Shader.TileMode.MIRROR
                )
            )

            // Recreate the waterfall bitmaps:
            createWaterfallLineBitmaps()

            // Fix the text size of the text paint objects:
            setFontSize(fontSize)
        }
    }

    /**
     *SurfaceHolder.回调函数。在销毁曲面视图之前调用
     *
     * @param holder reference to the surface holder
     */
    override fun surfaceDestroyed(holder: SurfaceHolder) {}

    //------------------- </SurfaceHolder.Callback> -----------------------------//
    //------------------- <OnScaleGestureListener> ------------------------------//
    override fun onScale(detector: ScaleGestureDetector): Boolean {
        if (source != null) {
            // Zoom horizontal if focus in the main area or always if decoupled axis is deactivated:
            if (!decoupledAxis || detector.focusX > gridSize * 1.5) {
                val xScale = detector.currentSpanX / detector.previousSpanX
                val frequencyFocus =
                    virtualFrequency + ((detector.focusX / width - 0.5) * virtualSampleRate).toInt()
                var maxSampleRate =
                    if (demodulationEnabled) (source!!.sampleRate * 0.9).toInt() else source!!.maxSampleRate
                if (recordingEnabled) maxSampleRate = source!!.sampleRate
                virtualSampleRate = Math.min(
                    Math.max(virtualSampleRate / xScale, MIN_VIRTUAL_SAMPLERATE.toFloat()),
                    maxSampleRate.toFloat()
                ).toInt()
                virtualFrequency = Math.min(
                    Math.max(
                        frequencyFocus + ((virtualFrequency - frequencyFocus) / xScale).toLong(),
                        source!!.minFrequency - source!!.sampleRate / 2
                    ), source!!.maxFrequency + source!!.sampleRate / 2
                )

                // if we zoomed the channel selector out of the window, reset the channel selector:
                if (demodulationEnabled && channelFrequency < virtualFrequency - virtualSampleRate / 2) {
                    channelFrequency = virtualFrequency - virtualSampleRate / 2
                    rfControlInterface!!.updateChannelFrequency(channelFrequency)
                }
                if (demodulationEnabled && channelFrequency > virtualFrequency + virtualSampleRate / 2) {
                    channelFrequency = virtualFrequency + virtualSampleRate / 2
                    rfControlInterface!!.updateChannelFrequency(channelFrequency)
                }
            }

            // Zoom vertical if enabled and focus in the left grid area or if decoupled axis is deactivated:
            if (verticalZoomEnabled && (!decoupledAxis || detector.focusX <= gridSize * 1.5)) {
                val yScale = detector.currentSpanY / detector.previousSpanY
                val dBFocus = maxDB - (maxDB - minDB) * (detector.focusY / fftHeight)
                val newMinDB = Math.min(
                    Math.max(dBFocus - (dBFocus - minDB) / yScale, MIN_DB.toFloat()),
                    (MAX_DB - 10).toFloat()
                )
                val newMaxDB = Math.min(
                    Math.max(dBFocus - (dBFocus - maxDB) / yScale, newMinDB + 10),
                    MAX_DB.toFloat()
                )
                setDBScale(newMinDB, newMaxDB)

                // adjust the squelch if it is outside the visible viewport right now:
                if (squelch < minDB) squelch = minDB
                if (squelch > maxDB) squelch = maxDB
            }

            // Automatically re-adjust the sample rate of the source if we zoom too far out or in
            // (only if not recording or demodulating!)
            if (!recordingEnabled && !demodulationEnabled) {
                if (source!!.sampleRate < virtualSampleRate && virtualSampleRate < source!!.maxSampleRate) source!!.sampleRate =
                    source!!.getNextHigherOptimalSampleRate(virtualSampleRate)
                val nextLower = source!!.getNextLowerOptimalSampleRate(source!!.sampleRate)
                if (virtualSampleRate < nextLower && source!!.sampleRate > nextLower) {
                    source!!.sampleRate = nextLower
                }
            }
        }
        return true
    }

    override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
        return true
    }

    override fun onScaleEnd(detector: ScaleGestureDetector) {}

    //------------------- </OnScaleGestureListener> -----------------------------//
    //------------------- <OnGestureListener> -----------------------------------//
    override fun onDown(e: MotionEvent): Boolean {
        // Find out which type of scrolling is requested:
        val hzPerPx = virtualSampleRate / width.toFloat()
        val dbPerPx = (maxDB - minDB) / fftHeight.toFloat()
        val channelFrequencyVariation = Math.max(channelWidth * 0.8f, width / 15f * hzPerPx)
        val channelWidthVariation = width / 15 * hzPerPx
        val touchedFrequency = virtualFrequency - virtualSampleRate / 2 + (e.x * hzPerPx).toLong()
        val touchedDB = maxDB - e.y * dbPerPx

        // if the user touched the squelch indicator the user wants to adjust the squelch threshold:
        if (demodulationEnabled && touchedFrequency < channelFrequency + channelWidth && touchedFrequency > channelFrequency - channelWidth && touchedDB < squelch + (maxDB - minDB) / 7 && touchedDB > squelch - (maxDB - minDB) / 7) {
            scrollType = SCROLLTYPE_SQUELCH
            squelchPaint!!.strokeWidth = STROKE_WIDTH_THICK.toFloat()
        } else if (demodulationEnabled && e.y <= fftHeight && touchedFrequency < channelFrequency + channelFrequencyVariation && touchedFrequency > channelFrequency - channelFrequencyVariation) {
            scrollType = SCROLLTYPE_CHANNEL_FREQUENCY
            channelSelectorPaint!!.strokeWidth = STROKE_WIDTH_THICK.toFloat()
        } else if (demodulationEnabled && e.y <= fftHeight && showLowerBand && touchedFrequency < channelFrequency - channelWidth + channelWidthVariation && touchedFrequency > channelFrequency - channelWidth - channelWidthVariation) {
            scrollType = SCROLLTYPE_CHANNEL_WIDTH_LEFT
            channelWidthSelectorPaint!!.strokeWidth =
                STROKE_WIDTH_THICK.toFloat()
        } else if (demodulationEnabled && e.y <= fftHeight && showUpperBand && touchedFrequency < channelFrequency + channelWidth + channelWidthVariation && touchedFrequency > channelFrequency + channelWidth - channelWidthVariation) {
            scrollType = SCROLLTYPE_CHANNEL_WIDTH_RIGHT
            channelWidthSelectorPaint!!.strokeWidth = STROKE_WIDTH_THICK.toFloat()
        } else scrollType = SCROLLTYPE_NORMAL
        return true
    }

    override fun onShowPress(e: MotionEvent) {
        // not used
    }

    override fun onSingleTapUp(e: MotionEvent): Boolean {
        // Set the channel frequency to the tapped position
        if (demodulationEnabled) {
            val hzPerPx = virtualSampleRate / width.toFloat()
            channelFrequency = virtualFrequency - virtualSampleRate / 2 + (hzPerPx * e.x).toLong()
            rfControlInterface!!.updateChannelFrequency(channelFrequency)
        }
        return true
    }

    override fun onScroll(
        e1: MotionEvent,
        e2: MotionEvent,
        distanceX: Float,
        distanceY: Float
    ): Boolean {
        if (source != null) {
            val hzPerPx = virtualSampleRate / width.toFloat()
            Logger.i("滚动类型：$scrollType")
            when (scrollType) {
                SCROLLTYPE_NORMAL ->                     // 如果触点在主区域，则水平滚动;如果解耦轴停用，则始终滚动;
                    if (!decoupledAxis || e1.x > gridSize * 1.5 || e1.y > fftHeight - gridSize) {
                        val minFrequencyShift = Math.max(
                            virtualFrequency * -1 + 1,
                            source!!.minFrequency - source!!.sampleRate / 2 - virtualFrequency
                        )
                        val maxFrequencyShift =
                            source!!.maxFrequency + source!!.sampleRate / 2 - virtualFrequency
                        var virtualFrequencyShift = Math.min(
                            Math.max((hzPerPx * distanceX).toLong(), minFrequencyShift),
                            maxFrequencyShift
                        )
                        var newVirtualFrequency = virtualFrequency + virtualFrequencyShift

                        //如果我们将样本滚动出可见窗口，则会自动重新调整源:
                        // (仅当不录音时)
                        if (!recordingEnabled) {
                            if (source!!.frequency + source!!.sampleRate / 2 < newVirtualFrequency + virtualSampleRate / 2 ||
                                source!!.frequency - source!!.sampleRate / 2 > newVirtualFrequency - virtualSampleRate / 2
                            ) {
                                if (newVirtualFrequency >= source!!.minFrequency && newVirtualFrequency <= source!!.maxFrequency) source!!.frequency =
                                    newVirtualFrequency
                            }
                        } else {
                            // 如果记录，我们限制滚动到fft之外:
                            if (newVirtualFrequency + virtualSampleRate / 2 > source!!.frequency + source!!.sampleRate / 2) newVirtualFrequency =
                                source!!.frequency + source!!.sampleRate / 2 - virtualSampleRate / 2
                            if (newVirtualFrequency - virtualSampleRate / 2 < source!!.frequency - source!!.sampleRate / 2) newVirtualFrequency =
                                source!!.frequency - source!!.sampleRate / 2 + virtualSampleRate / 2
                            virtualFrequencyShift = newVirtualFrequency - virtualFrequency
                        }
                        virtualFrequency += virtualFrequencyShift
                        channelFrequency += virtualFrequencyShift
                        rfControlInterface!!.updateChannelFrequency(channelFrequency)
                    }

                SCROLLTYPE_CHANNEL_FREQUENCY -> {
                    channelFrequency = (channelFrequency - distanceX * hzPerPx).toLong()
                    rfControlInterface!!.updateChannelFrequency(channelFrequency)
                }

                SCROLLTYPE_CHANNEL_WIDTH_LEFT, SCROLLTYPE_CHANNEL_WIDTH_RIGHT -> {
                    val tmpChannelWidth =
                        if (scrollType == SCROLLTYPE_CHANNEL_WIDTH_LEFT) (channelWidth + distanceX * hzPerPx).toInt() else (channelWidth - distanceX * hzPerPx).toInt()
                    if (rfControlInterface!!.updateChannelWidth(tmpChannelWidth)) channelWidth =
                        tmpChannelWidth
                }

                SCROLLTYPE_SQUELCH -> {
                    val dbPerPx = (maxDB - minDB) / fftHeight.toFloat()
                    squelch = squelch + distanceY * dbPerPx
                    if (squelch < minDB) squelch = minDB
                }

                else -> Logger.e("onScroll: invalid scroll type: $scrollType")
            }

            // 垂直滚动条
            if (verticalScrollEnabled && scrollType == SCROLLTYPE_NORMAL) {
                // if touch point in the left grid area of fft or if decoupled axis is deactivated:
                if (!decoupledAxis || e1.x <= gridSize * 1.5 && e1.y <= fftHeight - gridSize) {
                    var yDiff = (maxDB - minDB) * (distanceY / fftHeight.toFloat())
                    // Make sure we stay in the boundaries:
                    if (maxDB - yDiff > MAX_DB) yDiff = MAX_DB - maxDB
                    if (minDB - yDiff < MIN_DB) yDiff = MIN_DB - minDB
                    setDBScale(minDB - yDiff, maxDB - yDiff)

                    // adjust the squelch if it is outside the visible viewport right now and demodulation is enabled:
                    if (demodulationEnabled) {
                        if (squelch < minDB) squelch = minDB
                        if (squelch > maxDB) squelch = maxDB
                    }
                }
            }
        }
        return true
    }

    override fun onLongPress(e: MotionEvent) {
        // not used
    }

    override fun onFling(
        e1: MotionEvent,
        e2: MotionEvent,
        velocityX: Float,
        velocityY: Float
    ): Boolean {
        return true
    }

    //------------------- </OnGestureListener> ----------------------------------//
    override fun onTouchEvent(event: MotionEvent): Boolean {

        // Reset the stroke width of the channel controls if the user lifts his finger:
        if (event.action == MotionEvent.ACTION_UP) {
            squelchPaint!!.strokeWidth = STROKE_WIDTH_NORMAL.toFloat()
            channelSelectorPaint!!.strokeWidth = STROKE_WIDTH_NORMAL.toFloat()
            channelWidthSelectorPaint!!.strokeWidth =
                STROKE_WIDTH_NORMAL.toFloat()
        }
        var retVal = scaleGestureDetector!!.onTouchEvent(event)
        retVal = gestureDetector!!.onTouchEvent(event) || retVal
        return retVal
    }

    /**
     * 返回fft图的高度，单位为px (fft谱底线的y坐标)
     *
     * @return heigth (in px) of the fft
     */
    private val fftHeight: Int
        get() = (height * fftRatio).toInt()

    /**
     * 返回瀑布图的高度（单位：px）
     *
     * @return heigth (in px) of the waterfall
     */
    private val waterfallHeight: Int
        get() = (height * (1 - fftRatio)).toInt()

    /**
     * 返回频率/功率网格的高度/宽度（以px为单位）
     *
     * @return size of the grid (frequency grid height / power grid width) in px
     */
    private val gridSize: Int
        get() {
            val xdpi = resources.displayMetrics.xdpi
            val xpixel = resources.displayMetrics.widthPixels.toFloat()
            val xinch = xpixel / xdpi
            return if (xinch < 30) (75 * xdpi / 200).toInt() // Smartphone / Tablet / Computer screen
            else (400 * xdpi / 200).toInt() // TV screen
        }

    /**
     *返回瀑布图中每一行的高度（以像素为单位）
     *
     * @return number of pixels (in vertical direction) of one line in the waterfall plot
     */
    private val pixelPerWaterfallLine: Int
        get() = 1

    /**
     * 将(重新)在表面上绘制给定的数据集。请注意，它实际上只绘制fft数据的一个子集，这取决于虚拟频率和采样率的当前设置。
     *
     * @param mag        array of magnitude values that represent the fft
     * @param frequency  center frequency
     * @param sampleRate sample rate
     * @param frameRate  current frame rate (FPS)
     * @param load       current load (percentage [0..1])
     */
    fun draw(mag: FloatArray, frequency: Long, sampleRate: Int, frameRate: Int, load: Double) {
        if (virtualFrequency < 0) virtualFrequency = frequency
        if (virtualSampleRate < 0) virtualSampleRate = sampleRate

        // 根据频率和采样率以及虚拟频率和采样率计算绘制磁图的起始和结束指标:
        val samplesPerHz = mag.size.toFloat() / sampleRate.toFloat() // 表示有多少样品在mag覆盖1hz
        val frequencyDiff = virtualFrequency - frequency //中心频率差
        val sampleRateDiff = virtualSampleRate - sampleRate // 采样率差异
        val start = ((frequencyDiff - sampleRateDiff / 2.0) * samplesPerHz).toInt()
        val end = mag.size + ((frequencyDiff + sampleRateDiff / 2.0) * samplesPerHz).toInt()

        //求平均值 averageLength 默认为0
        if (averageLength > 0) {
            // 验证历史样本数组是否正确初始化:
            if (historySamples == null || historySamples!!.size != averageLength || historySamples!![0].size != mag.size) {
                historySamples = Array(averageLength) { FloatArray(mag.size) }
                for (i in 0 until averageLength) {
                    for (j in mag.indices) {
                        historySamples!![i][j] = mag[j]
                    }
                }
                oldesthistoryIndex = 0
            }
            // 检查输入信号的频率或采样率是否与之前的不同: 不同的话就将历史采样数据重置成新的数据
            if (frequency != lastFrequency || sampleRate != lastSampleRate) {
                for (i in 0 until averageLength) {
                    for (j in mag.indices) {
                        historySamples!![i][j] = mag[j] // 重置历史记录。我们也可以改变和扩大规模。但就目前而言，它们只是重新设置。
                    }
                }
            }
            //计算平均值（将它们存储到mag中）。将mag复制到最旧的历史索引
            var tmp: Float
            for (i in mag.indices) {
                tmp = mag[i]
                for (j in historySamples!!.indices) tmp += historySamples!![j][i]
                historySamples!![oldesthistoryIndex][i] = mag[i]
                mag[i] = tmp / (historySamples!!.size + 1) // 这里求得的是一个平均值 最近5/10 次的平均值
            }
            oldesthistoryIndex = (oldesthistoryIndex + 1) % historySamples!!.size
        }

        // Autoscale 是否允许自动缩放刻度尺比例
        if (doAutoscaleInNextDraw) {
            doAutoscaleInNextDraw = false
            var min = MAX_DB.toFloat()
            var max = MIN_DB.toFloat()
            var i = Math.max(0, start)
            while (i < Math.min(mag.size, end)) {
                // 尝试避免DC峰值（其总是正好在mag的中间：
                if (i == mag.size / 2 - 5) i += 10 // 这有效地跳过了DC偏移峰值
                min = Math.min(mag[i], min)
                max = Math.max(mag[i], max)
                i++
            }
            if (min < max) {
                minDB = Math.max(min, MIN_DB.toFloat())
                maxDB = Math.min(max, MAX_DB.toFloat())
            }
            if (squelch < minDB) squelch = minDB
            if (squelch > maxDB) squelch = maxDB
        }

        // 更新峰值保持 默认为 false
        if (peakHoldEnabled) {
            // 首先验证阵列是否已正确初始化：
            if (peaks == null || peaks!!.size != mag.size) {
                peaks = FloatArray(mag.size)
                for (i in peaks!!.indices) peaks!![i] = -999999f // == no peak ;)
            }
            // 检查输入信号的频率或采样率是否与之前不同：
            if (frequency != lastFrequency || sampleRate != lastSampleRate) {
                for (i in peaks!!.indices) peaks!![i] =
                    -999999f // 重置峰值。我们也可以改变和扩大规模。但就目前而言，它们只是重新设置。
            }
            // 更新峰值：比较当前值和最新的值哪个最大，永远保持最大值
            for (i in mag.indices) peaks!![i] = Math.max(peaks!![i], mag[i])
        } else {
            peaks = null
        }

        // 满足更新抑制：
        var averageSignalStrengh = -9999f // 所选通道中心信号的平均幅值
        // 是否开启解调模式 AM NFM WFM LSB USB
        if (demodulationEnabled) {
            var sum = 0f
            val chanStart =
                ((channelFrequency - (frequency - sampleRate / 2) - channelWidth / 2) * samplesPerHz).toInt()
            val chanEnd = (chanStart + channelWidth * samplesPerHz).toInt()
            if (chanStart > 0 && chanEnd <= mag.size) {
                for (i in chanStart until chanEnd) sum += mag[i]
                averageSignalStrengh = sum / (chanEnd - chanStart)
                if (averageSignalStrengh >= squelch && squelchSatisfied == false) {
                    squelchSatisfied = true
                    squelchPaint.color = Color.GREEN
                    rfControlInterface!!.updateSquelchSatisfied(squelchSatisfied)
                } else if (averageSignalStrengh < squelch && squelchSatisfied == true) {
                    squelchSatisfied = false
                    squelchPaint.color = Color.RED
                    rfControlInterface!!.updateSquelchSatisfied(squelchSatisfied)
                }
                // else the squelchSatisfied flag is still valid. no actions needed...
            }
        }

        // Draw:
        var c: Canvas? = null
        try {
            val currentTime = System.currentTimeMillis()
            c = this.holder.lockCanvas()
            synchronized(this.holder) {
                if (c != null) {
                    //drawCollect(c)
                    // Draw all the components
                    drawFFT(c, mag, start, end)
                    drawWaterfall(c)
                    drawFrequencyGrid(c)
                    drawPowerGrid(c)
                    drawPerformanceInfo(c, frameRate, load, averageSignalStrengh)
                } else Logger.d("draw: Canvas is null.")
            }
            val useTime = System.currentTimeMillis() - currentTime
            // Logger.i("绘制完成一帧图片耗时：" + useTime);
        } catch (e: Exception) {
            Logger.e("draw: Error while drawing on the canvas. Stop!")
            e.printStackTrace()
        } finally {
            if (c != null) {
                this.holder.unlockCanvasAndPost(c)
            }
        }

        // 更新上次频率和采样率：
        lastFrequency = frequency
        lastSampleRate = sampleRate
    }

    /**
     * 这种方法将fft绘制到画布上。它还将使用mag中的数据更新waterfallLinesWaterfallLinesTopIndex]中的位图。
     * 重要提示：开始和结束可能超出mag数组的范围。这将导致黑色填充。
     * @param c       canvas of the surface view
     * @param mag    表示fft的幅值数组
     * @param start  从mag中提取的第一个索引（可能为负数）
     * @param end    从mag中提取的最后一个索引（可能大于mag.length）
     */
    private fun drawFFT(c: Canvas, mag: FloatArray, start: Int, end: Int) {
        var previousY = fftHeight.toFloat() // 先前处理像素的Y坐标(仅用于绘制类型线)
        var currentY: Float //当前处理的像素的Y坐标
        val samplesPerPx = (end - start).toFloat() / width.toFloat() //每一个像素的FFT采样数
        val dbDiff = maxDB - minDB // 45db
        val dbWidth = fftHeight / dbDiff // fft中每1dB的大小(像素单位)
        val scale =
            waterfallColorMap.size / dbDiff // scale for the color mapping of the waterfall
        var avg: Float // 用于计算mag中多个值的平均值(水平平均值)
        var peakAvg: Float // 用于计算峰值中多个值的平均值
        var waterfallAvg: Float // 用于计算历史样本[oldestHistoryIndex-1]中多个值的平均值。
        // 这是用来忽略瀑布图中的平均时间
        var counter: Int // 用于计算峰值和峰值中多个值的平均值
        var latestHistoryIndex = 0

        // latestHistoryIndex指向historySamples数组中的当前fft值，并用于计算瀑布平均值
        if (historySamples != null) latestHistoryIndex =
            if (oldesthistoryIndex == 0) historySamples!!.size - 1 else oldesthistoryIndex - 1
        var newline: Canvas? = null
        waterfallLines?.let {
            val bitmap = it[waterfallLinesTopIndex]
            bitmap?.let { bt ->
                // 从当前瀑布线的位图中获取画布并清除它:
                newline = Canvas(bt)
                newline?.drawColor(Color.WHITE)
            }
        }

        // 清除画布中的fft区域:
        c.drawRect(0f, 0f, width.toFloat(), fftHeight.toFloat(), blackPaint)

        // 如果start为负值，则要绘制的起始位置为0或大于0:
        val firstPixel = if (start >= 0) 0 else (start * -1 / samplesPerPx).toInt()

        // 我们只会走到尽头，而不是更远。
        val lastPixel =
            if (end >= mag.size) ((mag.size - start) / samplesPerPx).toInt() else ((end - start) / samplesPerPx).toInt()

        //Logger.d(start + ";" + end + ";" + width + ";" + height + ";" + previousY + ";" + samplesPerPx + ";" + dbDiff + ";" + dbWidth + ";" + firstPixel + ";" + lastPixel + ";");
        //0;4096; 1920;992;496.0;2.1333334;45.0;11.0222225;0;1919;
        //  逐像素绘制:
        // 由于整数舍入错误，我们从firstPixel+1开始
        for (i in firstPixel + 1 until lastPixel) {
            //计算该像素的平均值(水平平均值-而不是时域平均值):
            avg = 0f
            peakAvg = 0f
            waterfallAvg = 0f
            counter = 0
            // samplesPerPx 每个像素的采样数 2.1333334
            var j = (i * samplesPerPx).toInt()
            while (j < (i + 1) * samplesPerPx) {
                avg += mag[j + start] // start:0
                if (peaks != null) peakAvg += peaks!![j + start]
                if (averageLength > 0) waterfallAvg += historySamples!![latestHistoryIndex][j + start]
                counter++
                j++
            }
            avg = avg / counter
            //Logger.i("avg:" + avg + ";counter:" + counter);
            //avg:-44.40581;counter:3
            if (peaks != null) peakAvg = peakAvg / counter
            waterfallAvg = if (averageLength > 0) waterfallAvg / counter else avg // avg和瀑布avg没有区别

            // FFT:
            if (avg > minDB) {
                currentY = fftHeight - (avg - minDB) * dbWidth
                if (currentY < 0) currentY = 0f
                when (fftDrawingType) {
                    FFT_DRAWING_TYPE_BAR -> c.drawLine(
                        i.toFloat(),
                        fftHeight.toFloat(),
                        i.toFloat(),
                        currentY,
                        fftPaint
                    )

                    FFT_DRAWING_TYPE_LINE -> {
                        c.drawLine((i - 1).toFloat(), previousY, i.toFloat(), currentY, fftPaint)
                        previousY = currentY

                        // 如果我们在最后一轮，我们必须画出最后一条底线：
                        if (i + 1 == lastPixel) c.drawLine(
                            i.toFloat(),
                            previousY,
                            (i + 1).toFloat(),
                            fftHeight.toFloat(),
                            fftPaint
                        )
                    }

                    else -> Logger.e("drawFFT: Invalid fft drawing type: $fftDrawingType")
                }
            }

            // Peak:
            if (peaks != null) {
                if (peakAvg > minDB) {
                    peakAvg = fftHeight - (peakAvg - minDB) * dbWidth
                    if (peakAvg > 0) c.drawPoint(i.toFloat(), peakAvg, peakHoldPaint)
                }
            }
            // Waterfall:
            if (waterfallAvg <= minDB)
                waterfallLinePaint.color = waterfallColorMap[0]
            else if
                         (waterfallAvg >= maxDB) waterfallLinePaint.color =
                waterfallColorMap[waterfallColorMap.size - 1]
            else
                waterfallLinePaint.color =
                    waterfallColorMap[((waterfallAvg - minDB) * scale).toInt()]

            // getPixelPerWaterfallLine :默认1
            if (pixelPerWaterfallLine > 1)
                newline?.drawLine(
                    i.toFloat(),
                    0f,
                    i.toFloat(),
                    pixelPerWaterfallLine.toFloat(),
                    waterfallLinePaint
                ) else newline?.drawPoint(i.toFloat(), 0f, waterfallLinePaint)
        }
    }

    /**
     * @param c canvas of the surface view
     */
    private fun drawWaterfall(c: Canvas) {
        var yPos = fftHeight
        val yDiff = pixelPerWaterfallLine

        waterfallLines?.let {
            // 在画布上绘制位图:
            for (i in it.indices) {
                val idx = (waterfallLinesTopIndex + i) % it.size
                val bitmap = it[idx]
                bitmap?.let {
                    c.drawBitmap(it, 0f, yPos.toFloat(), defaultPaint)
                    yPos += yDiff
                }

            }
        }

        // 移动数组索引(注意，为了正确操作，我们必须进行减量)
        waterfallLinesTopIndex--
        if (waterfallLinesTopIndex < 0) waterfallLinesTopIndex += waterfallLines!!.size
    }

    /**
     * 横坐标 表示频率
     *
     * @param c canvas of the surface view
     */
    private fun drawFrequencyGrid(c: Canvas) {
        var textStr: String
        val MHZ = 1000000.0
        var tickFreqMHz: Double
        var lastTextEndPos =
            -99999f // will indicate the horizontal pixel pos where the last text ended
        var textPos: Float
        // 计算文本之间的最小间距(以px为单位)，如果我们希望它至少分开
        // 与两个破折号所占用的空间相同。
        val bounds = Rect()
        textPaint.getTextBounds("--", 0, 2, bounds)
        val minFreeSpaceBetweenText = bounds.width().toFloat()
        //计算一个小刻度的跨度(必须是10KHz的功率)
        var tickSize = 10 // we start with 10KHz
        var helperVar = virtualSampleRate / 20f
        while (helperVar > 100) {
            helperVar = helperVar / 10f
            tickSize = tickSize * 10
        }
        // 计算小刻度的像素宽度
        val pixelPerMinorTick = width / (virtualSampleRate / tickSize.toFloat())
        // 计算fft最左边点的频率:接收的信号频率范围通常是中心频率加减采样率的一半
        val startFrequency: Long =
            if (isDisplayRelativeFrequencies) (-1 * (virtualSampleRate / 2.0)).toLong() else (virtualFrequency - virtualSampleRate / 2.0).toLong()
        //Logger.i("采样率：$virtualSampleRate,中心频率：$virtualFrequency")
        // 采样率：900000,中心频率：108038290(动态)
        // 计算第一个Tick的频率和位置(Tick是每个<tickSize> KHz)
        var tickFreq =
            (Math.ceil(startFrequency.toDouble() / tickSize.toFloat()) * tickSize).toLong()
        var tickPos = pixelPerMinorTick / tickSize.toFloat() * (tickFreq - startFrequency)
        //  Logger.i("刻度：" + startFrequency + ";" + tickFreq + ";" + tickPos + ";" + pixelPerMinorTick);
        // 画刻度
        var i = 0
        while (i < virtualSampleRate / tickSize.toFloat()) {
            var tickHeight: Float
            if (tickFreq % (tickSize * 10) == 0L) {
                // Major Tick (10x <tickSize> KHz)
                tickHeight = (gridSize / 2.0).toFloat()

                // 绘制频率文本（始终以MHz为单位）
                tickFreqMHz = tickFreq / MHZ
                textStr = if (tickFreqMHz == tickFreqMHz.toInt().toDouble()) String.format(
                    "%d",
                    tickFreqMHz.toInt()
                ) else String.format("%s", tickFreqMHz)
                textPaint.getTextBounds(textStr, 0, textStr.length, bounds)
                textPos = tickPos - bounds.width() / 2

                // …仅当与最后一个文本不重叠时：
                if (lastTextEndPos + minFreeSpaceBetweenText < textPos) {
                    c.drawText(textStr, textPos, fftHeight - tickHeight, textPaint)
                    lastTextEndPos = textPos + bounds.width()
                }
            } else if (tickFreq % (tickSize * 5) == 0L) {
                // 半大刻度（5x<tickSize>KHz）
                tickHeight = (gridSize / 3.0).toFloat()

                // 绘制频率文本（始终以MHz为单位）。。。
                tickFreqMHz = tickFreq / MHZ
                textStr = if (tickFreqMHz == tickFreqMHz.toInt().toDouble()) String.format(
                    "%d",
                    tickFreqMHz.toInt()
                ) else String.format("%s", tickFreqMHz)
                textSmallPaint.getTextBounds(textStr, 0, textStr.length, bounds)
                textPos = tickPos - bounds.width() / 2

                // ...only if not overlapping with the last text:
                if (lastTextEndPos + minFreeSpaceBetweenText < textPos) {
                    // ... if enough space between the major ticks:
                    if (bounds.width() < pixelPerMinorTick * 3) {
                        c.drawText(textStr, textPos, fftHeight - tickHeight, textSmallPaint)
                        lastTextEndPos = textPos + bounds.width()
                    }
                }
            } else {
                // Minor tick (<tickSize> KHz)
                tickHeight = (gridSize / 4.0).toFloat()
            }
            // Draw the tick line:
            c.drawLine(tickPos, fftHeight.toFloat(), tickPos, fftHeight - tickHeight, textPaint)
            tickFreq += tickSize.toLong()
            tickPos += pixelPerMinorTick
            i++
        }
        // 如果解调被激活:绘制通道选择器:
        if (demodulationEnabled) {
            val pxPerHz = width / virtualSampleRate.toFloat()
            val channelPosition = width / 2 + pxPerHz * (channelFrequency - virtualFrequency)
            val leftBorder = channelPosition - pxPerHz * channelWidth
            val rightBorder = channelPosition + pxPerHz * channelWidth
            val dbWidth = fftHeight / (maxDB - minDB)
            val squelchPosition = fftHeight - (squelch - minDB) * dbWidth

            // draw half transparent channel area:
            channelSelectorPaint!!.alpha = 0x7f
            if (showLowerBand) c.drawRect(
                leftBorder,
                0f,
                channelPosition,
                squelchPosition,
                channelSelectorPaint
            )
            if (showUpperBand) c.drawRect(
                channelPosition,
                0f,
                rightBorder,
                squelchPosition,
                channelSelectorPaint
            )

            // draw center and borders:
            channelSelectorPaint?.alpha = 0xff
            c.drawLine(
                channelPosition,
                fftHeight.toFloat(),
                channelPosition,
                0f,
                channelSelectorPaint
            )
            if (showLowerBand) {
                c.drawLine(
                    leftBorder,
                    fftHeight.toFloat(),
                    leftBorder,
                    0f,
                    channelWidthSelectorPaint
                )
                c.drawLine(
                    leftBorder,
                    squelchPosition,
                    channelPosition,
                    squelchPosition,
                    squelchPaint
                )
            }
            if (showUpperBand) {
                c.drawLine(
                    rightBorder,
                    fftHeight.toFloat(),
                    rightBorder,
                    0f,
                    channelWidthSelectorPaint
                )
                c.drawLine(
                    channelPosition,
                    squelchPosition,
                    rightBorder,
                    squelchPosition,
                    squelchPaint
                )
            }

            // draw squelch text above the squelch selector:
            textStr = String.format("%2.1f dB", squelch)
            textSmallPaint.getTextBounds(textStr, 0, textStr.length, bounds)
            c.drawText(
                textStr,
                channelPosition - bounds.width() / 2f,
                squelchPosition - bounds.height() * 0.1f,
                textSmallPaint
            )

            // draw channel width text below the squelch selector:
            var shownChannelWidth = 0
            if (showLowerBand) shownChannelWidth += channelWidth
            if (showUpperBand) shownChannelWidth += channelWidth
            textStr = String.format("%d kHz", shownChannelWidth / 1000)
            textSmallPaint.getTextBounds(textStr, 0, textStr.length, bounds)
            c.drawText(
                textStr,
                channelPosition - bounds.width() / 2f,
                squelchPosition + bounds.height() * 1.1f,
                textSmallPaint
            )
        }
    }

    /**
     * 信号强度纵坐标
     *
     * @param c canvas of the surface view
     */
    private fun drawPowerGrid(c: Canvas) {
        // Calculate pixel height of a minor tick (1dB)
        val pixelPerMinorTick = (fftHeight / (maxDB - minDB))

        // Draw the ticks from the top to the bottom. Stop as soon as we interfere with the frequency scale
        var tickDB = maxDB.toInt()
        var tickPos = (maxDB - tickDB) * pixelPerMinorTick
        while (tickDB > minDB) {
            var tickWidth: Float
            if (tickDB % 10 == 0) {
                // Major Tick (10dB)
                tickWidth = (gridSize / 3.0).toFloat()
                // Draw Frequency Text:
                c.drawText("" + tickDB, (gridSize / 2.9).toFloat(), tickPos, textPaint)
            } else if (tickDB % 5 == 0) {
                // 5 dB tick
                tickWidth = (gridSize / 3.5).toFloat()
            } else {
                // Minor tick
                tickWidth = (gridSize / 5.0).toFloat()
            }
            c.drawLine(0f, tickPos, tickWidth, tickPos, textPaint)
            tickPos += pixelPerMinorTick

            // stop if we interfere with the frequency grid:
            if (tickPos > fftHeight - gridSize) break
            tickDB--
        }
    }

    /**
     * 该方法将把性能信息绘制到画布右上角
     *
     * @param c                     canvas of the surface view
     * @param frameRate             current frame rate (FPS)
     * @param load                  current load (percentage [0..1])
     * @param averageSignalStrength average magnitude of the signal in the selected channel
     */
    private fun drawPerformanceInfo(
        c: Canvas,
        frameRate: Int,
        load: Double,
        averageSignalStrength: Float
    ) {
        val bounds = Rect()
        var text: String
        var yPos = height * 0.01f
        val rightBorder = width * 0.99f

        // 源名称和信息
        if (source != null) {
            // Name
            text = source!!.name
            textSmallPaint.getTextBounds(text, 0, text.length, bounds)
            c.drawText(text, rightBorder - bounds.width(), yPos + bounds.height(), textSmallPaint)
            yPos += bounds.height() * 1.1f

            // 中心频率
            text = String.format("tuned to %4.6f MHz", source!!.frequency / 1000000f)
            textSmallPaint.getTextBounds(text, 0, text.length, bounds)
            c.drawText(text, rightBorder - bounds.width(), yPos + bounds.height(), textSmallPaint)
            yPos += bounds.height() * 1.1f

            //中心频率
            if (isDisplayRelativeFrequencies) {
                text = String.format("centered at %4.6f MHz", virtualFrequency / 1000000f)
                textSmallPaint.getTextBounds(text, 0, text.length, bounds)
                c.drawText(
                    text,
                    rightBorder - bounds.width(),
                    yPos + bounds.height(),
                    textSmallPaint
                )
                yPos += bounds.height() * 1.1f
            }

            // HackRF specific stuff:
            if (source is HackrfSource) {
                text = String.format(
                    "offset=%4.6f MHz",
                    (source as HackrfSource).frequencyOffset / 1000000f
                )
                textSmallPaint.getTextBounds(text, 0, text.length, bounds)
                c.drawText(
                    text,
                    rightBorder - bounds.width(),
                    yPos + bounds.height(),
                    textSmallPaint
                )
                yPos += bounds.height() * 1.1f
            }
            // RTLSDR specific stuff:
            if (source is RtlsdrSource) {
                text = String.format(
                    "offset=%4.6f MHz",
                    (source as RtlsdrSource).frequencyOffset / 1000000f
                )
                textSmallPaint.getTextBounds(text, 0, text.length, bounds)
                c.drawText(
                    text,
                    rightBorder - bounds.width(),
                    yPos + bounds.height(),
                    textSmallPaint
                )
                yPos += bounds.height() * 1.1f
                text = "ppm=" + (source as RtlsdrSource).frequencyCorrection
                textSmallPaint.getTextBounds(text, 0, text.length, bounds)
                c.drawText(
                    text,
                    rightBorder - bounds.width(),
                    yPos + bounds.height(),
                    textSmallPaint
                )
                yPos += bounds.height() * 1.1f
            }
        }

        // 若开启解调，则绘制通道频率:
        if (demodulationEnabled) {
            text = String.format("demod at %4.6f MHz", channelFrequency / 1000000f)
            textSmallPaint.getTextBounds(text, 0, text.length, bounds)
            c.drawText(text, rightBorder - bounds.width(), yPos + bounds.height(), textSmallPaint)

            // increase yPos:
            yPos += bounds.height() * 1.1f
        }

        // 若开启解调，则绘制平均信号强度指示器
        if (demodulationEnabled) {
            text = String.format("%2.1f dB", averageSignalStrength)
            textSmallPaint!!.getTextBounds(text, 0, text.length, bounds)
            val indicatorWidth = (width / 10).toFloat()
            val indicatorPosX = rightBorder - indicatorWidth
            val indicatorPosY = yPos + bounds.height()
            val squelchTickPos = (squelch - minDB) / (maxDB - minDB) * indicatorWidth
            var signalWidth = (averageSignalStrength - minDB) / (maxDB - minDB) * indicatorWidth
            if (signalWidth < 0) signalWidth = 0f
            if (signalWidth > indicatorWidth) signalWidth = indicatorWidth

            // 绘制信号矩形:
            c.drawRect(
                indicatorPosX,
                yPos + bounds.height() * 0.1f,
                indicatorPosX + signalWidth,
                indicatorPosY,
                squelchPaint
            )

            //绘制左边框、右边框、底线和静音勾号:
            c.drawLine(indicatorPosX, indicatorPosY, indicatorPosX, yPos, textPaint)
            c.drawLine(rightBorder, indicatorPosY, rightBorder, yPos, textPaint)
            c.drawLine(indicatorPosX, indicatorPosY, rightBorder, indicatorPosY, textPaint)
            c.drawLine(
                indicatorPosX + squelchTickPos,
                indicatorPosY + 2,
                indicatorPosX + squelchTickPos,
                yPos + bounds.height() * 0.5f,
                textPaint
            )

            // draw text:
            c.drawText(text, indicatorPosX - bounds.width() * 1.1f, indicatorPosY, textSmallPaint)

            // increase yPos:
            yPos += bounds.height() * 1.1f
        }

        // Draw recording information
        if (recordingEnabled) {
            text = String.format(
                "%4.6f MHz @ %2.3f MSps",
                source!!.frequency / 1000000f,
                source!!.sampleRate / 1000000f
            )
            textSmallPaint!!.getTextBounds(text, 0, text.length, bounds)
            c.drawText(text, rightBorder - bounds.width(), yPos + bounds.height(), textSmallPaint)
            defaultPaint!!.color = Color.RED
            c.drawCircle(
                rightBorder - bounds.width() - bounds.height() / 2 * 1.3f,
                yPos + bounds.height() / 2,
                (bounds.height() / 2).toFloat(),
                defaultPaint
            )

            // increase yPos:
            yPos += bounds.height() * 1.1f
        }
        if (isShowDebugInformation) {
            // Draw the FFT/s rate
            text = "$frameRate FPS"
            textSmallPaint!!.getTextBounds(text, 0, text.length, bounds)
            c.drawText(text, rightBorder - bounds.width(), yPos + bounds.height(), textSmallPaint)
            yPos += bounds.height() * 1.1f

            // Draw the load
            text = String.format("%3.1f %%", load * 100)
            textSmallPaint?.getTextBounds(text, 0, text.length, bounds)
            c.drawText(text, rightBorder - bounds.width(), yPos + bounds.height(), textSmallPaint)
            yPos += bounds.height() * 1.1f
        }
    }

    private var totalAngle: Float = 0f

    /**
     * 正在采集当前环境的频率
     */
    fun drawCollect(c: Canvas) {
        //Logger.i("width:$width;height:$height")
        //c.drawArc()
        val radius = 300f
        c.drawCircle((width / 2).toFloat(), (height / 2).toFloat(), radius + 80, bgPaint)
        c.save()
        c.concat(matrix)
        c.translate(width / 2f, height / 2f)
        c.drawCircle(0f, 0f, radius, sweepPaint)
        c.drawCircle(0f, 0f, radius + 50, strokeWhitePaint)
        c.drawCircle(0f, 0f, radius - 50, strokeWhitePaint)
        c.drawCircle(0f, 0f, radius - 100, strokeWhitePaint)
        c.restore()
        if (enableCollect) {
            if (totalAngle >= 360) totalAngle = 0f
            totalAngle += sweepAngle //统计总的旋转角度
            matrix?.postRotate(
                sweepAngle,
                (width / 2).toFloat(),
                (height / 2).toFloat()
            ) //旋转矩阵

        }
    }

    private val runnable: Runnable = object : Runnable {
        override fun run() {
            if (enableCollect) {
                totalAngle += sweepAngle //统计总的旋转角度
                matrix?.postRotate(
                    sweepAngle,
                    (width / 2).toFloat(),
                    (height / 2).toFloat()
                ) //旋转矩阵
                postInvalidate() //刷新
                postDelayed(this, 50) //调用自身，实现不断循环
            }
        }
    }


    companion object {
        // horizontal axis.
        private const val MIN_DB = -100 // 垂直刻度可以启动的最小dB值
        private const val MAX_DB = 10 // 垂直刻度可以启动的最高dB值
        private const val MIN_VIRTUAL_SAMPLERATE = 64 // 最小虚拟采样率
        const val COLORMAP_JET =
            1                   // BLUE(0,0,1) - LIGHT_BLUE(0,1,1) - GREEN(0,1,0) - YELLOW(1,1,0) - RED(1,0,0)
        const val COLORMAP_HOT =
            2                   // BLACK (0,0,0) - RED (1,0,0) - YELLOW (1,1,0) - WHITE (1,1,1)
        const val COLORMAP_OLD =
            3                  // from version 1.00 :)
        const val COLORMAP_GQRX =
            4                // from https://github.com/csete/gqrx  -> qtgui/plotter.cpp
        const val FFT_DRAWING_TYPE_BAR = 1          // 绘制为条形图
        const val FFT_DRAWING_TYPE_LINE = 2        // 绘制为线
        private const val SCROLLTYPE_NORMAL = 1
        private const val SCROLLTYPE_CHANNEL_FREQUENCY = 2
        private const val SCROLLTYPE_CHANNEL_WIDTH_LEFT = 3
        private const val SCROLLTYPE_CHANNEL_WIDTH_RIGHT = 4
        private const val SCROLLTYPE_SQUELCH = 5
        const val STROKE_WIDTH_NORMAL = 1
        const val STROKE_WIDTH_THICK = 5
        const val FONT_SIZE_SMALL = 1
        const val FONT_SIZE_MEDIUM = 2
        const val FONT_SIZE_LARGE = 3
    }
}
