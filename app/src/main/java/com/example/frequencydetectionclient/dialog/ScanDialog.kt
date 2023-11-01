package com.example.frequencydetectionclient.dialog

import android.content.Context
import android.text.method.ScrollingMovementMethod
import android.widget.CheckBox
import androidx.appcompat.widget.AppCompatTextView
import com.example.frequencydetectionclient.MyApp
import com.example.frequencydetectionclient.R
import com.example.frequencydetectionclient.manager.SpManager
import com.hjq.shape.view.ShapeButton
import com.hjq.shape.view.ShapeTextView
import com.lxj.xpopup.core.CenterPopupView
import com.orhanobut.logger.Logger

/**
 * desc: 扫描的窗口
 * time：2023/10/28
 * author: mei jia
 */
class ScanDialog(context: Context) : CenterPopupView(context) {
    private val tvTitle: AppCompatTextView by lazy { findViewById(R.id.tv_scan_title) }
    private val tvScan: ShapeTextView by lazy { findViewById(R.id.tv_scan) }
    private val btClear: ShapeButton by lazy { findViewById(R.id.bt_clear) }
    private val btPause: ShapeButton by lazy { findViewById(R.id.bt_pause) }
    private val btStop: ShapeButton by lazy { findViewById(R.id.bt_stop) }

    private val cbWifi: CheckBox by lazy { findViewById(R.id.cb_wifi) }
    private val cbDisorder: CheckBox by lazy { findViewById(R.id.cb_disorder) }
    private val cbStation: CheckBox by lazy { findViewById(R.id.cb_station) }
    private val cbInterPhone: CheckBox by lazy { findViewById(R.id.cb_inter_phone) }
    private val cbOtherFilter: CheckBox by lazy { findViewById(R.id.cb_other_filter) }


    companion object {
        const val SCAN_STATUS_START = 1
        const val SCAN_STATUS_PAUSE = 2
        const val SCAN_STATUS_STOP = 3
        const val SCAN_STATUS_WORK = 4

        const val SCAN_FILTER_WIFI_KEY = "scan_filter_wifi_key"

        const val SCAN_FILTER_DISORDER_KEY = "scan_filter_disorder_key"

        const val SCAN_FILTER_STATION_KEY = "scan_filter_station_key"

        const val SCAN_FILTER_INTER_PHONE_KEY = "scan_filter_inter_phone_key"

        // 2600段
        const val SCAN_FILTER_OTHER_2600_KEY = "scan_filter_other_2600_key"
    }

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

    override fun getImplLayoutId(): Int = R.layout.dialog_center_scan

    override fun onCreate() {
        super.onCreate()
        MyApp.appViewModel.scanMsgData.observe(this) {
            it?.let {
                val text = tvScan.text.toString()
                tvScan.text = text + "\n" + it
                refreshLogView()
            }
        }


    }

    override fun init() {
        super.init()
        onClick()
        tvScan.movementMethod = ScrollingMovementMethod.getInstance()
        filterWifiEnable = SpManager.getBoolean(SCAN_FILTER_WIFI_KEY, false)
        filterStationEnable = SpManager.getBoolean(SCAN_FILTER_STATION_KEY, false)
        filterDisorderEnable = SpManager.getBoolean(SCAN_FILTER_DISORDER_KEY, false)
        filterInterPhoneEnable = SpManager.getBoolean(SCAN_FILTER_INTER_PHONE_KEY, false)
        filterOtherEnable = SpManager.getBoolean(SCAN_FILTER_OTHER_2600_KEY, false)
        cbInterPhone.isChecked = filterInterPhoneEnable
        cbDisorder.isChecked = filterDisorderEnable
        cbWifi.isChecked = filterWifiEnable
        cbStation.isChecked = filterStationEnable
        cbOtherFilter.isChecked = filterOtherEnable
        MyApp.appViewModel.wifiFilterData.postValue(filterWifiEnable)
        MyApp.appViewModel.stationFilterData.postValue(filterStationEnable)
        MyApp.appViewModel.disorderFilterData.postValue(filterDisorderEnable)
        MyApp.appViewModel.interPhoneFilterData.postValue(filterInterPhoneEnable)
        Logger.e("扫描弹窗初始化...")
    }


    private fun onClick() {
        btClear.setOnClickListener {
            tvScan.text = ""
        }

        btPause.setOnClickListener {
            if (btPause.text.toString() == "暂停") {
                MyApp.appViewModel.scanStatusData.postValue(SCAN_STATUS_PAUSE)
                btPause.text = "继续"
                tvTitle.text = "频段侦测暂停中..."
            } else {
                btPause.text = "暂停"
                MyApp.appViewModel.scanStatusData.postValue(SCAN_STATUS_START)
                tvTitle.text = "频段侦测中..."
            }
        }

        btStop.setOnClickListener {
            MyApp.appViewModel.scanStatusData.postValue(SCAN_STATUS_STOP)
            dismiss()
        }

        cbWifi.setOnCheckedChangeListener { _, isChecked ->
            MyApp.appViewModel.wifiFilterData.postValue(isChecked)
        }

        cbStation.setOnCheckedChangeListener { _, isChecked ->
            MyApp.appViewModel.stationFilterData.postValue(isChecked)
        }

        cbDisorder.setOnCheckedChangeListener { _, isChecked ->
            MyApp.appViewModel.disorderFilterData.postValue(isChecked)
        }

        cbInterPhone.setOnCheckedChangeListener { _, isChecked ->
            MyApp.appViewModel.interPhoneFilterData.postValue(isChecked)
        }

        cbOtherFilter.setOnCheckedChangeListener { _, isChecked ->
            MyApp.appViewModel.otherFilterData.postValue(isChecked)
        }




    }

    private fun refreshLogView() {
        val offset = tvScan.lineCount * tvScan.lineHeight
        if (offset > tvScan.height) {
            tvScan.scrollTo(0, offset - tvScan.height)
        }
    }
}