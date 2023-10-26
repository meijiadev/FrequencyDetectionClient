package com.example.frequencydetectionclient.bean

/**
 * des:从每一组中找出最大的信号强度值和对应的索引
 * @param maxValue 最大信号值
 * @param maxIndex 最大信号值对应的索引在列表中
 */
data class MaxFrequency(val maxValue:Float,val maxIndex:Int)