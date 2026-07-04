package com.visionlink.android.voiceprint

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.visionlink.android.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 声纹管理对话框
 *
 * 功能:
 * - 查看已注册用户列表
 * - 注册新用户
 * - 删除用户
 * - 测试识别
 * - 设置用户个性化偏好
 */
class VoicePrintDialog(
    private val context: Context,
    private val voicePrintManager: VoicePrintManager
) {

    interface OnUserSelectedListener {
        fun onUserSelected(user: VoicePrintManager.VoicePrintUser)
    }

    var onUserSelectedListener: OnUserSelectedListener? = null

    fun show() {
        val view = LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 32, 48, 32)
        }

        // 标题
        val title = TextView(context).apply {
            text = "🎤 声纹识别管理"
            textSize = 20f
            setPadding(0, 0, 0, 24)
        }
        view.addView(title)

        // 状态
        val statusText = TextView(context).apply {
            text = if (voicePrintManager.isReady()) {
                "✅ 模型已加载 | ${voicePrintManager.getEnrolledCount()} 人已注册"
            } else {
                "⚠️ 模型未加载 (回退模式) | ${voicePrintManager.getEnrolledCount()} 人已注册"
            }
            textSize = 14f
            setPadding(0, 0, 0, 16)
        }
        view.addView(statusText)

        // 用户列表
        val userListView = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = UserListAdapter(
                voicePrintManager.getEnrolledUsers(),
                onDelete = { user -> deleteUser(user) },
                onSelect = { user ->
                    onUserSelectedListener?.onUserSelected(user)
                    Toast.makeText(context, "已切换到 ${user.name}", Toast.LENGTH_SHORT).show()
                }
            )
            setPadding(0, 0, 0, 16)
        }
        view.addView(userListView)

        // 按钮
        val btnLayout = LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
        }

        val btnEnroll = Button(context).apply {
            text = "📝 注册"
            setOnClickListener { showEnrollDialog() }
        }
        val btnIdentify = Button(context).apply {
            text = "🔍 识别"
            setOnClickListener { showIdentifyDialog() }
        }
        val btnClose = Button(context).apply {
            text = "关闭"
        }

        btnLayout.addView(btnEnroll, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        btnLayout.addView(btnIdentify, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        btnLayout.addView(btnClose, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        view.addView(btnLayout)

        val dialog = AlertDialog.Builder(context)
            .setView(view)
            .create()

        btnClose.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun showEnrollDialog() {
        val input = EditText(context).apply {
            hint = "输入名称 (如: 爸爸)"
        }

        AlertDialog.Builder(context)
            .setTitle("📝 注册新声纹")
            .setMessage("请输入名称，然后录音 5 秒\n在安静环境下说任意内容")
            .setView(input)
            .setPositiveButton("开始录音") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(context, "请输入名称", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val userId = "user_${System.currentTimeMillis()}"
                startEnrollment(userId, name)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun startEnrollment(userId: String, name: String) {
        val progress = AlertDialog.Builder(context)
            .setTitle("录制中")
            .setMessage("🎤 正在录制 $name 的声纹...\n请说任意内容 (5秒)\n\n⏺️ 第一段...")
            .setCancelable(false)
            .show()

        voicePrintManager.startEnrollment(userId, name) { score, success ->
            if (success) {
                progress.dismiss()
                Toast.makeText(context, "✅ $name 注册成功! (相似度: ${"%.2f".format(score)})", Toast.LENGTH_LONG).show()
                // 刷新列表
                show()
            } else {
                progress.setMessage("⏺️ 第二段... (验证一致性)")
                // 其实回调已经是最终结果了
                progress.dismiss()
                Toast.makeText(context, "❌ 注册失败，请重试", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showIdentifyDialog() {
        if (voicePrintManager.getEnrolledCount() == 0) {
            Toast.makeText(context, "请先注册声纹", Toast.LENGTH_SHORT).show()
            return
        }

        val progress = AlertDialog.Builder(context)
            .setTitle("识别中")
            .setMessage("🎤 正在识别说话人...\n请说任意内容 (3秒)")
            .setCancelable(false)
            .show()

        voicePrintManager.startIdentification { result ->
            progress.dismiss()

            if (result.isMatch && result.userId != null) {
                val user = voicePrintManager.getUser(result.userId)
                val msg = "✅ 识别为: ${result.name}\n相似度: ${"%.2f".format(result.score)}"
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()

                // 自动应用用户偏好
                user?.let {
                    onUserSelectedListener?.onUserSelected(it)
                }
            } else {
                Toast.makeText(context, "❌ 未识别 (最高分: ${"%.2f".format(result.score)})", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun deleteUser(user: VoicePrintManager.VoicePrintUser) {
        AlertDialog.Builder(context)
            .setTitle("删除用户")
            .setMessage("确定删除 ${user.name} 的声纹?")
            .setPositiveButton("删除") { _, _ ->
                voicePrintManager.deleteUser(user.userId)
                Toast.makeText(context, "已删除 ${user.name}", Toast.LENGTH_SHORT).show()
                show()  // 刷新
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ========== RecyclerView Adapter ==========

    private class UserListAdapter(
        private val users: List<VoicePrintManager.VoicePrintUser>,
        private val onDelete: (VoicePrintManager.VoicePrintUser) -> Unit,
        private val onSelect: (VoicePrintManager.VoicePrintUser) -> Unit
    ) : RecyclerView.Adapter<UserListAdapter.UserViewHolder>() {

        class UserViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val nameText: TextView = view.findViewById(android.R.id.text1)
            val subText: TextView = view.findViewById(android.R.id.text2)
            val btnDelete: Button = view.findViewById(android.R.id.button1)
            val btnSelect: Button = view.findViewById(android.R.id.button2)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): UserViewHolder {
            val view = LinearLayout(parent.context).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(16, 16, 16, 16)
            }

            val textLayout = LinearLayout(parent.context).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val nameText = TextView(parent.context).apply {
                id = android.R.id.text1
                textSize = 16f
                setTextColor(0xFF333333.toInt())
            }
            val subText = TextView(parent.context).apply {
                id = android.R.id.text2
                textSize = 12f
                setTextColor(0xFF666666.toInt())
            }
            textLayout.addView(nameText)
            textLayout.addView(subText)
            view.addView(textLayout)

            val btnSelect = Button(parent.context).apply {
                id = android.R.id.button2
                text = "选择"
            }
            val btnDelete = Button(parent.context).apply {
                id = android.R.id.button1
                text = "删除"
            }
            view.addView(btnSelect)
            view.addView(btnDelete)

            return UserViewHolder(view)
        }

        override fun onBindViewHolder(holder: UserViewHolder, position: Int) {
            val user = users[position]
            holder.nameText.text = "👤 ${user.name}"
            val modeName = when (user.preferredMode) {
                1 -> "避障"
                2 -> "读文字"
                3 -> "场景"
                4 -> "引导"
                else -> "默认"
            }
            holder.subText.text = "模式:$modeName | 语速:${"%.1f".format(user.ttsRate)} | ${if (user.isEnglish) "EN" else "中"}"
            holder.btnDelete.setOnClickListener { onDelete(user) }
            holder.btnSelect.setOnClickListener { onSelect(user) }
        }

        override fun getItemCount() = users.size
    }
}
