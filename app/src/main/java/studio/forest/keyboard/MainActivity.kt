package studio.forest.keyboard

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import android.text.InputType

class MainActivity : Activity() {
    private val prefs by lazy { getSharedPreferences(KeyboardPreferences.FILE, MODE_PRIVATE) }
    private lateinit var customPhraseList:LinearLayout

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
            text="V0.9.13 · 個人詞庫與輸入設定"
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
        content.addView(sectionTitle("鍵盤布局"))
        val heightLabel=TextView(this).apply { textSize=16f }
        fun updateHeightLabel(value:Int) { heightLabel.text="按鍵高度：${value}（標準 54）" }
        updateHeightLabel(prefs.getInt(KeyboardPreferences.KEY_HEIGHT,54).coerceIn(44,62))
        content.addView(heightLabel)
        content.addView(android.widget.SeekBar(this).apply {
            max=18
            progress=prefs.getInt(KeyboardPreferences.KEY_HEIGHT,54).coerceIn(44,62)-44
            setOnSeekBarChangeListener(object:android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar:android.widget.SeekBar?,value:Int,fromUser:Boolean) {
                    updateHeightLabel(value+44)
                }
                override fun onStartTrackingTouch(bar:android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(bar:android.widget.SeekBar?) {
                    prefs.edit().putInt(KeyboardPreferences.KEY_HEIGHT,(bar?.progress?:10)+44).apply()
                }
            })
        })
        content.addView(settingSwitch("標點快捷列",KeyboardPreferences.PUNCTUATION_ROW,false,
            "開啟後在字母上方顯示標點；關閉時可從 123 輸入標點"))
        content.addView(sectionTitle("輸入偏好"))
        content.addView(settingSwitch("自動學習常用詞",KeyboardPreferences.AUTO_LEARNING,true,
            "只在一般文字欄位記錄你選過的詞"))
        content.addView(settingSwitch("按鍵震動",KeyboardPreferences.HAPTIC_FEEDBACK,true,
            "關閉後按鍵不再觸發觸覺回饋"))
        content.addView(settingSwitch("按鍵音效",KeyboardPreferences.KEY_SOUND,true,
            "關閉後按鍵不再播放系統點擊音"))
        content.addView(sectionTitle("自訂短語"))
        content.addView(TextView(this).apply {
            text="加入常用名字、品牌或固定譯名；輸入拼音前綴時就能選用。"
            textSize=14f
            setTextColor(0xFF606168.toInt())
            setPadding(0,0,0,8.dp)
        })
        content.addView(Button(this).apply {
            text="新增自訂短語"
            setOnClickListener { showAddCustomPhraseDialog() }
        })
        customPhraseList=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        content.addView(customPhraseList)
        renderCustomPhraseList()
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

    override fun onResume() {
        super.onResume()
        if(::customPhraseList.isInitialized) renderCustomPhraseList()
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

    private fun showAddCustomPhraseDialog() {
        val form=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(24.dp,8.dp,24.dp,0)
        }
        val pinyin=EditText(this).apply {
            hint="拼音，例如 billkin"
            inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSingleLine(true)
        }
        val phrase=EditText(this).apply {
            hint="要顯示的字詞，例如 Billkin"
            inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSingleLine(true)
        }
        form.addView(pinyin)
        form.addView(phrase)
        val dialog=AlertDialog.Builder(this)
            .setTitle("新增自訂短語")
            .setView(form)
            .setNegativeButton("取消",null)
            .setPositiveButton("加入",null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val py=pinyin.text.toString().trim().lowercase()
                val word=phrase.text.toString().trim()
                if(!py.matches(Regex("[a-z]+")) || word.isBlank()) {
                    Toast.makeText(this,"請輸入英文字母拼音和字詞",Toast.LENGTH_SHORT).show()
                } else {
                    KeyboardPreferences.addCustomPhrase(this,py,word)
                    renderCustomPhraseList()
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }

    private fun renderCustomPhraseList() {
        customPhraseList.removeAllViews()
        val phrases=KeyboardPreferences.customPhrases(this).toSortedMap()
        if(phrases.isEmpty()) {
            customPhraseList.addView(TextView(this).apply {
                text="尚未新增短語"
                textSize=14f
                setTextColor(0xFF73747A.toInt())
                setPadding(0,4.dp,0,8.dp)
            })
            return
        }
        phrases.forEach { (py,words) ->
            val row=LinearLayout(this).apply {
                orientation=LinearLayout.HORIZONTAL
                gravity=android.view.Gravity.CENTER_VERTICAL
            }
            row.addView(TextView(this).apply {
                text="$py  →  ${words.joinToString("、")}"
                textSize=15f
                setTextColor(0xFF303136.toInt())
                layoutParams=LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f)
            })
            row.addView(Button(this).apply {
                text="移除"
                setOnClickListener {
                    KeyboardPreferences.removeCustomPhrases(this@MainActivity,py)
                    renderCustomPhraseList()
                }
            })
            customPhraseList.addView(row)
        }
    }

    private val Int.dp:Int get()=(this*resources.displayMetrics.density).toInt()
}

