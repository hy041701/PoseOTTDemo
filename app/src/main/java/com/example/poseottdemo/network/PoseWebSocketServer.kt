package com.example.poseottdemo.network

import android.util.Log
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress
import org.json.JSONObject
import com.example.poseottdemo.protocol.PoseJsonParser
import com.example.poseottdemo.model.PoseFrameData

class PoseWebSocketServer(
    private val serverPort: Int,
    private val listener: ServerListener
) : WebSocketServer(InetSocketAddress(serverPort)) {
    companion object { private const val TAG = "PoseWsServer" }
    @Volatile
    private var activeClient: WebSocket? = null

    interface ServerListener {
        fun onServerStarted()
        fun onClientConnected()
        fun onClientDisconnected()
        fun onMessageReceived(message: String)
        // hello / hello_ack 成功后通知 Activity
        fun onPairingSucceeded()
        fun onReadyCheckPassed()
        fun onPhoneExitedDetection()
        fun onPoseFrame(frame: PoseFrameData)
        fun onServerError(error: String)
    }

    //WebSocket Server成功启动以后调用
    override fun onStart() {
        listener.onServerStarted()
    }

    //手机连接OTT以后调用
    override fun onOpen(
        conn: WebSocket,
        handshake: ClientHandshake
    ) {
        val oldClient = activeClient
        if (oldClient != null && oldClient != conn && oldClient.isOpen) {
            activeClient = conn
            oldClient.close(1000, "Replaced by new phone")
        } else {
            activeClient = conn
        }
        listener.onClientConnected()
    }

    //收到消息就会被调用
    override fun onMessage(
        conn: WebSocket?,
        message: String?
    ) {
        if (message == null) { return }
        try {
            // 把收到的字符串解析成JSON
            val json = JSONObject(message)
            // 获取消息类型
            val type = json.optString("type")
            when (type) {
                // 手机刚刚建立连接
                "hello" -> {
                    Log.i(TAG, "Received HELLO")
                    // HELLO到达以后， 再次确认这就是当前真正完成配对的客户端
                    if (conn != null) { activeClient = conn }
                    val response = JSONObject()
                    response.put("type", "hello_ack")
                    response.put("status", "ok")
                    response.put("version", 1)
                    conn?.send(response.toString())
                    //再告诉MainActivity： 手机已经真正配对成功
                    listener.onPairingSucceeded()
                }
                //动作检测准备
                "ready_check_passed" -> {
                    Log.i(TAG, "Phone ready check passed")
                    listener.onReadyCheckPassed()
                }
                //手机退出检测
                "phone_exit_detection" -> {
                    Log.i(TAG, "Phone exited detection")
                    listener.onPhoneExitedDetection()
                }
                // 手机发送姿态数据
                "pose" -> {
                    val poseFrame = PoseJsonParser.parse(message)
                    if (poseFrame == null) {
                        Log.e(TAG, "POSE parse failed")
                        return
                    }
                    val personCount = poseFrame.persons.size
//                    Log.d(TAG, "Received POSE: " + "frameId=${poseFrame.frameId}, " + "timestamp=${poseFrame.timestampMs}, " + "persons=$personCount")
                    if (personCount > 0) {
                        val person = poseFrame.persons[0]
                        val landmarkCount = person.landmarks.size
//                        Log.d(TAG, "personId=${person.personId}, " + "landmark count=$landmarkCount")
                    }
                    //把PoseFrameData交给MainActivity
                    listener.onPoseFrame(poseFrame)    //Server: “我已经完成网络接收和JSON解析，接下来这帧数据交给你。”->mainactivity.kt渲染在屏幕上
                }
                // 未知消息
                else -> {
                    Log.w(TAG, "Unknown message type: $type")
                }
            }
            // 通知MainActivity
            listener.onMessageReceived(message)
        } catch (
            e: Exception
        ) {
            Log.e(TAG, "Parse message failed", e)
        }
    }

    override fun onClose(
        conn: WebSocket,
        code: Int,
        reason: String,
        remote: Boolean
    ) {
        val wasActiveClient = activeClient == conn
        if (wasActiveClient) { activeClient = null
            Log.i(TAG, "Active client disconnected. " + "code=$code, reason=$reason")
            listener.onClientDisconnected()
        } else {
            Log.i(TAG, "Old/non-active client disconnected. " + "code=$code, reason=$reason")
        }
    }

    // Server发生异常以后调用
    override fun onError(
        conn: WebSocket?,
        ex: Exception
    ) {
        val errorMessage = ex.message ?: "Unknown WebSocket error"
        Log.e(TAG, errorMessage, ex)
        listener.onServerError(errorMessage)
    }

    //手机开始发送pose的标志
    fun sendPracticeStart(
        workoutId: String
    ): Boolean {
        // 取得当前手机连接
        val client = activeClient
        // 没有连接
        if (client == null) { return false }
        // WebSocket已经不是打开状态
        if (!client.isOpen
        ) {
            Log.w(TAG, "Cannot send PRACTICE_START: " + "client is not open")
            return false
        }

        // 构造控制消息
        val message = JSONObject().apply {
                put("type", "practice_start")
                put("version", 1)
                put("workout_id", workoutId)
            }.toString()

        // OTT -> 手机
        client.send(message)
        return true
    }

    //停止发送pose的标志
    fun sendPracticeStop(): Boolean {
        val client = activeClient

        if (client == null) {
            Log.w(TAG, "Cannot send PRACTICE_STOP: " + "no active client")
            return false
        }

        if (!client.isOpen) {
            Log.w(TAG, "Cannot send PRACTICE_STOP: " + "client is not open")
            return false
        }

        val message = JSONObject().apply {
                put("type", "practice_stop")
                put("version", 1)
            }.toString()

        client.send(message)
        return true
    }

    //通知手机退出动作检测并返回扫码页面。
    fun sendExitDetection(): Boolean {
        val client = activeClient ?: return false
        if (!client.isOpen) return false
        val message = JSONObject().apply {
            put("type", "exit_detection")
            put("version", 1)
        }.toString()
        client.send(message)
        return true
    }

    //退出边看边练业务时调用，断开ott连接
    fun disconnectActiveClient(
        reason: String = "OTT disconnect"
    ) {
        val client = activeClient
        // 先清除activeClient 这样这是OTT主动退出业务，onClose时不会再次触发“手机意外断线弹窗”
        activeClient = null
        if (client != null && client.isOpen
        ) {
            Log.i(TAG, "Disconnect active client: $reason")
            client.close(1000, reason)
        }
    }
}
