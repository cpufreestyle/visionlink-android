package com.visionlink.android.glasses

import android.app.Activity
import android.content.Context
import android.util.Log
import com.visionlink.android.utils.CrashReporter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import com.visionlink.android.glasses.RokidCxrHelper.AuthRequestResult

/**
 * Rokid CXR-L 眼镜管理器
 *
 * CXR-L 流程:
 * 1. 检查 Rokid AI App 是否安装
 * 2. 通过 AuthorizationHelper 请求授权 → 获取 token
 * 3. CXRLink.connect(token) 连接眼镜
 * 4. configCXRSession(CUSTOMVIEW) 配置会话
 * 5. customViewOpen/Update/Close 控制 HUD
 *
 * Prerequisites:
 * - Rokid AI App >= 1.7.14 installed on device (for auth)
 * - Glasses paired via Rokid AI App
 */
class CXRGlassesManager(private val context: Context) {

    companion object {
        private const val TAG = "CXRGlassesManager"
    }

    enum class ConnectionState {
        DISCONNECTED, AUTHENTICATING, CONNECTING, CONNECTED, ERROR
    }

    var connectionState = ConnectionState.DISCONNECTED
        private set

    var errorMessage: String = ""
        private set

    /** 眼镜交互回调（命令点击、语音助手） */
    var interactionCallback: GlassesInteractionCallback? = null
        private set

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    val isConnected: Boolean
        get() = connectionState == ConnectionState.CONNECTED && RokidCxrHelper.isConnected

    // ========== 连接流程 ==========

    /**
     * 检查连接状态（自动重连已授权的设备）
     *
     * CXR-L 的连接需要 Activity 来发起授权，这里只检查蓝牙是否已连接。
     * 真正的连接在 requestAuthAndConnect(Activity) 中发起。
     *
     * 注意：如果当前正在授权或连接中，跳过检查，避免覆盖状态。
     */
    fun connect(callback: (Boolean) -> Unit) {
        // 正在授权或连接中时不覆盖状态
        if (connectionState == ConnectionState.AUTHENTICATING ||
            connectionState == ConnectionState.CONNECTING) {
            Log.d(TAG, "connect() skipped: state=$connectionState")
            return
        }

        if (connectionState == ConnectionState.CONNECTED) {
            Log.w(TAG, "Already connected")
            callback(true)
            return
        }

        scope.launch {
            try {
                val btConnected = RokidCxrHelper.isGlassBtConnected()
                if (btConnected) {
                    Log.i(TAG, "Glasses BT already connected")
                    connectionState = ConnectionState.CONNECTED
                    callback(true)
                } else {
                    Log.i(TAG, "Glasses not connected, need authorization")
                    connectionState = ConnectionState.DISCONNECTED
                    callback(false)
                }
            } catch (e: Exception) {
                errorMessage = e.message ?: "Unknown error"
                connectionState = ConnectionState.ERROR
                Log.e(TAG, "Connect check failed: ", e)
                CrashReporter.reportError("GlassesConnect", "Connect check failed: ${e.message}", e)
                callback(false)
            }
        }
    }

    /**
     * 请求授权并连接眼镜
     *
     * 必须从 Activity 调用，会拉起 Rokid AI App 授权界面。
     * 如果之前已授权过，SDK 可能直接返回 token，无需用户操作。
     * 授权结果在 Activity.onActivityResult 中处理，调用 handleAuthResult()。
     *
     * @param activity 当前 Activity
     * @param onImmediateConnect 如果即时授权成功，通过此回调通知（在 IO 线程）
     * @return true 如果成功发起授权请求或已获得即时 token
     */
    fun requestAuthAndConnect(activity: Activity, onImmediateConnect: ((Boolean) -> Unit)? = null): Boolean {
        if (!RokidCxrHelper.isRokidAppInstalled(activity)) {
            errorMessage = "Rokid AI App 未安装，请先安装 Rokid AI App"
            connectionState = ConnectionState.ERROR
            Log.e(TAG, errorMessage)
            return false
        }

        connectionState = ConnectionState.AUTHENTICATING
        Log.i(TAG, "Requesting Rokid AI App authorization...")
        val authResult = RokidCxrHelper.requestAuthorization(activity)

        if (authResult.immediateToken != null) {
            // 即时授权成功（之前已授权过），直接连接
            Log.i(TAG, "Immediate auth success, connecting to glasses directly...")
            connectionState = ConnectionState.CONNECTING
            connectWithToken(activity, authResult.immediateToken, onImmediateConnect)
            return true
        }

        if (!authResult.started) {
            connectionState = ConnectionState.ERROR
            errorMessage = "无法启动授权流程"
            Log.e(TAG, errorMessage)
            return false
        }

        // 授权 Activity 已启动，等待 onActivityResult
        return true
    }

    /**
     * 使用 token 直接连接眼镜（带超时保护）
     */
    private fun connectWithToken(context: Context, token: String, callback: ((Boolean) -> Unit)?) {
        scope.launch {
            var callbackCalled = false
            val connectTimeoutMs = 30_000L  // 缩短到 30 秒

            // 超时保护
            val timeoutJob = launch {
                delay(connectTimeoutMs)
                if (!callbackCalled) {
                    callbackCalled = true
                    Log.e(TAG, "Glasses connect timed out after ${connectTimeoutMs}ms")
                    connectionState = ConnectionState.ERROR
                    errorMessage = "眼镜连接超时，请确保眼镜已开机并配对"
                    CrashReporter.reportError("GlassesConnect", "Glasses connect timed out after ${connectTimeoutMs}ms")
                    callback?.invoke(false)
                }
            }

            // 实际连接
            RokidCxrHelper.connect(context, token) { connected ->
                if (!callbackCalled) {
                    callbackCalled = true
                    timeoutJob.cancel()
                    if (connected) {
                        connectionState = ConnectionState.CONNECTED
                        errorMessage = ""
                        Log.i(TAG, "Glasses connected: ${RokidCxrHelper.getDeviceName()}")
                    } else {
                        connectionState = ConnectionState.ERROR
                        errorMessage = "眼镜连接失败"
                        Log.e(TAG, "Glasses connect failed")
                    }
                    callback?.invoke(connected)
                }
            }
        }
    }

