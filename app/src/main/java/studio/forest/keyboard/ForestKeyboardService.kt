package studio.forest.keyboard

import android.graphics.Color
import android.inputmethodservice.InputMethodService
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

class ForestKeyboardService : InputMethodService() {
    private lateinit var root: LinearLayout
    private var numeric=false
    private var english=false
    private var shift=false
    private var composing=""
    private var spaceStartX=0f
    private var lastCursorStep=0

    private val pinyin = mapOf(
        "wo" to listOf("我","握","窩","喔"),
        "ni" to listOf("你","妳","呢","泥"),
        "hao" to listOf("好","號","浩","豪"),
        "shi" to listOf("是","時","事","市","十"),
        "de" to listOf("的","得","德"),
        "le" to listOf("了","樂","勒"),
        "ma" to listOf("嗎","媽","馬","嘛"),
        "ai" to listOf("愛","哎","唉"),
        "zai" to listOf("在","再","載"),
        "you" to listOf("有","又","由","友"),
        "bu" to listOf("不","部","步"),
        "ren" to listOf("人","認","任"),
        "jin" to listOf("今","金","進"),
        "tian" to listOf("天","田","甜"),
        "jin tian" to listOf("今天"),
        "sen" to listOf("森"),
        "lin" to listOf("林"),
        "sen lin" to listOf("森林"),
        "zhong" to listOf("中","種","重"),
        "wen" to listOf("文","問","聞"),
        "zhong wen" to listOf("中文")
    )

    override fun onCreateInputView(): View {
        root=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(5,5,5,5)
            setBackgroundColor(Color.rgb(242,242,244))
        }
        render(); return root
    }

    private fun key(label:String, weight:Float=1f, action:(()->Unit)?=null)=Button(this).apply {
        text=label; textSize=17f; isAllCaps=false; minHeight=0; minimumHeight=0; setPadding(0,0,0,0)
        layoutParams=LinearLayout.LayoutParams(0,50.dp,weight).apply { setMargins(2,2,2,2) }
        setOnClickListener { action?.invoke() ?: commit(label) }
    }

    private fun row(vararg keys:Button)=LinearLayout(this).apply {
        orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER; keys.forEach(::addView)
    }

    private fun render(){ root.removeAllViews(); if(numeric) renderNumeric() else renderLetters() }

    private fun renderCandidates(){
        val bar=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL }
        val mode=Button(this).apply {
            text=if(english)"ABC" else "拼"
            textSize=14f; isAllCaps=false; minHeight=0; minimumHeight=0
            layoutParams=LinearLayout.LayoutParams(58.dp,44.dp)
            setOnClickListener { flushRaw(); english=!english; render() }
        }
        bar.addView(mode)
        val scroll=HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled=false; layoutParams=LinearLayout.LayoutParams(0,44.dp,1f) }
        val candidates=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
        val words=if(composing.isBlank()) listOf("繁","簡","「」","常用") else candidateList(composing)
        words.forEach { word ->
            candidates.addView(TextView(this).apply {
                text=word; textSize=18f; gravity=Gravity.CENTER; setPadding(18,0,18,0)
                setOnClickListener {
                    when {
                        composing.isNotBlank() -> choose(word)
                        word=="「」" -> { commit("「」"); send(KeyEvent.KEYCODE_DPAD_LEFT) }
                    }
                }
            })
        }
        scroll.addView(candidates); bar.addView(scroll); root.addView(bar)
    }

    private fun renderLetters(){
        renderCandidates()
        listOf("qwertyuiop","asdfghjkl","zxcvbnm").forEach { chars ->
            root.addView(row(*chars.map { c ->
                val label=if(shift)c.uppercase() else c.toString()
                key(label){ letter(label) }
            }.toTypedArray()))
        }
        root.addView(row(
            key("123",1.2f){flushRaw();numeric=true;render()},
            key("⇧"){shift=!shift;render()},
            key("，"){punct("，")}, key("。"){punct("。")},
            key("空格",3f).also(::setupSpace),
            key("⌫",1.2f){backspace()},
            key("↵",1.2f){flushRaw();commit("\n")}
        ))
    }

    private fun letter(s:String){
        if(english){ commit(s); return }
        composing += s.lowercase()
        currentInputConnection.setComposingText(composing,1)
        render()
    }

    private fun candidateList(raw:String):List<String>{
        val direct=pinyin[raw]
        if(direct!=null)return direct
        val parts=raw.split(" ")
        if(parts.size>1){
            val last=pinyin[parts.last()] ?: emptyList()
            if(last.isNotEmpty())return last
        }
        return listOf(raw)
    }

    private fun choose(word:String){
        currentInputConnection.finishComposingText()
        currentInputConnection.commitText(word,1)
        composing=""
        render()
    }

    private fun flushRaw(){
        if(composing.isNotEmpty()){
            currentInputConnection.finishComposingText()
            currentInputConnection.commitText(composing,1)
            composing=""
        }
    }

    private fun punct(s:String){ flushRaw(); commit(s) }

    private fun backspace(){
        if(composing.isNotEmpty()){
            composing=composing.dropLast(1)
            if(composing.isEmpty()) currentInputConnection.finishComposingText()
            else currentInputConnection.setComposingText(composing,1)
            render()
        } else currentInputConnection.deleteSurroundingText(1,0)
    }

    private fun renderNumeric(){
        val left=arrayOf(arrayOf("#","£","&","-"),arrayOf("@","(",")","="),arrayOf("更多",":",";","%"),arrayOf("ABC","°","—","*"))
        val nums=arrayOf(arrayOf("1","2","3"),arrayOf("4","5","6"),arrayOf("7","8","9"),arrayOf(",","0","."))
        for(i in 0..3){
            val buttons=mutableListOf<Button>()
            left[i].forEach { s->buttons+=key(s,if(s=="更多"||s=="ABC")1.25f else 1f){if(s=="ABC"){numeric=false;render()}else commit(s)} }
            nums[i].forEach { buttons+=key(it,1.15f) }
            buttons+=when(i){0->key("?");1->key("!");2->key("⌫"){currentInputConnection.deleteSurroundingText(1,0)};else->key("↵"){commit("\n")}}
            root.addView(row(*buttons.toTypedArray()))
        }
    }

    private fun setupSpace(v:Button){
        v.setOnTouchListener { _,e->
            when(e.action){
                MotionEvent.ACTION_DOWN->{spaceStartX=e.x;lastCursorStep=0;true}
                MotionEvent.ACTION_MOVE->{
                    val step=((e.x-spaceStartX)/24.dp).toInt(); val diff=step-lastCursorStep
                    if(diff!=0){ val code=if(diff>0)KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT; repeat(abs(diff)){send(code)};lastCursorStep=step }
                    true
                }
                MotionEvent.ACTION_UP->{
                    if(abs(e.x-spaceStartX)<12.dp){
                        if(composing.isNotEmpty()){
                            val first=candidateList(composing).first()
                            choose(first)
                        } else commit(" ")
                    }
                    true
                }
                else->false
            }
        }
    }

    private fun send(code:Int)=currentInputConnection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN,code))
    private fun commit(s:String)=currentInputConnection.commitText(s,1)
    private val Int.dp:Int get()=(this*resources.displayMetrics.density).toInt()
}
