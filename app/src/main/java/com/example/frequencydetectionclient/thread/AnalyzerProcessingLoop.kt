package com.example.frequencydetectionclient.thread

import android.os.Build
import com.example.frequencydetectionclient.MainActivity.Companion.END_FREQUENCY
import com.example.frequencydetectionclient.MainActivity.Companion.START_FREQUENCY
import com.example.frequencydetectionclient.MainActivity.Companion.collectQueue
import com.example.frequencydetectionclient.MainActivity.Companion.scanningMap
import com.example.frequencydetectionclient.MyApp
import com.example.frequencydetectionclient.bean.MaxFrequency
import com.example.frequencydetectionclient.utils.FFT
import com.example.frequencydetectionclient.bean.SamplePacket
import com.example.frequencydetectionclient.dialog.ScanDialog
import com.example.frequencydetectionclient.iq.IQSourceInterface
import com.example.frequencydetectionclient.manager.SpManager
import com.example.frequencydetectionclient.view.AnalyzerSurface
import com.orhanobut.logger.Logger
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Module:      AnalyzerProcessingLoop.java
 * Description: 该线程将从传入队列（由调度器提供）中获取样本，进行信号处理（fft），然后将结果转发到固定费率。它稳定了fft的生成速率，从而给出瀑布显示线性时间尺度。
 */
