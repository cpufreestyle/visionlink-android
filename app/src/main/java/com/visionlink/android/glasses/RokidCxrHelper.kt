package com.visionlink.android.glasses

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.util.Log
import android.util.Pair as AndroidPair
import com.rokid.cxr.link.CXRLink
import com.rokid.cxr.link.callbacks.ICXRLinkCbk
import com.rokid.cxr.link.callbacks.ICustomViewCbk
import com.rokid.cxr.link.utils.CxrDefs
import com.rokid.cxr.link.utils.GlassInfo
import com.rokid.sprite.aiapp.externalapp.auth.AuthorizationHelper
import com.rokid.sprite.aiapp.externalapp.auth.AuthResult
import com.rokid.sprite.aiapp.externalapp.auth.GlassPermission

/**
 * Rokid CXR-L SDK 封装工具类
 *
 * CXR-L SDK 流程:
 * 1. 检查 Rokid AI App 是否安装
 * 2. 通过 AuthorizationHelper 请求授权（拉起 Rokid AI App）
 * 3. 授权成功获取 token
 * 4. 创建 CXRLink，调用 connect(token) 连接眼镜
 * 5. configCXRSession 配置会话
 * 6. 使用 customViewOpen/Update/Close 控制 HUD 显示
 *
 * 参考: sdk/rokid-cxrm demo + client-l SDK 反编译
 */
object RokidCxrHelper {
    private const val TAG = "RokidCxrHelper"

    /** 授权请求码 */
    const val REQUEST_AUTH = 3001

    /** CXRLink 实例 */
    private var cxrLink: CXRLink? = null

    /** 当前眼镜信息 */
    var glassesInfo: GlassInfo = GlassInfo()
        private set

    /** 连接状态 */
    var isConnected = false
        private set

    /** 是否已安装 Rokid AI App */
    fun isRokidAppInstalled(activity: Activity): Boolean {
        return AuthorizationHelper.isRokidAppInstalled(activity)
    }

    /**
     * 请求眼镜授权
     *
     * 拉起 Rokid AI App 授权界面，用户确认后返回 token。
     * 需要在 Activity.onActivityResult 中调用 parseAuthResult 处理结果。
     *
     * @param activity 当前 Activity
     * @return true 如果请求成功发起
     */
    fun requestAuthorization(activity: Activity): Boolean {
        if (!isRokidAppInstalled(activity)) {
            Log.e(TAG, "Rokid AI App not installed")
            return false
        }

        val permissions = arrayOf(
            GlassPermission.CAMERA,
            GlassPermission.MICROPHONE,
            GlassPermission.MEDIA
        )

        val result: AndroidPair<Int, Intent>? = AuthorizationHelper
            .requestAuthorization(activity, permissions, REQUEST_AUTH)

        Log.i(TAG, "requestAuthorization result: ${result?.first}")
        return result != null
    }

    /**
     * 解析授权结果（在 onActivityResult 中调用）
     *
     * @param resultCode Activity result code
     * @param data Intent data
     * @return token 字符串，授权失败返回 null
     */
    fun parseAuthResult(resultCode: Int, data: Intent?): String? {
        val authResult = AuthorizationHelper.parseAuthorizationResult(resultCode, data)
        return when (authResult) {
            is AuthResult.AuthSuccess -> {
                Log.i(TAG, "Auth success, token length: ${authResult.token.length}")
                authResult.token
            }
            is AuthResult.AuthFail -> {
                Log.e(TAG, "Auth failed")
                null
            }
            is AuthResult.AuthCancel -> {
                Log.w(TAG, "Auth cancelled by user")
                null
            }
            else -> {
                Log.e(TAG, "Auth unknown result")
                null
            }
        }
    }

