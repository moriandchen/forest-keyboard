package studio.forest.keyboard

import android.graphics.Color
import android.inputmethodservice.InputMethodService
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import kotlin.math.abs

class ForestKeyboardService : InputMethodService() {
    private lateinit var root: LinearLayout
    private var numeric=false
    private var shift=false
    private var spaceStartX=0f
    private var lastCursorStep=0

    override fun onCreateInputView(): View {
        root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(6,8,6,8); setBackgroundColor(Color.rgb(238,238,240)) }
        render(); return root
    }

    private fun key(label:String, weight:Float=1f, action:(()->Unit)?=null)=Button(this).apply {
        text=label; textSize=18f; isAllCaps=false; setPadding(0,0,0,0)
        layoutParams=LinearLayout.LayoutParams(0,58.dp,weight).apply { setMargins(3,3,3,3) }
        setOnClickListener { action?.invoke() ?: commit(label) }
    }
    private fun row(vararg keys:Button)=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER; keys.forEach(::addView) }
    private fun render(){ root.removeAllViews(); if(numeric) renderNumeric() else renderLetters() }

    private fun renderLetters(){
        root.addView(row(key("繁"),key("简"),key("字数"),key("「」"){ commit("「」"); send(KeyEvent.KEYCODE_DPAD_LEFT) },key("常用")))
        listOf("qwertyuiop","asdfghjkl","zxcvbnm").forEach { chars ->
            root.addView(row(*chars.map { c->key(if(shift)c.uppercase() else c.toString()) }.toTypedArray()))
        }
        root.addView(row(
            key("123",1.2f){numeric=true;render()}, key("⇧"){shift=!shift;render()}, key("，"), key("。"),
            key("空格",3f).also(::setupSpace),
            key("⌫",1.2f){currentInputConnection.deleteSurroundingText(1,0)},
            key("↵",1.2f){commit("\n")}
        ))
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
                MotionEvent.ACTION_UP->{if(abs(e.x-spaceStartX)<12.dp)commit(" ");true}
                else->false
            }
        }
    }
    private fun send(code:Int)=currentInputConnection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN,code))
    private fun commit(s:String)=currentInputConnection.commitText(s,1)
    private val Int.dp:Int get()=(this*resources.displayMetrics.density).toInt()
}
