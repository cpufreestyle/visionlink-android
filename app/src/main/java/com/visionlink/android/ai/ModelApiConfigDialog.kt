package com.visionlink.android.ai

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast

/**
 * 模型 API 配置对话框
 *
 * 允许用户添加、编辑、删除自定义 OpenAI 兼容 API 配置，
 * 并选择当前激活的配置。
 */
class ModelApiConfigDialog(
    private val context: Context,
    private val manager: ModelApiConfigManager,
    private val onConfigSelected: (ModelApiConfig) -> Unit
) {
    private val configs = manager.getAll().toMutableList()
    private var activeId: String? = manager.getAll().find { it.isActive }?.id
        ?: manager.getAll().firstOrNull()?.id

    fun show() {
        val listView = ListView(context)
        val adapter = ConfigListAdapter()
        listView.adapter = adapter

        val dialog = AlertDialog.Builder(context)
            .setTitle("模型 API 设置")
            .setView(listView)
            .setPositiveButton("关闭", null)
            .setNeutralButton("添加新配置") { _, _ ->
                showEditDialog(null)
            }
            .create()

        listView.setOnItemClickListener { _, _, position, _ ->
            val config = configs[position]
            manager.setActive(config.id)
            activeId = config.id
            onConfigSelected(config)
            Toast.makeText(context, "已切换到: ${config.name}", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        listView.setOnItemLongClickListener { _, _, position, _ ->
            val config = configs[position]
            AlertDialog.Builder(context)
                .setTitle("操作: ${config.name}")
                .setItems(arrayOf("编辑", "删除")) { _, which ->
                    when (which) {
                        0 -> showEditDialog(config)
                        1 -> {
                            manager.delete(config.id)
                            configs.removeAt(position)
                            adapter.notifyDataSetChanged()
                            Toast.makeText(context, "已删除: ${config.name}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                .show()
            true
        }

        dialog.show()
    }

    private fun showEditDialog(existing: ModelApiConfig?) {
        val layout = LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(40, 30, 40, 10)
        }

        fun makeField(hint: String, value: String, isPassword: Boolean = false): EditText {
            return EditText(context).apply {
                this.hint = hint
                setText(value)
                if (isPassword) {
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                }
                setSingleLine(true)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 12 }
            }
        }

        val etName = makeField("配置名称（如 DeepSeek）", existing?.name ?: "")
        val etUrl = makeField("API URL", existing?.apiUrl ?: "https://api.deepseek.com/v1/chat/completions")
        val etKey = makeField("API Key", existing?.apiKey ?: "", isPassword = true)
        val etVision = makeField("视觉模型名", existing?.visionModel ?: "deepseek-chat")
        val etText = makeField("文本模型名", existing?.textModel ?: "deepseek-chat")

        layout.addView(etName)
        layout.addView(etUrl)
        layout.addView(etKey)
        layout.addView(etVision)
        layout.addView(etText)

        AlertDialog.Builder(context)
            .setTitle(if (existing == null) "添加 API 配置" else "编辑: ${existing.name}")
            .setView(layout)
            .setPositiveButton("保存") { _, _ ->
                val name = etName.text.toString().trim()
                val url = etUrl.text.toString().trim()
                val key = etKey.text.toString().trim()
                val vision = etVision.text.toString().trim()
                val text = etText.text.toString().trim()

                if (name.isEmpty() || url.isEmpty() || key.isEmpty()) {
                    Toast.makeText(context, "名称、URL、Key 不能为空", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }

                val config = ModelApiConfig(
                    id = existing?.id ?: System.currentTimeMillis().toString(),
                    name = name,
                    apiUrl = url,
                    apiKey = key,
                    visionModel = if (vision.isEmpty()) text else vision,
                    textModel = if (text.isEmpty()) vision else text
                )
                manager.save(config)

                if (existing == null) {
                    configs.add(config)
                } else {
                    val idx = configs.indexOfFirst { it.id == config.id }
                    if (idx >= 0) configs[idx] = config
                }

                // 如果是第一个配置或已激活，自动选中
                if (configs.size == 1 || activeId == config.id) {
                    manager.setActive(config.id)
                    activeId = config.id
                    onConfigSelected(config)
                }

                Toast.makeText(context, "已保存: $name", Toast.LENGTH_SHORT).show()
                show()  // 刷新列表
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private inner class ConfigListAdapter : BaseAdapter() {
        override fun getCount() = configs.size
        override fun getItem(position: Int) = configs[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = convertView ?: LayoutInflater.from(context)
                .inflate(android.R.layout.simple_list_item_2, parent, false)
            val config = configs[position]
            view.findViewById<TextView>(android.R.id.text1).apply {
                text = if (config.id == activeId) "★ ${config.name}" else config.name
                setTextColor(0xFF00FF00.toInt())
                textSize = 16f
            }
            view.findViewById<TextView>(android.R.id.text2).apply {
                text = "${config.apiUrl}\n模型: ${config.visionModel}"
                setTextColor(0xFFCCCCCC.toInt())
                textSize = 12f
            }
            return view
        }
    }
}
