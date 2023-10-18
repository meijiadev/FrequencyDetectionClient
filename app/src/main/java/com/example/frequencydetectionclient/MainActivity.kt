package com.example.frequencydetectionclient

import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Bundle
import android.os.PersistableBundle
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.frequencydetectionclient.databinding.ActivityMainBinding
import com.example.frequencydetectionclient.hackrf.HackrfSource
import com.example.frequencydetectionclient.iq.IQSourceInterface
import com.example.frequencydetectionclient.iq.RFControlInterface
import com.example.frequencydetectionclient.manager.SpManager
import com.example.frequencydetectionclient.thread.AnalyzerProcessingLoop
import com.example.frequencydetectionclient.thread.Demodulator
import com.example.frequencydetectionclient.thread.Scheduler
import com.example.frequencydetectionclient.view.AnalyzerSurface
import com.orhanobut.logger.Logger
import java.io.File


class MainActivity : AppCompatActivity(), IQSourceInterface.Callback, RFControlInterface {
    companion object {
        //const val SP_SOURCE_TYPE_KEY = "sp_source_type_key"

        const val SP_RTL_SDR_EXTERNAL_SERVER_KEY = "sp_rtl_sdr_external_server_key"

        const val SP_AUTO_START_KEY = "sp_auto_start_key"

        const val SP_FILE_FREQUENCY_KEY = "sp_file_frequency_key"
        const val SP_FREQUENCY_KEY = "sp_frequency_key"

        const val SP_FILE_SAMPLE_RATE_KEY = "sp_file_sample_rate_key"
        const val SP_HACK_SAMPLE_RATE_KEY = "sp_hack_sample_rate_key"
        const val SP_RTL_SAMPLE_RATE_KEY = "sp_rtl_sample_rate_key"

        const val SP_HACK_RF_VGA_RX_GAIN_KEY = "sp_hack_rf_vga_rx_gain_key"
        const val SP_HACK__RF_LNA_GAIN_KEY = "sp_hack_rf_lna_gain_key"
        const val SP_HACK_RF_AMPLIFIER_KEY = "sp_hack_rf_amplifier_key"
        const val SP_HACK_RF_ANTENNA_POWER_KEY = "sp_hack_rf_antenna_power_key"
        const val SP_HACK_RF_FREQUENCY_OFFSET_KEY = "sp_hack_rf_frequency_offset_key"


        const val SOURCE_FILE_VALUE = 0
        const val SOURCE_HACK_RF_VALUE = 1
        const val SOURCE_RTL_SDR_VALUE = 2

        const val SP_FFT_SIZE_KEY = "sp_fft_size_key"
        const val SP_FRAME_RATE_KEY = "sp_frame_rate_key"
        const val SP_DYNAMIC_FRAME_RATE = "sp_dynamic_rate_key"

        // bundle
        const val STATE_SAVE_RUNNING = "save_state_running"
        const val STATE_SAVE_DEMODULATOR_MODE = "save_state_demodulator_mode"

    }

    private var source: IQSourceInterface? = null
    private var running = false
    private var scheduler: Scheduler? = null
    private var analyzerProcessingLoop: AnalyzerProcessingLoop? = null
    private var analyzerSurface: AnalyzerSurface? = null
    private var demodulator: Demodulator? = null

    private var recordingFile: File? = null

    // 解调器模式
    private var demodulationMode = Demodulator.DEMODULATION_OFF

    private var workStatus: Int = AnalyzerProcessingLoop.WORK_STATUS_COLLECT

    private lateinit var viewBinding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(viewBinding.root)

        // 恢复/初始化运行状态和解调器模式
        if (savedInstanceState != null) {
            running = savedInstanceState.getBoolean(STATE_SAVE_RUNNING)
            demodulationMode = savedInstanceState.getInt(STATE_SAVE_DEMODULATOR_MODE)
        } else {
            running = SpManager.getBoolean(SP_AUTO_START_KEY, false)
        }

        volumeControlStream = AudioManager.STREAM_MUSIC

