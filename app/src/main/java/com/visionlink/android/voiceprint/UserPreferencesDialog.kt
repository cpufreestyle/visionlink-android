package com.visionlink.android.voiceprint

import android.content.Context
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import android.widget.ArrayAdapter
import androidx.appcompat.app.AlertDialog
import com.visionlink.android.R

/**
 * 用户偏好设置对话框
 * 允许设置：首选模式、语速、音调、语言
 */
class UserPreferencesDialog(
    private val context: Context,
    private val voicePrintManager: VoicePrintManager,
    private val user: VoicePrintManager.VoicePrintUser
) {

    fun show() {
        val layout = LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 32, 48, 32)
        }

        // 标题
        layout.addView(TextView(context).apply {
            text = "👤 ${user.name} 的偏好设置"
            textSize = 18f
            setPadding(0, 0, 0, 16)
        })

        // 首选模式
        val modeSpinner = Spinner(context).apply {
            adapter = ArrayAdapter(
                context,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("避障模式", "读文字模式", "场景描述", "指向引导")
            )
            setSelection(user.preferredMode - 1)
        }
        layout.addView(TextView(context).apply { text = "首选模式" })
        layout.addView(modeSpinner)

        // 语速
        val rateSeekBar = SeekBar(context).apply {
            max = 20  // 0-20 → 0.5-1.5
            progress = ((user.ttsRate - 0.5f) * 20).toInt()
        }
        val rateLabel = TextView(context).apply {
            text = "语速: ${"%.1f".format(user.ttsRate)}x"
        }
        rateSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                rateLabel.text = "语速: ${"%.1f".format(0.5f + progress * 0.05f)}x"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })
        layout.addView(rateLabel)
        layout.addView(rateSeekBar)

        // 语言
        val langCheck = CheckBox(context).apply {
            text = "英语模式"
            isChecked = user.isEnglish
        }
        layout.addView(langCheck)

        AlertDialog.Builder(context)
            .setView(layout)
            .setPositiveButton("保存") { _, _ ->
                val mode = modeSpinner.selectedItemPosition + 1
                val rate = 0.5f + rateSeekBar.progress * 0.05f
                val isEnglish = langCheck.isChecked

                voicePrintManager.updateUserSettings(
                    userId = user.userId,
                    preferredMode = mode,
                    ttsRate = rate,
                    isEnglish = isEnglish
                )
                Toast.makeText(context, "偏好已保存", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
