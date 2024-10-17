package com.example.frequencydetectionclient.tcp

import com.orhanobut.logger.Logger
import com.xuhao.didi.core.iocore.interfaces.ISendable
import com.xuhao.didi.core.pojo.OriginalData
import com.xuhao.didi.socket.client.sdk.OkSocket
import com.xuhao.didi.socket.client.sdk.client.ConnectionInfo
import com.xuhao.didi.socket.client.sdk.client.OkSocketOptions
import com.xuhao.didi.socket.client.sdk.client.action.SocketActionAdapter
import com.xuhao.didi.socket.client.sdk.client.connection.IConnectionManager
import java.lang.Exception

/**
 * 长连接封装
 */
class TcpClient private constructor() {
    private var info: ConnectionInfo? = null
    private var manager: IConnectionManager? = null
    private var socketCallback: SocketCallback? = null



    companion object {
        val instance: TcpClient by lazy(mode = LazyThreadSafetyMode.SYNCHRONIZED) {
            TcpClient()
        }
    }


    /**
     * 创建长链接
     * @param ip 服务器IP 192.168.1.6
     * @param port 服务器端口 7099
     */
    fun createConnect(ip: String, port: Int) {
        info = ConnectionInfo(ip, port)
        manager = OkSocket.open(info)
        val clientOptions = OkSocketOptions.Builder()
        clientOptions.setPulseFeedLoseTimes(100)
        manager?.option(clientOptions.build())
        socketCallback = SocketCallback()
        manager?.registerReceiver(socketCallback)
    }

    /**
     * 断开长链接
     */
    fun disconnect() {
        manager?.unRegisterReceiver(socketCallback)
        manager?.disconnect()
        manager = null
        Logger.i("断开TCP链接")
    }

    private var pulseData: PulseData = PulseData()

    inner class SocketCallback : SocketActionAdapter() {
        override fun onSocketConnectionSuccess(info: ConnectionInfo?, action: String?) {
            super.onSocketConnectionSuccess(info, action)
            //链式编程调用,给心跳管理器设置心跳数据,一个连接只有一个心跳管理器,因此数据只用设置一次,如果断开请再次设置.
            OkSocket.open(info)
                .pulseManager
                .setPulseSendable(pulseData)  //只需要设置一次,下一次可以直接调用pulse()
                .pulse()    //开始心跳,开始心跳后,心跳管理器会自动进行心跳触发
        }

        override fun onSocketConnectionFailed(
            info: ConnectionInfo?,
            action: String?,
            e: Exception?
        ) {
            super.onSocketConnectionFailed(info, action, e)
        }

        override fun onSocketDisconnection(info: ConnectionInfo?, action: String?, e: Exception?) {
            super.onSocketDisconnection(info, action, e)
        }

        override fun onSocketReadResponse(
            info: ConnectionInfo?,
            action: String?,
            data: OriginalData?
        ) {
            //遵循以上规则,这个回调才可以正常收到服务器返回的数据,数据在OriginalData中,为byte[]数组,该数组数据已经处理过字节序问题,直接放入ByteBuffer中即可使用

        }

        override fun onSocketWriteResponse(
            info: ConnectionInfo?,
            action: String?,
            data: ISendable?
        ) {
            super.onSocketWriteResponse(info, action, data)
        }

    }
}