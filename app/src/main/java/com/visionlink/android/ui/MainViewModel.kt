package com.visionlink.android.ui

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * MainActivity 的 ViewModel — 持有配置变更后需要保留的状态
 *
 * 注意：完整迁移需要 MainActivity 改为观察 StateFlow，
 * 当前阶段先持有状态，Activity 在 onResume 时恢复。
 */
class MainViewModel : ViewModel() {

    // 当前检测模式
    private val _currentMode = MutableStateFlow(1) // 1=障碍物 2=读文本 3=场景 4=引导
    val currentMode: StateFlow<Int> = _currentMode.asStateFlow()

    // 当前用户 ID
    private val _currentUserId = MutableStateFlow<String?>(null)
    val currentUserId: StateFlow<String?> = _currentUserId.asStateFlow()

    // 是否正在连续检测
    private val _isContinuous = MutableStateFlow(false)
    val isContinuous: StateFlow<Boolean> = _isContinuous.asStateFlow()

    // 是否在引导模式
    private val _isGuideMode = MutableStateFlow(false)
    val isGuideMode: StateFlow<Boolean> = _isGuideMode.asStateFlow()

    // 是否英文模式
    private val _isEnglish = MutableStateFlow(false)
    val isEnglish: StateFlow<Boolean> = _isEnglish.asStateFlow()

    // AI 是否已初始化
    private val _isAiInitialized = MutableStateFlow(false)
    val isAiInitialized: StateFlow<Boolean> = _isAiInitialized.asStateFlow()

    // 当前 AI 引擎名称
    private val _aiEngineName = MutableStateFlow("Moonshot")
    val aiEngineName: StateFlow<String> = _aiEngineName.asStateFlow()

    fun setMode(mode: Int) { _currentMode.value = mode }
    fun setUserId(id: String?) { _currentUserId.value = id }
    fun setContinuous(value: Boolean) { _isContinuous.value = value }
    fun setGuideMode(value: Boolean) { _isGuideMode.value = value }
    fun setEnglish(value: Boolean) { _isEnglish.value = value }
    fun setAiInitialized(value: Boolean) { _isAiInitialized.value = value }
    fun setAiEngineName(name: String) { _aiEngineName.value = name }

    override fun onCleared() {
        super.onCleared()
        // ViewModel 销毁时清理（仅在 Activity 真正销毁时）
    }
}
