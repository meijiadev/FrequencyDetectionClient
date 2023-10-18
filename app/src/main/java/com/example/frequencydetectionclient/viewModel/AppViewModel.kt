package com.example.frequencydetectionclient.viewModel

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

class AppViewModel : ViewModel() {
    // 当前信号接收器的工作状态
    var workStatusData = MutableLiveData<Int>()



}