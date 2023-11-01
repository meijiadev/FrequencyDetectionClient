package com.example.frequencydetectionclient.bean

data class FrequencyData(val mag: FloatArray, val frequency: Long, val rate: Int) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as FrequencyData

        if (!mag.contentEquals(other.mag)) return false
        if (frequency != other.frequency) return false
        if (rate != other.rate) return false

        return true
    }

    override fun hashCode(): Int {
        var result = mag.contentHashCode()
        result = 31 * result + frequency.hashCode()
        result = 31 * result + rate.hashCode()
        return result
    }
}