    /**
     * 连接眼镜
     *
     * @param context Application Context
     * @param token 授权 token
     * @param callback 连接回调
     */
    fun connect(context: Context, token: String, callback: (Boolean) -> Unit) {
        if (cxrLink == null) {
            cxrLink = CXRLink(context)
        }

        // 设置连接回调
        cxrLink!!.setCXRLinkCbk(object : ICXRLinkCbk {
            override fun onCXRLConnected(connected: Boolean) {
                Log.i(TAG, "onCXRLConnected: $connected")
                isConnected = connected
                if (connected) {
                    // 连接成功，配置会话
                    val session = CxrDefs.CXRSession(CxrDefs.CXRSessionType.CUSTOMVIEW)
                    val configured = cxrLink!!.configCXRSession(session)
                    Log.i(TAG, "configCXRSession result: $configured")
                    // 获取设备信息
                    cxrLink!!.getGlassDeviceInfo()
                }
                callback(connected)
            }

            override fun onGlassBtConnected(connected: Boolean) {
                Log.i(TAG, "onGlassBtConnected: $connected")
            }

            override fun onGlassDeviceInfo(deviceInfo: GlassInfo) {
                Log.i(TAG, "onGlassDeviceInfo: name=${deviceInfo.deviceName}, battery=${deviceInfo.batteryLevel}")
                glassesInfo = deviceInfo
            }

            override fun onGlassWearingStatus(wearing: Boolean) {
                Log.i(TAG, "onGlassWearingStatus: $wearing")
            }

            override fun onGlassAiAssistStart() {
                Log.i(TAG, "onGlassAiAssistStart")
            }

            override fun onGlassAiAssistStop() {
                Log.i(TAG, "onGlassAiAssistStop")
            }

            override fun onGlassAiInterrupt(interrupted: Boolean) {
                Log.i(TAG, "onGlassAiInterrupt: $interrupted")
            }
        })

        // 设置自定义视图回调
        cxrLink!!.setCXRCustomViewCbk(object : ICustomViewCbk {
            override fun onCustomViewOpened() {
                Log.i(TAG, "CustomView opened")
            }

            override fun onCustomViewUpdated() {
                Log.i(TAG, "CustomView updated")
            }

            override fun onCustomViewClosed() {
                Log.i(TAG, "CustomView closed")
            }

            override fun onCustomViewIconsSent() {
                Log.i(TAG, "CustomView icons sent")
            }

            override fun onCustomViewError(errorCode: Int, errorMsg: String?) {
                Log.e(TAG, "CustomView error: $errorCode ($errorMsg)")
            }
        })

        Log.i(TAG, "Connecting to glasses with token...")
        val result = cxrLink!!.connect(token)
        Log.i(TAG, "connect() returned: $result")
    }

    /**
     * 断开连接
     */
    fun disconnect() {
        try {
            cxrLink?.disconnect()
        } catch (e: Exception) {
            Log.e(TAG, "disconnect error: ${e.message}")
        }
        isConnected = false
    }

    // ========== HUD 自定义视图 ==========

    /**
     * 在眼镜 HUD 上显示文本
     *
     * @param text 要显示的文本
     * @return true 如果成功
     */
    fun showText(text: String): Boolean {
        if (!isConnected) {
            Log.w(TAG, "Cannot show text: not connected")
            return false
        }
        val json = buildTextViewJson(text)
        val result = cxrLink?.customViewOpen(json) ?: false
        Log.d(TAG, "showText: '$text' result=$result")
        return result
    }

    /**
     * 更新 HUD 上显示的文本
     */
    fun updateText(text: String): Boolean {
        if (!isConnected) return false
        val json = buildTextViewJson(text)
        return cxrLink?.customViewUpdate(json) ?: false
    }

    /**
     * 关闭 HUD 自定义视图
     */
    fun closeCustomView(): Boolean {
        if (!isConnected) return false
        return cxrLink?.customViewClose() ?: false
    }

    /**
     * 检查 HUD 是否已打开
     */
    fun isCustomViewOpen(): Boolean {
        return cxrLink?.customViewIsOpen() ?: false
    }

    /**
     * 构建文本显示的 JSON 视图
     */
    private fun buildTextViewJson(text: String): String {
        val escapedText = text.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
        return """{"type":"LinearLayout","props":{"id":"main","layout_width":"match_parent","layout_height":"match_parent","orientation":"vertical","gravity":"center_vertical","paddingStart":"12dp","paddingEnd":"12dp","paddingTop":"160dp","paddingBottom":"80dp","backgroundColor":"#FF000000"},"children":[{"type":"TextView","props":{"text":"$escapedText","textSize":"16sp","textStyle":"bold","textColor":"#FFFFFFFF","marginEnd":"8dp"}}]}"""
    }

    // ========== 设备信息 ==========

    /**
     * 获取设备信息
     */
    fun getDeviceInfo() {
        cxrLink?.getGlassDeviceInfo()
    }

    /**
     * 获取设备名称
     */
    fun getDeviceName(): String {
        return glassesInfo.deviceName ?: ""
    }

    /**
     * 获取电量
     */
    fun getBatteryLevel(): Int {
        return glassesInfo.batteryLevel
    }

    /**
     * 检查蓝牙是否连接
     */
    fun isGlassBtConnected(): Boolean {
        val link = cxrLink ?: return false
        return try {
            link.isGlassBtConnected()
        } catch (e: Exception) {
            false
        }
    }

    // ========== 拍照 ==========

    /**
     * 通过眼镜拍照
     *
     * @param width 照片宽度
     * @param height 照片高度
     * @param quality 照片质量
     * @return true 如果请求成功
     */
    fun takePhoto(width: Int, height: Int, quality: Int): Boolean {
        if (!isConnected) return false
        return cxrLink?.takePhoto(width, height, quality) ?: false
    }

    /**
     * 获取 CXRLink 实例
     */
    fun getCXRLink(): CXRLink? = cxrLink

    /**
     * 释放资源
     */
    fun release() {
        disconnect()
        cxrLink = null
    }
}
