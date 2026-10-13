package studio.forest.keyboard

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private val prefs by lazy { getSharedPreferences(KeyboardPreferences.FILE, MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val content=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(24.dp,24.dp,24.dp,24.dp)
            setBackgroundColor(0xFFF7F7F8.toInt())
        }
        content.addView(TextView(this).apply {
            text="Forest Keyboard"
            textSize=26f
            setTextColor(0xFF202124.toInt())
        })
        content.addView(TextView(this).apply {
            text="V0.9.11 · 輸入與學習設定"
            textSize=14f
            setTextColor(0xFF606168.toInt())
            setPadding(0,4.dp,0,18.dp)
        })
        content.addView(Button(this).apply {
            text="啟用 Forest Keyboard"
            setOnClickListener { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
        })
        content.addView(Button(this).apply {
            text="選擇 Forest Keyboard"
            setOnClickListener { (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker() }
        })
        content.addView(sectionTitle("輸入偏好"))
        content.addView(settingSwitch("自動學習常用詞",KeyboardPreferences.AUTO_LEARNING,true,
            "只在一般文字欄位記錄你選過的詞"))
        content.addView(settingSwitch("按鍵震動",KeyboardPreferences.HAPTIC_FEEDBACK,true,
            "關閉後按鍵不再觸發觸覺回饋"))
        content.addView(settingSwitch("按鍵音效",KeyboardPreferences.KEY_SOUND,true,
            "關閉後按鍵不再播放系統點擊音"))
        content.addView(sectionTitle("學習資料"))
        content.addView(TextView(this).apply {
            text="學習資料只保存在這部裝置。密碼欄位不顯示拼音候選，也不會記錄輸入。"
            textSize=14f
            setTextColor(0xFF606168.toInt())
            setPadding(0,0,0,8.dp)
        })
        content.addView(Button(this).apply {
            text="清除已學習詞頻"
            setOnClickListener { confirmClearLearning() }
        })
        setContentView(ScrollView(this).apply { addView(content) })
    }

    private fun sectionTitle(title:String)=TextView(this).apply {
        text=title
        textSize=18f
        setTextColor(0xFF34483A.toInt())
        setPadding(0,22.dp,0,6.dp)
    }

    private fun settingSwitch(title:String,key:String,default:Boolean,summary:String)=LinearLayout(this).apply {
        orientation=LinearLayout.VERTICAL
        val toggle=Switch(this@MainActivity).apply {
            text=title
            textSize=16f
            isChecked=prefs.getBoolean(key,default)
            setOnCheckedChangeListener { _, checked -> prefs.edit().putBoolean(key,checked).apply() }
        }
        addView(toggle)
        addView(TextView(this@MainActivity).apply {
            text=summary
            textSize=13f
            setTextColor(0xFF73747A.toInt())
            setPadding(0,0,0,10.dp)
        })
    }

    private fun confirmClearLearning() {
        val count=prefs.all.keys.count { it.startsWith("freq|") }
        AlertDialog.Builder(this)
            .setTitle("清除已學習詞頻？")
            .setMessage("會移除 $count 筆本機選詞記錄，不會刪除鍵盤設定或內建詞庫。")
            .setNegativeButton("取消",null)
            .setPositiveButton("清除") { _,_ ->
                val removed=KeyboardPreferences.clearLearningData(this)
                Toast.makeText(this,"已清除 $removed 筆學習記錄",Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private val Int.dp:Int get()=(this*resources.displayMetrics.density).toInt()
}
