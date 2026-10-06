package studio.forest.keyboard

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.view.inputmethod.InputMethodManager

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val box = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(48,64,48,48) }
        box.addView(TextView(this).apply {
            text="Forest Keyboard\nV0.9.3 · 輸入手感更新\n\n繁體拼音、即時候選與本機詞頻學習。\n本版更新：候選列穩定刷新、打字自動收起展開面板，並保留分段選字及 ，。？！"; textSize=20f
        })
        box.addView(Button(this).apply { text="启用 Forest Keyboard"; setOnClickListener { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) } })
        box.addView(Button(this).apply { text="选择输入法"; setOnClickListener { (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker() } })
        setContentView(box)
    }
}
