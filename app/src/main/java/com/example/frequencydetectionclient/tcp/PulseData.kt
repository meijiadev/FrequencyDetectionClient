package com.example.frequencydetectionclient.tcp

import com.xuhao.didi.core.iocore.interfaces.IPulseSendable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset


class PulseData :IPulseSendable {
    private var data:String = "pulse"
    override fun parse(): ByteArray {
        val body: ByteArray = data.toByteArray(Charset.forName("UTF-8"))
        val bb = ByteBuffer.allocate(4 + body.size)
        bb.order(ByteOrder.BIG_ENDIAN)
        bb.putInt(body.size)
        bb.put(body)
        return bb.array()
    }
}