        onClick()
        Logger.i("启动频率侦测仪器")
    }

    private fun onClick() {
        viewBinding.tvCollect.setOnClickListener {
            Logger.i("进入采集程序")
            workStatus = AnalyzerProcessingLoop.WORK_STATUS_COLLECT
            startAnalyzer()
        }

        viewBinding.tvDetection.setOnClickListener {
            Logger.i("进入侦测页面")
            workStatus = AnalyzerProcessingLoop.WORK_STATUS_SCAN
        }

    }

    /**
     * 根据用户设置创建IQSource 实例
     * @return true success l;false error
     */
    private fun createSource(): Boolean {
        // 频率
        var frequency =
            SpManager.getLong(SP_FREQUENCY_KEY, 30 * 1000 * 1000L)    // 30Mhz
        // 采样率
        var sampleRate = SpManager.getInt(SP_HACK_SAMPLE_RATE_KEY, 20 * 1000 * 1000)   // 20Mhz
        Logger.i("frequency:$frequency;------sampleRate:$sampleRate")
        if (workStatus == AnalyzerProcessingLoop.WORK_STATUS_COLLECT) {
            frequency = 40 * 1000 * 1000L           //40Mhz
            sampleRate = 20 * 1000 * 1000           // 20Mhz
        }
        source = HackrfSource()
        source?.let {
            source?.frequency = frequency
            source?.sampleRate = sampleRate
        }
        (source as HackrfSource).let {
            it.vgaRxGain =
                SpManager.getInt(
                    SP_HACK_RF_VGA_RX_GAIN_KEY,
                    HackrfSource.MAX_VGA_RX_GAIN / 2
                )
            it.lnaGain =
                SpManager.getInt(SP_HACK__RF_LNA_GAIN_KEY, HackrfSource.MAX_LNA_GAIN / 2)
            it.setAmplifier(SpManager.getBoolean(SP_HACK_RF_AMPLIFIER_KEY, false))
            it.setAntennaPower(SpManager.getBoolean(SP_HACK_RF_ANTENNA_POWER_KEY, false))
            it.frequencyOffset = SpManager.getInt(SP_HACK_RF_FREQUENCY_OFFSET_KEY, 0)
        }
        // inform the analyzer surface about the new source
        analyzerSurface?.setSource(source)
        return true

    }

    /**
     * 打开IQSOURCE实例，注意有些源在打开时需要特殊处理，比如 rtl-sdr源
     * @return true success false error
     */
    private fun openSource(): Boolean {
        return if (source != null && source is HackrfSource) {
            source!!.open(this, this)
        } else {
            Logger.e("openSource: sourceType is SOURCE_HACK_RF_VALUE,but source is null or of other type.")
            false
        }
    }

    /**
     * 停止射频分析，包括关闭调度器，处理循环和解调器
     */
    private fun stopAnalyzer() {
        // stop the scheduler if running:
        if (scheduler != null) {
            // stop recording in case it is running:
            // stopRecording();
            scheduler?.stopScheduler()
        }
        // stop the processing loop if running:
        analyzerProcessingLoop?.stopLoop()

        // stop the demodulator if running:
        demodulator?.stopDemodulator()

        // wait for the scheduler to stop
        if (scheduler != null && !scheduler?.name.equals(Thread.currentThread().name)) {
            try {
                scheduler?.join()
            } catch (e: InterruptedException) {
                Logger.e("startAnalyzer: Error while stopping Scheduler.")
            }
        }

        // wait for the processing loop to stop
        if (analyzerProcessingLoop != null) {
            try {
                analyzerProcessingLoop?.join()
            } catch (e: InterruptedException) {
                Logger.e("startAnalyzer: Error while stopping Processing Loop.")

            }
        }

        if (demodulator != null) {
            try {
                demodulator?.join()
            } catch (e: InterruptedException) {
                Logger.e("startAnalyzer: Error while stopping Demodulator.")
            }
        }

        running = false

        // 允许屏幕再次关闭
        runOnUiThread {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

    }

    private fun startAnalyzer() {
        stopAnalyzer() //运行时停止，这确保我们不会以多个线程循环
        //
        val fftSize = SpManager.getInt(SP_FFT_SIZE_KEY, 4096)
        val frameRate = SpManager.getInt(SP_FRAME_RATE_KEY, 10)
        val dynamicFrameRate = SpManager.getBoolean(SP_DYNAMIC_FRAME_RATE, true)
        Logger.i("fftSize:$fftSize;--frameRate:$frameRate;--dynamicFrameRate:$dynamicFrameRate")
        running = true

        if (source == null) {
            // 如果创建source为false 则直接结束这个方法
            if (!createSource()) {
                Logger.e("startAnalyzer error")
                return
            }
        }

        // 如果源没有打开则打开它
        if (false == source?.isOpen) {
            if (!openSource()) {
                Toast.makeText(this, "hack rf 来源不可用", Toast.LENGTH_SHORT).show()
                running = false
                return
            }
            return           //如果执行打开消息源的操作需要结束当前方法，在onIQSourceReady()中将再次调用startAnalyzer()
        }

        // 创建scheduler 和processingLoop的新实例
        scheduler = Scheduler(fftSize, source)
        analyzerProcessingLoop = AnalyzerProcessingLoop(
            //   analyzerSurface!!,          // 分析仪绘制实时数据曲线的view
            fftSize,                    // 快速傅里叶变换采样数
            scheduler?.fftOutputQueue,  // 对处理循环输入队列的引用
            scheduler?.fftInputQueue,   // 对缓冲池返回队列的引用
            source!!
        )

        if (dynamicFrameRate) {
            analyzerProcessingLoop?.isDynamicFrameRate = true
        } else {
            analyzerProcessingLoop?.isDynamicFrameRate = false
            analyzerProcessingLoop?.frameRate = frameRate
        }
        analyzerProcessingLoop?.setWorkStatus(workStatus)
        //启动两个线程
        scheduler?.start()
        analyzerProcessingLoop?.start()
        // ????
        //scheduler?.channelFrequency = analyzerSurface!!.getChannelFrequency()

        // 启动解调器线程
        demodulator =
            Demodulator(
                scheduler?.demodOutputQueue,
                scheduler?.demodInputQueue,
                source!!.packetSize
            )
        demodulator?.start()

        // 设置解调器模式（将正确配置解调器）
        setDemodulationMode(demodulationMode)

        // 防止屏幕关闭:
        runOnUiThread { window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    /**
     * 将调制模式设置为给定值，负责分别调整调度器和解调器
     */
    private fun setDemodulationMode(mode: Int) {
        var mMode = mode
        if (scheduler == null || demodulator == null || source == null) {
            Logger.e("setDemodulationMode: scheduler/demodulator/source is null")
            return
        }

        if (mMode == Demodulator.DEMODULATION_OFF) {
            scheduler?.isDemodulationActivated = false
        } else {
            if (recordingFile != null && source?.sampleRate != Demodulator.INPUT_RATE) {
                //我们现在正在以不兼容的采样率进行记录。
                Logger.i("setDemodulationMode：录制正在运行 " + source?.sampleRate + " Sps。无法启动解调。")
                runOnUiThread {
                    Toast.makeText(
                        this,
                        "正在以不兼容的采样率运行录制以进行解调！",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                return
            }

            // 调整源的采样率
            source?.sampleRate = Demodulator.INPUT_RATE

            // 验证源是否支持采样率
            if (source?.sampleRate != Demodulator.INPUT_RATE) {
                Logger.e("setDemodulationMode:无法调整源采样率")
                Toast.makeText(
                    this,
                    "源不支持解调所需的采样率（:${Demodulator.INPUT_RATE / 1000000} Msps",
                    Toast.LENGTH_SHORT
                ).show()
                scheduler?.isDemodulationActivated = false
                mMode = Demodulator.DEMODULATION_OFF    // 停用解调。。。
            } else {
                scheduler?.isDemodulationActivated = true

            }
        }

        demodulator?.setDemodulationMode(mMode)
        demodulationMode = mMode // 保存设置

        // 禁用/启用曲面中的解调试图
        if (mode == Demodulator.DEMODULATION_OFF) {
            analyzerSurface?.setDemodulationEnabled(false)
        } else {
            analyzerSurface?.setDemodulationEnabled(true)    //将重新调整信道频率、宽度和静噪
            // 如果它们在当前视口之外，则更新调解器
            analyzerSurface?.setShowLowerBand(mMode != Demodulator.DEMODULATION_USB)  // 如果不是USB，则显示下侧带
            analyzerSurface?.setShowUpperBand(mode != Demodulator.DEMODULATION_LSB)   // 如果不是LSB，则显示上侧带
        }


    }

    override fun onStart() {
        super.onStart()
        // 检查用户是否更改了首选项
        checkForChangedPreferences()
        if (running) {
            startAnalyzer()
        }

    }


    override fun onStop() {
        super.onStop()
        val runningSaved = running
        stopAnalyzer()
        running = runningSaved
        if (source != null) {
            SpManager.putLong(SP_FREQUENCY_KEY, source!!.frequency)
            SpManager.putInt(SP_HACK_SAMPLE_RATE_KEY, source!!.sampleRate)
        }
    }


    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_SAVE_RUNNING, running)
        outState.putInt(STATE_SAVE_DEMODULATOR_MODE, demodulationMode)
        if (analyzerSurface != null) {

        }

    }

    override fun onDestroy() {
        super.onDestroy()
        // close source
        if (source != null && true == source?.isOpen) {
            source?.close()
        }
    }

    /**
     *  将检查是否有任何偏好与应用程序的当前状态冲突并修复它
     */
    private fun checkForChangedPreferences() {
        if (source !is HackrfSource) {
            source?.close()
            createSource()
        } else {
            val amp = SpManager.getBoolean(SP_HACK_RF_AMPLIFIER_KEY, false)
            val antennaPower = SpManager.getBoolean(SP_HACK_RF_ANTENNA_POWER_KEY, false)
            val frequencyOffset = SpManager.getInt(SP_HACK_RF_FREQUENCY_OFFSET_KEY, 0)
            (source as HackrfSource).let {
                if (it.isAmplifierOn != amp) it.setAmplifier(amp)
                if (it.isAntennaPowerOn != antennaPower) it.setAntennaPower(antennaPower)
                if (it.frequencyOffset != frequencyOffset)
                    it.frequencyOffset = frequencyOffset
            }
        }
        if (analyzerSurface != null) {
            // ?????
        }
    }

    override fun onIQSourceReady(source: IQSourceInterface?) { // is called after source.open()
        if (running) {
            startAnalyzer()                                    // will start the processing loop,scheduler and source
        }
    }

    override fun onIQSourceError(source: IQSourceInterface?, message: String?) {
        Logger.e("Error with Source:$message")
        stopAnalyzer()
        if (source != null && source.isOpen) {
            source.close()
        }
    }

    override fun updateDemodulationMode(newDemodulationMode: Int): Boolean {
        if (scheduler == null || demodulator == null || source == null) {
            Logger.e("updateDemodulationMode: scheduler/demodulator/source is null (no demodulation running)")
            return false
        }
        setDemodulationMode(newDemodulationMode)
        return true
    }

    override fun updateChannelWidth(newChannelWidth: Int): Boolean {
        if (demodulator != null) {
            if (demodulator?.setChannelWidth(newChannelWidth) == true) {
                analyzerSurface?.channelWidth = newChannelWidth
                return true
            }
        }
        Logger.i("newChannelWidth：$newChannelWidth")
        return false
    }

    override fun updateChannelFrequency(newChannelFrequency: Long): Boolean {
        if (scheduler != null) {
            scheduler?.channelFrequency = newChannelFrequency
            analyzerSurface?.setChannelFrequency(newChannelFrequency)
            return true
        }
        return false
    }

    override fun updateSourceFrequency(newSourceFrequency: Long): Boolean {
        if (source != null && newSourceFrequency <= source!!.maxFrequency && newSourceFrequency >= source!!.minFrequency) {
            source?.frequency = newSourceFrequency
            analyzerSurface?.virtualFrequency = newSourceFrequency
            return true
        }
        return false
    }

    override fun updateSampleRate(newSampleRate: Int): Boolean {
        if (source != null) {
            if (scheduler == null || false == scheduler?.isRecording) {
                source!!.sampleRate = newSampleRate
                return true
            }
        }
        return false
    }

    override fun updateSquelch(newSquelch: Float) {
        analyzerSurface?.setSquelch(newSquelch)
    }

    override fun updateSquelchSatisfied(squelchSatisfied: Boolean): Boolean {
        if (scheduler != null) {
            scheduler?.setSquelchSatisfied(squelchSatisfied)
            return true
        }
        return false
    }

    override fun requestCurrentChannelWidth(): Int {
        return if (demodulator != null) {
            demodulator!!.channelWidth
        } else {
            -1
        }
    }

    override fun requestCurrentChannelFrequency(): Long {
        return if (scheduler != null) {
            return scheduler!!.channelFrequency
        } else {
            -1
        }
    }

    override fun requestCurrentDemodulationMode(): Int {
        return demodulationMode
    }

    override fun requestCurrentSquelch(): Float {
        return if (analyzerSurface != null) {
            analyzerSurface!!.getSquelch()
        } else {
            Float.NaN
        }
    }

    override fun requestCurrentSourceFrequency(): Long {
        return if (source != null) {
            source!!.frequency
        } else {
            -1
        }
    }

    override fun requestCurrentSampleRate(): Int {
        return if (source != null) {
            source!!.sampleRate
        } else {
            return -1
        }
    }

    override fun requestMaxSourceFrequency(): Long {
        return if (source != null) {
            source!!.maxFrequency
        } else {
            -1
        }
    }

    override fun requestSupportedSampleRates(): IntArray? {
        return if (source != null) {
            source!!.supportedSampleRates
        } else {
            null
        }
    }


}