package com.example.frequencydetectionclient.viewModel

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

class AppViewModel : ViewModel() {
    // 当前信号接收器的工作状态
    var workStatusData = MutableLiveData<Int>()

    // 监听采集的进度
    var collectingProcessData = MutableLiveData<Int>()

    // 扫描的频率结果
    var scanMsgData = MutableLiveData<String>()

    // 扫描的
    var scanStatusData = MutableLiveData<Int>()

    // wifi 过滤
    var wifiFilterData = MutableLiveData<Boolean>()

    // 基站
    var stationFilterData = MutableLiveData<Boolean>()

    // 无序信号
    var disorderFilterData = MutableLiveData<Boolean>()

    // 过滤对讲机广播的信号
    var interPhoneFilterData = MutableLiveData<Boolean>()

    // 2.6G频段基站发出的信号
    var otherFilterData = MutableLiveData<Boolean>()
    // 找到异常的信号
    var alarmFrequencyData = MutableLiveData<Long>()

    // 是否开启解调器
    var demodulationEnableData=MutableLiveData<Boolean>()
}