class AnalyzerProcessingLoop(
    private val view: AnalyzerSurface,
    fftSize: Int,
    inputQueue: ArrayBlockingQueue<SamplePacket>?,
    returnQueue: ArrayBlockingQueue<SamplePacket>?,
    iqSourceInterface: IQSourceInterface
) : Thread() {
    private var fftSize = 0 // FFT的大小   默认4096

    @JvmField
    var frameRate = 10 // 每秒帧数
    private var load = 0.0 // Time_for_processing_and_drawing / Time_per_Frame
    var isDynamicFrameRate = true // 打开和关闭自动帧速率控制
    private var stopRequested = true //设置为true时将停止线程
    private var mag: FloatArray? = null // 频谱的幅值
    private var fftBlock: FFT? = null
    private var inputQueue: ArrayBlockingQueue<SamplePacket>? = null // 传递示例数据包的队列
    private var returnQueue: ArrayBlockingQueue<SamplePacket>? = null // 队列以返回未使用的缓冲区

    var mIQSourceInterface: IQSourceInterface? = null                // 对RFControlInterface处理程序的引用

    // 过滤Wi-Fi
    private var filterWifiEnable = false

    // 过滤基站
    private var filterStationEnable = false

    // 过滤不规则信号
    private var filterDisorderEnable = false

    // 过滤对讲机的信号
    private var filterInterPhoneEnable = false

    // 2.6g信号
    private var filterOtherEnable = false

    // 频段扫描的
    private var scanStatus: Int = 0


//    /**
//     * 扫描频段
//     */
//    private var collectQueue: MutableMap<Long, FloatArray>? = null


    /**
     * 将启动处理循
     */
    override fun start() {
        stopRequested = false
        super.start()
    }

    /**
     * 过滤Wi-Fi
     */
    fun setWifi(enable: Boolean) {
        this.filterWifiEnable = enable
        Logger.i("是否过滤Wi-Fi：$enable")
    }

    /**
     * 过滤基站
     */
    fun setStation(enable: Boolean) {
        this.filterStationEnable = enable
        Logger.i("是否过滤不规则信号：$enable")
    }

    /**
     * 过滤不规则无序的信号
     */
    fun setDisorder(enable: Boolean) {
        this.filterDisorderEnable = enable
    }

    /**
     * 过滤对讲机的信号
     */
    fun setInterPhone(enable: Boolean) {
        this.filterInterPhoneEnable = enable
    }

    fun setOtherFilter(enable: Boolean) {
        filterOtherEnable = enable
    }

    /**
     * 设置扫描的状态
     */
    fun setScanStatus(status: Int) {
        scanStatus = status
    }

    /**
     * 将设置stopRequested标志，以便处理循环终止
     */
    fun stopLoop() {
        stopRequested = true
    }

    val isRunning: Boolean
        /**
         * @return true if loop is running; false if not.
         */
        get() = !stopRequested


    private var workStatus: Int = WORK_STATUS_DEFAULT

    /**
     * 构造函数，将初始化成员属性。
     *
     * @param view        reference to the AnalyzerSurface for drawing
     * @param fftSize     Size of the FFT
     * @param inputQueue  queue that delivers sample packets
     * @param returnQueue queue to return unused buffers
     */
    init {
        // 检查fftSize是否是2的幂
        val order = (ln(fftSize.toDouble()) / ln(2.0)).toInt()
        require(fftSize == 1 shl order) { "FFT大小必须是2的幂" }
        this.fftSize = fftSize
        fftBlock = FFT(fftSize)
        mag = FloatArray(fftSize)
        this.inputQueue = inputQueue
        this.returnQueue = returnQueue
        mIQSourceInterface = iqSourceInterface
    }

    /**
     * 设置工作模式
     */
    fun setWorkStatus(status: Int) {
        workStatus = status
        initPara()
        Logger.i("工作状态是：$workStatus")
    }

    private fun initPara() {
        filterWifiEnable = SpManager.getBoolean(ScanDialog.SCAN_FILTER_WIFI_KEY, false)
        filterStationEnable = SpManager.getBoolean(ScanDialog.SCAN_FILTER_STATION_KEY, false)
        filterDisorderEnable = SpManager.getBoolean(ScanDialog.SCAN_FILTER_DISORDER_KEY, false)
        filterInterPhoneEnable = SpManager.getBoolean(ScanDialog.SCAN_FILTER_INTER_PHONE_KEY, false)
        filterOtherEnable = SpManager.getBoolean(ScanDialog.SCAN_FILTER_OTHER_2600_KEY, false)
    }

    override fun run() {
        Logger.i(
            "Processing loop started. (Thread: " + this.name + ")"
        )
        Logger.i("每hz应该分配的采样数：$perHzData")
        var startTime: Long // timestamp when signal processing is started
        var sleepTime: Long // time (in ms) to sleep before the next run to meet the frame rate
        var frequency: Long // center frequency of the incoming samples
        var sampleRate: Int // sample rate of the incoming samples
        while (!stopRequested) {
            // store the current timestamp
            startTime = System.currentTimeMillis()

            // 从队列中获取下一个样本:
            var samples: SamplePacket?
            try {
                samples = inputQueue!!.poll((1000 / frameRate).toLong(), TimeUnit.MILLISECONDS)
                if (samples == null) {
                    Logger.d("run: Timeout while waiting on input data. skip.")
                    continue
                }
            } catch (e: InterruptedException) {
                Logger.e("run: Interrupted while polling from input queue. stop.")
                stopLoop()
                break
            }
            frequency = samples.frequency
            sampleRate = samples.sampleRate
            preFrequency = if (preFrequency == 0L) frequency else preFrequency
            // 进行信号处理:
            doProcessing(samples)
            // 将样品返回缓冲池
            returnQueue!!.offer(samples)
            when (workStatus) {
                WORK_STATUS_COLLECT -> {
                    doCollecting(mag!!, frequency, sampleRate)
                }

                WORK_STATUS_SCAN -> {
                    doScanning(mag!!, frequency, sampleRate)
                }

                WORK_STATUS_DEFAULT -> {
                    // 把结果推到表面上去:
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        view.draw(mag!!, frequency, sampleRate, frameRate, load)
                    }
                    // 计算该帧的剩余时间(根据帧速率)，并在该时间内休眠:
                    sleepTime = 1000 / frameRate - (System.currentTimeMillis() - startTime)
//                    Logger.i("sleepTime:$sleepTime");
//                    try {
//                        if (sleepTime > 0) {
//                            // load = processing_time / frame_duration
//                            load = (System.currentTimeMillis() - startTime) / (1000.0 / frameRate)
//                            // Automatic frame rate control:
//                            if (isDynamicFrameRate && load < LOW_THRESHOLD && frameRate < MAX_FRAMERATE) frameRate++
//                            if (isDynamicFrameRate && load > HIGH_THRESHOLD && frameRate > 1) frameRate--
//
//                            //Logger.d(LOGTAG,"FrameRate: " + frameRate + ";  Load: " + load + "; Sleep for " + sleepTime + "ms.");
//                            sleep(sleepTime)
//                        } else {
//                            // Automatic frame rate control:
//                            if (isDynamicFrameRate && frameRate > 1) frameRate--
//
//                            //Logger.d("Couldn't meet requested frame rate!");
//                            load = 1.0
//                        }
//                    } catch (e: Exception) {
//                        Logger.e("Error while calling sleep()")
//                    }
                }

                else -> {

                }

            }
        }
        stopRequested = true
        Logger.i(
            "Processing loop stopped. (Thread: " + this.name + ")"
        )
    }

    private var preFrequency: Long = 0  //上一次的频率
    private var startTime: Long = 0
    private var endTime: Long = 0

    //每hz应该分配到的采样数
    private var perHzData = 4096 / (20 * 1000 * 1000f)
    private var collectCount = 0

    private var avMags = mutableListOf<MutableMap<Long, FloatArray>>()


    /**
     * 采集周围环境的
     */
    private fun doCollecting(mag: FloatArray, frequency: Long, rate: Int) {
        val curMag = FloatArray(fftSize)
        for (i in mag.indices) {
            if (mag[i] < -999) {
                curMag[i] = -20f
            } else {
                curMag[i] = mag[i]
            }
        }
        collectQueue[frequency] = curMag
        Logger.i("-----$frequency,$collectCount")
        if (frequency == START_FREQUENCY) {
            startTime = System.currentTimeMillis()
            Logger.i("扫描起点：$START_FREQUENCY,$collectCount")
        }
        val newFre = frequency + rate / 2
        if (newFre <= END_FREQUENCY) {
            if (newFre != preFrequency) {
                preFrequency = newFre
                mIQSourceInterface?.frequency = newFre
            }
        } else {
            collectCount++
            avMags.add(collectQueue)
            endTime = System.currentTimeMillis()
            Logger.i("跑完3Ghz总耗时:${endTime - startTime}")
            preFrequency = START_FREQUENCY
            mIQSourceInterface?.frequency = START_FREQUENCY
            MyApp.appViewModel.collectingProcessData.postValue(collectCount * 5)
            if (collectCount == 20) {

                for ((k, v) in collectQueue) {
                    var avMag = FloatArray(fftSize)
                    var frequency = k
                    for (j in 0 until fftSize) {
                        var db = 0f
                        var count = 0
                        for (i in 0 until avMags.size) {
                            val curCollectMap = avMags[i]
                            val curMag = curCollectMap[frequency]
                            curMag?.let {
                                count++
                                db += it[j]
                            }
                        }
                        // Logger.i("total:$db,$frequency")
                        avMag[j] = db / count
                    }
                    collectQueue[frequency] = avMag
                }
                collectCount = 0
                workStatus = WORK_STATUS_ERROR
                MyApp.appViewModel.workStatusData.postValue(workStatus)
            }
        }
    }


    /**
     * 进行扫频操作，与之前保存的进行对比
     */
    private fun doScanning(mag: FloatArray, frequency: Long, rate: Int) {
        if (frequency == START_FREQUENCY) {
            startTime = System.currentTimeMillis()
        }
        val newFre = frequency + rate
        //scanningMap[frequency] = mag
        val maxFrequency = findMaxByFor(mag)
        val mag = collectQueue[frequency]
        val maxValue = maxFrequency.maxValue
        val maxIndex = maxFrequency.maxIndex
        if (scanStatus == ScanDialog.SCAN_STATUS_PAUSE) {
            return
        }
        if (mag != null) {
            if (maxIndex < mag.size - 1) {
                val perValue = mag[maxIndex]
                if (perValue < -999) {
                    Logger.d("perMaxValue:$perValue,maxIndex:$maxIndex,frequency:$frequency")
                } else if (perValue + 8 < maxValue) {
                    val fre = maxIndex / perHzData
                    val abnormalFre = (frequency - rate / 2 + fre) / 1000 / 1000

                    if (abnormalFre > 700 && abnormalFre < 735) {
                        // 700mhz 上行 703-733mhz 758-788 下行
                        val msg = "700段上行异常信号：$abnormalFre Mhz,$maxValue,$perValue"
                        Logger.i("700段上行异常信号：$abnormalFre,$maxValue,$perValue,$maxIndex")
                        MyApp.appViewModel.scanMsgData.postValue(msg)
                    } else if (abnormalFre > 758 && abnormalFre < 788) {
                        if (!filterStationEnable) {
                            val msg = "基站下行信号：$abnormalFre Mhz,$maxValue,$perValue"
                            MyApp.appViewModel.scanMsgData.postValue(msg)
                        }
                        Logger.i("基站下行信号：$abnormalFre,$maxValue,$perValue,$maxIndex")
                    } else if (abnormalFre > 823 && abnormalFre < 838) {
                        // 850mhz 上行 825-835mhz  870-880mhz
                        val msg = "850段上行异常信号：$abnormalFre Mhz,$maxValue,$perValue"
                        MyApp.appViewModel.scanMsgData.postValue(msg)
                        Logger.i("850段上行异常信号：$abnormalFre,$maxValue,$perValue,$maxIndex")
                    } else if (abnormalFre > 870 && abnormalFre < 880) {
                        if (!filterStationEnable) {
                            val msg = "基站下行信号：$abnormalFre Mhz,$maxValue,$perValue"
                            MyApp.appViewModel.scanMsgData.postValue(msg)
                        }
                        Logger.i("基站下行信号：$abnormalFre,$maxValue,$perValue,$maxIndex")
                    } else if (abnormalFre > 885 && abnormalFre < 916) {
                        // 889_904_915 mhz 移动_联通 上行
                        val msg = "900段上行异常信号：$abnormalFre Mhz,$maxValue,$perValue"
                        MyApp.appViewModel.scanMsgData.postValue(msg)
                        Logger.i("900段上行异常信号：$abnormalFre,$maxValue,$perValue,$maxIndex")
                    } else if (abnormalFre > 934 && abnormalFre < 960) {
                        if (!filterStationEnable) {
                            val msg = "基站下行信号：$abnormalFre Mhz,$maxValue,$perValue"
                            MyApp.appViewModel.scanMsgData.postValue(msg)
                        }
                        Logger.i("基站下行信号：$abnormalFre,$maxValue,$perValue,$maxIndex")
                    } else if (abnormalFre > 1707 && abnormalFre < 1788) {
                        // 1710_1735_1765_1785  移动_联通_电信
                        val msg = "1800段上行异常信号：$abnormalFre Mhz,$maxValue,$perValue"
                        MyApp.appViewModel.scanMsgData.postValue(msg)
                        Logger.i("1800段上行异常信号：$abnormalFre,$maxValue,$perValue,$maxIndex")
                    } else if (abnormalFre > 1805 && abnormalFre < 1880) {
                        if (!filterStationEnable) {
                            val msg = "基站下行信号：$abnormalFre Mhz,$maxValue,$perValue"
                            MyApp.appViewModel.scanMsgData.postValue(msg)
                        }
                        Logger.i("基站下行信号：$abnormalFre,$maxValue,$perValue,$maxIndex")
                    } else if (abnormalFre > 2010 && abnormalFre < 2025) {
                        val msg = "2000段上行异常信号：$abnormalFre Mhz,$maxValue,$perValue"
                        MyApp.appViewModel.scanMsgData.postValue(msg)
                        Logger.i("2000段上行异常信号：$abnormalFre,$maxValue,$perValue,$maxIndex")
                    } else if (abnormalFre > 1880 && abnormalFre < 1965) {
                        // 1880_1920_1940_1965 上行信号
                        val msg = "1.9G上行异常信号：$abnormalFre Mhz,$maxValue,$perValue"
                        MyApp.appViewModel.scanMsgData.postValue(msg)
                        Logger.i("1.9G上行异常信号：$abnormalFre,$maxValue,$perValue,$maxIndex")
                    } else if (abnormalFre > 2110 && abnormalFre < 2155) {
                        if (!filterStationEnable) {
                            val msg = "基站下行信号：$abnormalFre Mhz,$maxValue,$perValue"
                            MyApp.appViewModel.scanMsgData.postValue(msg)
                        }
                        Logger.i("基站下行信号：$abnormalFre,$maxValue,$perValue,$maxIndex")
                    } else if (abnormalFre > 2297 && abnormalFre < 2367) {
                        // 2300_2320_2370
                        val msg = "2.3G异常信号：$abnormalFre Mhz,$maxValue,$perValue"
                        MyApp.appViewModel.scanMsgData.postValue(msg)
                        Logger.i("2.3G异常信号：$abnormalFre,$maxValue,$perValue,$maxIndex")
                    } else if (abnormalFre > 2512 && abnormalFre < 2678) {
                        if (!filterOtherEnable) {
                            // 2515_2675
                            val msg = "2.6G异常信号：$abnormalFre Mhz,$maxValue,$perValue"
                            MyApp.appViewModel.scanMsgData.postValue(msg)
                        }
                        Logger.d("2.6G异常信号：$abnormalFre,$maxValue,$perValue,$maxIndex,$filterOtherEnable")
                    } else if (abnormalFre > 2397 && abnormalFre < 2488) {
                        if (!filterWifiEnable) {
                            val msg = "wifi2.4G信号：$abnormalFre Mhz,$maxValue,$perValue"
                            MyApp.appViewModel.scanMsgData.postValue(msg)
                        }
                        Logger.d("wifi信号：$frequency,$maxValue,$perValue,$maxIndex,${abnormalFre} mhz，$filterWifiEnable")
                    } else if (abnormalFre < 800) {
                        if (!filterInterPhoneEnable) {
                            val msg = "疑似对讲机异常信号：$abnormalFre Mhz,$maxValue,$perValue"
                            MyApp.appViewModel.scanMsgData.postValue(msg)
                        }
                        Logger.i("疑似对讲机异常信号：${frequency / 1000 / 1000},$maxValue,$perValue,$maxIndex,${abnormalFre} mhz")
                    } else {
                        if (!filterDisorderEnable) {
                            val msg = "其他异常信号：$abnormalFre Mhz,$maxValue,$perValue"
                            MyApp.appViewModel.scanMsgData.postValue(msg)
                        }
                        Logger.i("其他异常信号：${frequency / 1000 / 1000},$maxValue,$perValue,$maxIndex,${abnormalFre} mhz")
                    }
                } else {
                    //  Logger.d("perMaxValue:$perValue,maxIndex:$maxIndex,frequency:$frequency")
                }
            }
        }
        if (newFre <= END_FREQUENCY) {
            if (newFre != preFrequency) {
                preFrequency = newFre
                mIQSourceInterface?.frequency = newFre
            }
        } else {
            endTime = System.currentTimeMillis()
            Logger.d("scanning time :${endTime - startTime}")
            //workStatus = 4
            preFrequency = START_FREQUENCY
            mIQSourceInterface?.frequency = START_FREQUENCY
        }

    }

    private var maxFre: MaxFrequency? = null
    private fun compareMaxFrequency(maxFrequency: MaxFrequency) {

    }


    /**
     * 找出列表的最大值和相应的索引
     */
    private fun findMaxByFor(mag: FloatArray): MaxFrequency {
        var maxValue = mag[0]
        var maxIndex = 0
        for (i in mag.indices) {
            if (mag[i] > maxValue) {
                maxValue = mag[i]
                maxIndex = i
            }
        }
        return MaxFrequency(maxValue, maxIndex)
    }


    /**
     * 该方法将对给定的样本进行信号处理(fft),
     *
     * @param samples 用于信号处理的输入样本
     */
    private fun doProcessing(samples: SamplePacket) {
        val re = samples.re()
        val im = samples.im()
        // 将样本与窗口函数相乘:
        fftBlock?.applyWindow(re, im)

        // 计算fft:
        fftBlock?.fft(re, im)
        //  Logger.i("采样的数据长度：${samples.size()}")
        // 计算对数量级:
        var realPower: Float
        var imagPower: Float
        val size = samples.size()
        for (i in 0 until size) {
            // 我们必须将fft的两侧翻转，以便将其绘制在屏幕中央:
            val targetIndex = (i + size / 2) % size

            // 计算 magnitude = log(  re^2 + im^2  )
            // 请注意，我们仍然需要将re和im除以FFT大小
            realPower = re[i] / fftSize
            realPower *= realPower
            imagPower = im[i] / fftSize
            imagPower *= imagPower
            // Math.sqrt(realPower + imagPower) 开平方根

            mag!![targetIndex] =
                (10 * log10(sqrt((realPower + imagPower).toDouble()))).toFloat()
        }
    }


    companion object {
        private const val MAX_FRAMERATE = 30 // 自动帧速率控制的上限
        private const val LOW_THRESHOLD = 0.65 // 在低于该阈值的每个负载值时，我们都会增加帧速率
        private const val HIGH_THRESHOLD = 0.85 //在每个负载值高于该阈值时，我们都会降低帧速率

        //
        const val WORK_STATUS_COLLECT = 1
        const val WORK_STATUS_SCAN = 2
        const val WORK_STATUS_DEFAULT = 3
        const val WORK_STATUS_ERROR = 4

    }
}
