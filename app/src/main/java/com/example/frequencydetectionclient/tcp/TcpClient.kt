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
     * @param ip 服务器IP
     * @param port 服务器端口
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


    class SocketCallback : SocketActionAdapter() {
        override fun onSocketConnectionSuccess(info: ConnectionInfo?, action: String?) {
            super.onSocketConnectionSuccess(info, action)
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