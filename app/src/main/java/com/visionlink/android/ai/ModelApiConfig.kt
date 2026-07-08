package com.visionlink.android.ai

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * 自定义模型 API 配置
 *
 * 支持任何 OpenAI 兼容的 API（OpenAI / Moonshot / StepFun / DeepSeek / 通义千问 / LM Studio 等）
 */
data class ModelApiConfig(
    val id: String = System.currentTimeMillis().toString(),
    var name: String,           // 配置名称，如 "DeepSeek"
    var apiUrl: String,         // API URL，如 "https://api.deepseek.com/v1/chat/completions"
    var apiKey: String,         // API Key
    var visionModel: String,    // 视觉模型名，如 "deepseek-chat"
    var textModel: String,      // 文本模型名
    var isActive: Boolean = false
) {
    companion object {
        const val TAG = "ModelApiConfig"
    }
}

/**
 * 管理多个自定义 API 配置，持久化到 SharedPreferences
 */
class ModelApiConfigManager(private val context: Context) {

    companion object {
        private const val PREFS_NAME = "visionlink"
        private const val KEY_CONFIGS = "custom_api_configs"
        private const val KEY_ACTIVE_ID = "custom_api_active_id"
        private const val TAG = "ModelApiConfigMgr"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 获取所有配置 */
    fun getAll(): List<ModelApiConfig> {
        val json = prefs.getString(KEY_CONFIGS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                ModelApiConfig(
                    id = obj.getString("id"),
                    name = obj.getString("name"),
                    apiUrl = obj.getString("apiUrl"),
                    apiKey = obj.getString("apiKey"),
                    visionModel = obj.getString("visionModel"),
                    textModel = obj.getString("textModel"),
                    isActive = obj.optBoolean("isActive", false)
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse configs: ${e.message}")
            emptyList()
        }
    }

    /** 获取当前激活的配置 */
    fun getActive(): ModelApiConfig? {
        val activeId = prefs.getString(KEY_ACTIVE_ID, null) ?: return null
        return getAll().find { it.id == activeId }
    }

    /** 保存或更新配置 */
    fun save(config: ModelApiConfig) {
        val configs = getAll().toMutableList()
        val existingIndex = configs.indexOfFirst { it.id == config.id }
        if (existingIndex >= 0) {
            configs[existingIndex] = config
        } else {
            configs.add(config)
        }
        persist(configs)
    }

    /** 删除配置 */
    fun delete(id: String) {
        val configs = getAll().filter { it.id != id }
        persist(configs)
        // 如果删除的是当前激活的，清除激活
        if (prefs.getString(KEY_ACTIVE_ID, null) == id) {
            prefs.edit().remove(KEY_ACTIVE_ID).apply()
        }
    }

    /** 设置激活配置 */
    fun setActive(id: String) {
        prefs.edit().putString(KEY_ACTIVE_ID, id).apply()
    }

    private fun persist(configs: List<ModelApiConfig>) {
        val arr = JSONArray()
        configs.forEach { c ->
            arr.put(JSONObject().apply {
                put("id", c.id)
                put("name", c.name)
                put("apiUrl", c.apiUrl)
                put("apiKey", c.apiKey)
                put("visionModel", c.visionModel)
                put("textModel", c.textModel)
                put("isActive", c.isActive)
            })
        }
        prefs.edit().putString(KEY_CONFIGS, arr.toString()).apply()
    }
}