    /**
     * 重置授权状态（用于超时或取消）
     */
    fun resetAuthState() {
        if (connectionState == ConnectionState.AUTHENTICATING ||
            connectionState == ConnectionState.CONNECTING) {
            Log.w(TAG, "Resetting auth state from $connectionState")
            connectionState = ConnectionState.DISCONNECTED
            errorMessage = "授权超时，请重试"
        }
    }

    /**
     * 处理授权结果（在 Activity.onActivityResult 中调用）
     *
     * @param resultCode Activity result code
     * @param data Intent data
     * @param callback 连接结果回调
     */
    fun handleAuthResult(resultCode: Int, data: android.content.Intent?, callback: (Boolean) -> Unit) {
        // 如果状态已经不是 AUTHENTICATING，说明可能已被超时重置或已通过即时授权连接
        if (connectionState != ConnectionState.AUTHENTICATING) {
            Log.w(TAG, "handleAuthResult called but state=$connectionState, ignoring")
            callback(false)
            return
        }

        val token = RokidCxrHelper.parseAuthResult(resultCode, data)
        if (token == null) {
            connectionState = ConnectionState.ERROR
            errorMessage = "授权失败或被取消"
            callback(false)
            return
        }

        connectionState = ConnectionState.CONNECTING
        Log.i(TAG, "Auth success, connecting to glasses...")
        connectWithToken(context, token, callback)
    }

    // ========== 交互设置 ==========

    /**
     * 设置眼镜交互回调 — 接收眼镜端的点击命令和语音助手事件
     */
    fun setInteractionCallback(callback: GlassesInteractionCallback) {
        interactionCallback = callback
        RokidCxrHelper.setInteractionCallback(callback)
    }

    /**
     * 在眼镜 HUD 上设置功能图标
     * 用户可通过触控板滑动选择图标并点击执行对应功能
     */
    fun setupFunctionIcons() {
        if (!isConnected) return
        try {
            RokidCxrHelper.setupFunctionIcons()
        } catch (e: Exception) {
            Log.e(TAG, "setupFunctionIcons failed: ${e.message}")
        }
    }

    // ========== HUD 显示 ==========

    /**
     * 在眼镜 HUD 上显示文本
     */
    fun sendText(text: String) {
        if (!isConnected) {
            Log.w(TAG, "Cannot send text: not connected")
            return
        }
        if (text.isBlank()) return
        try {
            RokidCxrHelper.showText(text)
            // 确保功能图标可用（幂等操作）
            RokidCxrHelper.setupFunctionIcons()
            Log.d(TAG, "HUD: $text")
        } catch (e: Exception) {
            Log.e(TAG, "sendText failed: ${e.message}")
        }
    }

    /**
     * 更新 HUD 显示的文本
     */
    fun updateText(text: String) {
        if (!isConnected) return
        try {
            RokidCxrHelper.updateText(text)
        } catch (e: Exception) {
            Log.e(TAG, "updateText failed: ${e.message}")
        }
    }

    /**
     * 关闭 HUD
     */
    fun closeHUD() {
        if (!isConnected) return
        try {
            RokidCxrHelper.closeCustomView()
        } catch (e: Exception) {
            Log.e(TAG, "closeHUD failed: ${e.message}")
        }
    }

    fun updateHUDStatus(mode: Int, status: String) {
        if (!isConnected) return
        val modeText = when (mode) {
            1 -> "障碍物检测"
            2 -> "文字识别"
            3 -> "场景描述"
            4 -> "指向引导"
            else -> "未知"
        }
        sendText("$modeText\n$status")
        // 同时设置功能图标，确保交互可用
        setupFunctionIcons()
    }

    fun showResult(result: String) {
        if (!isConnected) return
        val short = if (result.length > 100) result.take(97) + "..." else result
        sendText(short)
    }

    // ========== 设备信息 ==========

    fun getDeviceName(): String {
        return RokidCxrHelper.getDeviceName()
    }

    fun getBatteryLevel(): Int {
        return RokidCxrHelper.getBatteryLevel()
    }

    // ========== 生命周期 ==========

    fun disconnect() {
        if (connectionState == ConnectionState.DISCONNECTED) return
        try {
            RokidCxrHelper.disconnect()
            connectionState = ConnectionState.DISCONNECTED
            errorMessage = ""
            Log.i(TAG, "Glasses disconnected")
        } catch (e: Exception) {
            Log.e(TAG, "Disconnect error: ${e.message}")
        }
    }

    fun release() {
        try {
            disconnect()
            RokidCxrHelper.release()
            scope.cancel("GlassesManager released")
            Log.i(TAG, "Glasses manager released")
        } catch (e: Exception) {
            Log.w(TAG, "Release error: ${e.message}")
        }
    }

    fun getConnectionStatusText(): String {
        return when (connectionState) {
            ConnectionState.DISCONNECTED -> "眼镜未连接"
            ConnectionState.AUTHENTICATING -> "正在授权..."
            ConnectionState.CONNECTING -> "正在连接眼镜..."
            ConnectionState.CONNECTED -> "眼镜已连接: ${getDeviceName()}"
            ConnectionState.ERROR -> "错误: $errorMessage"
        }
    }
}
