package com.visionlink.android.glasses

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.util.Base64
import android.util.Log
import android.util.Pair as AndroidPair
import com.rokid.cxr.link.CXRLink
import com.rokid.cxr.link.callbacks.ICXRLinkCbk
import com.rokid.cxr.link.callbacks.ICustomCmdCbk
import com.rokid.cxr.link.callbacks.ICustomViewCbk
import com.rokid.cxr.link.utils.CxrDefs
import com.rokid.cxr.link.utils.GlassInfo
import com.rokid.cxr.link.utils.IconInfo
import com.rokid.sprite.aiapp.externalapp.auth.AuthorizationHelper
import com.rokid.sprite.aiapp.externalapp.auth.AuthResult
import com.rokid.sprite.aiapp.externalapp.auth.GlassPermission
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

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
/**
 * 眼镜交互回调 — 接收眼镜端的点击命令和语音助手事件
 */
interface GlassesInteractionCallback {
    /** 眼镜端发送了自定义命令（如点击图标/按钮） */
    fun onCommand(cmd: String, data: ByteArray?)
    /** 眼镜语音助手启动（用户按了眼镜 AI 键） */
    fun onAiAssistStart()
    /** 眼镜语音助手停止 */
    fun onAiAssistStop()
}

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

    /** 交互回调 */
    private var interactionCallback: GlassesInteractionCallback? = null

    /** 设置交互回调 */
    fun setInteractionCallback(callback: GlassesInteractionCallback) {
        interactionCallback = callback
    }

    /** 是否已安装 Rokid AI App */
    fun isRokidAppInstalled(activity: Activity): Boolean {
        return AuthorizationHelper.isRokidAppInstalled(activity)
    }

    /**
     * 授权请求结果
     */
    data class AuthRequestResult(
        val started: Boolean,          // true = 授权 Activity 已启动，等待 onActivityResult
        val immediateToken: String?   // 非 null = 即时授权成功（之前已授权过）
    )

    /**
     * 请求眼镜授权
     *
     * 拉起 Rokid AI App 授权界面，用户确认后返回 token。
     * 如果之前已授权过，SDK 可能直接返回即时结果（无需用户操作）。
     *
     * @param activity 当前 Activity
     * @return AuthRequestResult
     */
    fun requestAuthorization(activity: Activity): AuthRequestResult {
        if (!isRokidAppInstalled(activity)) {
            Log.e(TAG, "Rokid AI App not installed")
            return AuthRequestResult(started = false, immediateToken = null)
        }

        val permissions = arrayOf(
            GlassPermission.CAMERA,
            GlassPermission.MICROPHONE,
            GlassPermission.MEDIA
        )

        val result: AndroidPair<Int, Intent>? = AuthorizationHelper
            .requestAuthorization(activity, permissions, REQUEST_AUTH)

        Log.i(TAG, "requestAuthorization result: ${result?.first}, hasIntent=${result?.second != null}")

        if (result == null) {
            Log.e(TAG, "requestAuthorization returned null")
            return AuthRequestResult(started = false, immediateToken = null)
        }

        // 检查是否是即时授权结果（之前已授权过，SDK 直接返回 token）
        // result.first == RESULT_OK (-1) 表示即时成功
        if (result.first == Activity.RESULT_OK && result.second != null) {
            val token = parseAuthResult(result.first, result.second)
            if (token != null) {
                Log.i(TAG, "Immediate auth success (previously authorized), token length: ${token.length}")
                return AuthRequestResult(started = false, immediateToken = token)
            }
        }

        // 授权 Activity 已启动，等待 onActivityResult
        Log.i(TAG, "Authorization activity launched, waiting for onActivityResult...")
        return AuthRequestResult(started = true, immediateToken = null)
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
                interactionCallback?.onAiAssistStart()
            }

            override fun onGlassAiAssistStop() {
                Log.i(TAG, "onGlassAiAssistStop")
                interactionCallback?.onAiAssistStop()
            }

            override fun onGlassAiInterrupt(interrupted: Boolean) {
                Log.i(TAG, "onGlassAiInterrupt: $interrupted")
            }
        })

        // 设置自定义命令回调 — 接收眼镜端点击/语音命令
        cxrLink!!.setCXRCustomCmdCbk(object : ICustomCmdCbk {
            override fun onCustomCmdResult(cmd: String?, data: ByteArray?) {
                Log.i(TAG, "onCustomCmdResult: cmd=$cmd, dataLen=${data?.size}")
                if (cmd != null) {
                    interactionCallback?.onCommand(cmd, data)
                }
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
     * 构建带交互按钮的 JSON 视图
     *
     * 上方显示文本内容，下方显示功能按钮（拍照、模式切换等）。
     * 按钮点击后眼镜端通过 ICustomCmdCbk.onCustomCmdResult 回传按钮 id。
     */
    private fun buildTextViewJson(text: String): String {
        val escapedText = text.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
        return """{"type":"LinearLayout","props":{"id":"main","layout_width":"match_parent","layout_height":"match_parent","orientation":"vertical","gravity":"center","paddingStart":"24dp","paddingEnd":"24dp","paddingTop":"12dp","paddingBottom":"12dp","backgroundColor":"#FF000000"},"children":[{"type":"TextView","props":{"text":"$escapedText","textSize":"16sp","textStyle":"bold","textColor":"#FFFFFFFF","marginBottom":"12dp"}},{"type":"LinearLayout","props":{"orientation":"horizontal","gravity":"center","layout_width":"match_parent","layout_height":"wrap_content"},"children":[{"type":"Button","props":{"id":"btn_capture","text":"拍照","textSize":"14sp","textColor":"#FFFFFFFF","backgroundColor":"#FF1E88E5","marginEnd":"8dp"}},{"type":"Button","props":{"id":"btn_mode1","text":"障碍物","textSize":"14sp","textColor":"#FFFFFFFF","backgroundColor":"#FF43A047","marginEnd":"8dp"}},{"type":"Button","props":{"id":"btn_mode2","text":"文字","textSize":"14sp","textColor":"#FFFFFFFF","backgroundColor":"#FFFB8C00","marginEnd":"8dp"}},{"type":"Button","props":{"id":"btn_mode3","text":"场景","textSize":"14sp","textColor":"#FFFFFFFF","backgroundColor":"#FFE53935","marginEnd":"8dp"}},{"type":"Button","props":{"id":"btn_guide","text":"引导","textSize":"14sp","textColor":"#FFFFFFFF","backgroundColor":"#FF8E24AA","marginEnd":"8dp"}},{"type":"Button","props":{"id":"btn_continuous","text":"连续","textSize":"14sp","textColor":"#FFFFFFFF","backgroundColor":"#FF00897B"}}]}]}"""
    }

    // ========== 图标设置（眼镜端导航选择）==========

    /**
     * 设置功能图标 — 眼镜端可通过触控板滑动选择图标并点击执行
     *
     * 图标列表：拍照分析、障碍物检测、文字识别、场景描述、指向引导、连续检测
     *
     * @return true 如果设置成功
     */
    fun setupFunctionIcons(): Boolean {
        if (!isConnected) return false
        val iconsJson = buildIconsJson()
        val result = cxrLink?.customViewSetIcons(iconsJson) ?: false
        Log.i(TAG, "setupFunctionIcons result=$result")
        return result
    }

    /**
     * 构建图标 JSON
     *
     * 每个图标包含 name（命令标识）和 data（base64 PNG 图片）
     */
    private fun buildIconsJson(): String {
        val icons = listOf(
            Triple("capture", "拍照", "#1E88E5"),
            Triple("mode_obstacle", "障碍物", "#43A047"),
            Triple("mode_text", "文字", "#FB8C00"),
            Triple("mode_scene", "场景", "#E53935"),
            Triple("mode_guide", "引导", "#8E24AA"),
            Triple("continuous", "连续", "#00897B")
        )
        val jsonArray = JSONArray()
        for ((name, label, color) in icons) {
            val icon = JSONObject()
            icon.put("name", name)
            icon.put("data", createIconBase64(label, color))
            jsonArray.put(icon)
        }
        return jsonArray.toString()
    }

    /**
     * 生成简单的带文字图标（base64 PNG）
     */
    private fun createIconBase64(text: String, bgColor: String): String {
        val size = 96
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // 圆角背景
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        bgPaint.color = Color.parseColor("#FF$bgColor")
        val radius = 16f
        canvas.drawRoundRect(
            0f, 0f, size.toFloat(), size.toFloat(),
            radius, radius, bgPaint
        )

        // 文字
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        textPaint.color = Color.WHITE
        textPaint.textSize = if (text.length <= 2) 32f else 24f
        textPaint.typeface = Typeface.DEFAULT_BOLD
        textPaint.textAlign = Paint.Align.CENTER

        val textBounds = Rect()
        textPaint.getTextBounds(text, 0, text.length, textBounds)
        val baseline = size / 2f + textBounds.height() / 2f
        canvas.drawText(text, size / 2f, baseline, textPaint)

        // 转 base64
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
        bitmap.recycle()
        return Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
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
