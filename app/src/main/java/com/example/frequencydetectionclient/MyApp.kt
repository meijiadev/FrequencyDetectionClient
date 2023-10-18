package com.example.frequencydetectionclient

import android.app.Application
import android.util.Log
import com.example.frequencydetectionclient.manager.SpManager
import com.orhanobut.logger.AndroidLogAdapter
import com.orhanobut.logger.Logger


class MyApp : Application() {

    override fun onCreate() {
        super.onCreate()
        SpManager.init(this)
        Logger.addLogAdapter(AndroidLogAdapter())
        Log.i("MyApp","启动初始化")
    }
}