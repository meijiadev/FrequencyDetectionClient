package com.example.frequencydetectionclient

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import com.example.frequencydetectionclient.manager.SpManager
import com.example.frequencydetectionclient.viewModel.AppViewModel
import com.orhanobut.logger.AndroidLogAdapter
import com.orhanobut.logger.Logger


class MyApp : Application(), ViewModelStoreOwner {
    /**
     * 作用域为Application的ViewModel，整个应用程序周期（用于在不同的Activity之间传递数据）
     */
    private lateinit var mApplicationProvider: ViewModelProvider

    private lateinit var mAppViewModelStore: ViewModelStore

    override fun onCreate() {
        super.onCreate()
        mAppViewModelStore = ViewModelStore()
        mApplicationProvider = ViewModelProvider(this)
        SpManager.init(this)
        Logger.addLogAdapter(AndroidLogAdapter())
        Log.i("MyApp", "启动初始化")
        initViewModel()
        appContext = this
    }

    private fun initViewModel() {
        appViewModel = getApplicationViewModel(AppViewModel::class.java)
    }

    /**
     * 获取作用域在Application的ViewModel对象
     */
    private fun <T : ViewModel> getApplicationViewModel(modelClass: Class<T>): T {
        return mApplicationProvider.get(modelClass)
    }

    override fun getViewModelStore(): ViewModelStore {
        return mAppViewModelStore
    }

    companion object {
        lateinit var appViewModel: AppViewModel
        lateinit var appContext: Context
    }
}