package com.example.frequencydetectionclient.manager

import android.content.Context
import com.orhanobut.logger.Logger
import com.tencent.mmkv.MMKV


/**
 *    author : MJ
 *    time   : 2022/02/09
 *    desc   : 使用MMkv存储数据
 */
class SpManager {

    companion object {
        private lateinit var kv: MMKV

        fun init(context: Context) {
            // MMKV 初始化
            val rootDir: String = MMKV.initialize(context)
            Logger.i(rootDir)
            kv = MMKV.defaultMMKV()
        }

        /**
         * 存储字符串
         * @param key 键
         * @param value 值
         */
        fun putString(key: String, value: String?) {
            kv.encode(key, value)
        }

        fun putBoolean(key: String, value: Boolean) {
            kv.encode(key, value)
        }

        fun getBoolean(key: String, defaultValue: Boolean): Boolean {
            return kv.decodeBool(key, defaultValue)
        }

        /**
         * 获取字符串数据
         * @param key 存储时的键
         * @param defaultValue 如果没有获取到数据显示默认字符串
         */
        fun getString(key: String, defaultValue: String): String? {
            return kv.decodeString(key, defaultValue)
        }


        /**
         * 获取字符串数据
         * @param key 存储时的键
         */
        fun getString(key: String): String? {
            return kv.decodeString(key, "")
        }

        /**
         * 存储字符串
         * @param key 键
         * @param value 值
         */
        fun putInt(key: String, value: Int) {
            kv.encode(key, value)
        }

        /**
         * 获取字符串数据
         * @param key 存储时的键
         * @param defaultValue 如果没有获取到数据显示默认value
         */
        fun getInt(key: String, defaultValue: Int): Int {
            return kv.decodeInt(key, defaultValue)
        }

        /**
         * 获取字符串数据
         * @param key 存储时的键
         */
        fun getInt(key: String): Int {
            return kv.decodeInt(key, 0)
        }

        /**
         * 存储Long型数据
         * @param key 键
         * @param value 值
         */
        fun putLong(key: String, value: Long) {
            kv.encode(key, value)
        }

        /**
         * 获取Long型数
         * @param key 存储时的值
         */
        fun getLong(key: String): Long {
            return kv.decodeLong(key)
        }

        /**
         * 获取Long型数据
         * @param key
         * @param defaultValue 默认的值，如果没有获取到存储的值，就返回这个默认值
         */
        fun getLong(key: String, defaultValue: Long): Long {
            return kv.decodeLong(key, defaultValue)
        }



    }

}