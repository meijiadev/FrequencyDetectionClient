package com.example.frequencydetectionclient.dialog

import android.content.Context
import android.view.View
import android.widget.TextView
import com.example.frequencydetectionclient.MyApp
import com.example.frequencydetectionclient.R
import com.example.frequencydetectionclient.view.AttendanceRateView
import com.lxj.xpopup.core.CenterPopupView
import com.orhanobut.logger.Logger

/**
 * desc: 信号采集的额进度弹窗
 * time：2023/10/28
 * author： mei jia
 */
class CollectingDialog(context: Context) : CenterPopupView(context) {
    private val process: AttendanceRateView by lazy { findViewById(R.id.process) }
    private val tvStart: TextView by lazy { findViewById(R.id.tv_start) }
    override fun onCreate() {
        super.onCreate()
        process.setOnClickListener {
            process.animatePercentage(0)
            Logger.i("点击process")
        }
        tvStart.setOnClickListener {
            process.animatePercentage(100)
        }
        // process?.animatePercentage(100)


        MyApp.appViewModel.collectingProcessData.observe(this) {
            it?.let {
                process.animatePercentage(it)
            }
        }

        MyApp.appViewModel.workStatusData.observe(this) {
            if (it == 4) {
                dismiss()

            }
        }
    }

    override fun getImplLayoutId(): Int = R.layout.dialog_center_collect


}