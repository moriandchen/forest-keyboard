package studio.forest.keyboard

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.GridLayout
import android.widget.ScrollView
import android.content.Context
import kotlin.math.abs

class ForestKeyboardService : InputMethodService() {
    private lateinit var root: LinearLayout
    private lateinit var candidateHost: LinearLayout
    private var numeric=false
    private var english=false
    private var shift=false
    private var composing=""
    private var spaceStartX=0f
    private var lastCursorStep=0
    private var candidatesExpanded=false
    private val prefs by lazy { getSharedPreferences("forest_learning", Context.MODE_PRIVATE) }

    private val pinyin by lazy { loadDictionary() }
    // Built once after the dictionary is loaded. Previously segment() rebuilt and
    // sorted this list on every key press, which became expensive with V0.8.
    private val syllables by lazy {
        pinyin.keys.asSequence()
            .filter { !it.contains(" ") }
            .sortedByDescending { it.length }
            .toList()
    }
    // Composing strings repeat heavily while typing (w -> wo -> ...). Cache both
    // segmentation and final candidates for the current app session.
    private val segmentationCache = object : LinkedHashMap<String,List<List<String>>>(128,0.75f,true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String,List<List<String>>>?) = size > 256
    }
    private val candidateCache = object : LinkedHashMap<String,List<String>>(128,0.75f,true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String,List<String>>?) = size > 256
    }
    // Compact-Pinyin prefix index: avoids scanning every dictionary entry on each keystroke.
    private val prefixIndex by lazy {
        val out=linkedMapOf<String,MutableList<String>>()
        for((py,words) in pinyin){
            val compact=py.replace(" ","")
            val maxPrefix=minOf(compact.length,8)
            for(len in 1..maxPrefix){
                val bucket=out.getOrPut(compact.substring(0,len)){ mutableListOf() }
                if(bucket.size<24){
                    for(word in words.take(4)){
                        if(word !in bucket) bucket.add(word)
                        if(bucket.size>=24) break
                    }
                }
            }
        }
        out.mapValues { it.value.toList() }
    }

    private fun loadDictionary():Map<String,List<String>>{
        val out=linkedMapOf<String,MutableList<String>>()
        resources.openRawResource(R.raw.pinyin_dict).bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.forEach { line ->
                val clean=line.trim()
                if(clean.isBlank() || clean.startsWith("#")) return@forEach
                val parts=clean.split("\t",limit=2)
                if(parts.size!=2) return@forEach
                val py=parts[0].trim()
                val words=parts[1].trim().split(" ").filter { it.isNotBlank() }
                if(words.isNotEmpty()) out.getOrPut(py){ mutableListOf() }.addAll(words)
            }
        }
        return out.mapValues { (_,v) -> v.distinct() }
    }

    override fun onCreateInputView(): View {
        root=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(5,4,5,5)
            setBackgroundColor(Color.rgb(225,227,232))
        }
        candidateHost=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        render(); return root
    }

    private fun key(label:String, weight:Float=1f, action:(()->Unit)?=null)=Button(this).apply {
        text=label; textSize=18f; isAllCaps=false; minHeight=0; minimumHeight=0; setPadding(0,0,0,0)
        setTextColor(Color.rgb(25,25,28))
        background=GradientDrawable().apply {
            cornerRadius=8.dp.toFloat()
            setColor(if(label in listOf("⇧","⌫","123","☺","↵")) Color.rgb(214,216,221) else Color.WHITE)
        }
        stateListAnimator=null
        elevation=0f
        layoutParams=LinearLayout.LayoutParams(0,52.dp,weight).apply { setMargins(3,4,3,4) }
        setOnClickListener { action?.invoke() ?: commit(label) }
    }

    private fun row(vararg keys:Button)=LinearLayout(this).apply {
        orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER; keys.forEach(::addView)
    }

    private fun render(){
        root.removeAllViews()
        if(numeric) renderNumeric() else {
            if(candidateHost.parent!=null) (candidateHost.parent as? LinearLayout)?.removeView(candidateHost)
            root.addView(candidateHost)
            refreshCandidates()
            renderLetterKeys()
        }
    }

    private fun refreshCandidates(){
        candidateHost.removeAllViews()
        renderCandidates(candidateHost)
    }

    private fun renderCandidates(host:LinearLayout){
        val bar=LinearLayout(this).apply {
            orientation=LinearLayout.HORIZONTAL
            gravity=Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.rgb(250,250,251))
        }
        val mode=Button(this).apply {
            text=if(english)"ABC" else "拼"
            textSize=13f; isAllCaps=false; minHeight=0; minimumHeight=0
            setTextColor(Color.rgb(70,70,74)); setSingleLine(true)
            layoutParams=LinearLayout.LayoutParams(64.dp,46.dp)
            setOnClickListener { flushRaw(); english=!english; render() }
        }
        bar.addView(mode)
        val scroll=HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled=false; layoutParams=LinearLayout.LayoutParams(0,46.dp,1f) }
        val candidates=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
        val words=if(composing.isBlank()) listOf("繁","簡","「」","常用") else candidateList(composing)
        words.forEach { word ->
            candidates.addView(TextView(this).apply {
                text=word; textSize=20f; gravity=Gravity.CENTER; setPadding(18,0,18,0)
                setTextColor(Color.rgb(38,38,42))
                setBackgroundColor(Color.rgb(250,250,251))
                setSingleLine(true)
                setOnClickListener {
                    when {
                        composing.isNotBlank() -> choose(word)
                        word=="「」" -> { commit("「」"); send(KeyEvent.KEYCODE_DPAD_LEFT) }
                    }
                }
            })
        }
        scroll.addView(candidates); bar.addView(scroll)
        val expand=TextView(this).apply {
            text=if(candidatesExpanded)"⌃" else "⌄"; textSize=24f; gravity=Gravity.CENTER
            setTextColor(Color.rgb(35,35,38))
            layoutParams=LinearLayout.LayoutParams(48.dp,46.dp)
            setOnClickListener {
                if(composing.isNotBlank()){ candidatesExpanded=!candidatesExpanded; refreshCandidates() }
            }
        }
        bar.addView(expand); host.addView(bar)

        if(candidatesExpanded && composing.isNotBlank()){
            val all=expandedCandidateList(composing)
            val grid=GridLayout(this).apply {
                columnCount=4
                setPadding(8.dp,4.dp,8.dp,8.dp)
                setBackgroundColor(Color.rgb(250,250,251))
            }
            all.forEach { word ->
                grid.addView(TextView(this).apply {
                    text=word; textSize=20f; gravity=Gravity.CENTER
                    setTextColor(Color.rgb(38,38,42)); setPadding(8.dp,10.dp,8.dp,10.dp)
                    layoutParams=GridLayout.LayoutParams().apply { width=0; columnSpec=GridLayout.spec(GridLayout.UNDEFINED,1,1f) }
                    setOnClickListener {
                        // Collapse/remove the expanded panel immediately. Some IME hosts
                        // defer the full root redraw after commitText(), leaving the old
                        // ScrollView visible even though the state is already false.
                        candidatesExpanded=false
                        choose(word)
                    }
                })
            }
            host.addView(ScrollView(this).apply {
                layoutParams=LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,132.dp)
                addView(grid)
            })
        }
    }

    private fun renderLetterKeys(){
        root.addView(row(*"qwertyuiop".map { c ->
            val label=if(shift)c.uppercase() else c.toString(); key(label){letter(label)}
        }.toTypedArray()))
        root.addView(LinearLayout(this).apply {
            orientation=LinearLayout.HORIZONTAL
            addView(View(this@ForestKeyboardService), LinearLayout.LayoutParams(14.dp,1))
            "asdfghjkl".forEach { c -> val label=if(shift)c.uppercase() else c.toString(); addView(key(label){letter(label)}) }
            addView(View(this@ForestKeyboardService), LinearLayout.LayoutParams(14.dp,1))
        })
        root.addView(row(
            key("⇧",1.15f){shift=!shift;render()},
            *"zxcvbnm".map { c -> val label=if(shift)c.uppercase() else c.toString(); key(label){letter(label)} }.toTypedArray(),
            key("⌫",1.15f){backspace()}
        ))
        root.addView(row(
            key("123",1.25f){flushRaw();numeric=true;render()},
            key("☺",1.05f){flushRaw();commit("☺")},
            key(if(english)"space" else "空格",4.2f).also(::setupSpace),
            key("↵",1.55f){flushRaw();commit("\n")}
        ))
    }

    private fun letter(s:String){
        if(english){ commit(s); return }
        composing += s.lowercase()
        currentInputConnection.setComposingText(composing,1)
        refreshCandidates()
    }

    private fun candidateList(raw:String):List<String>{
        // Keep ranking dynamic so personal learning takes effect immediately.
        candidateCache[raw]?.let { return rank(raw,it) }
        val base = pinyin[raw] ?: run {
            val phrases = mutableListOf<String>()
            for(parts in segment(raw).take(12)){
                val spaced=parts.joinToString(" ")
                pinyin[spaced]?.let { phrases.addAll(it) }
                val perSyllable=parts.map { pinyin[it]?.firstOrNull() }
                if(perSyllable.all { it != null }) phrases += perSyllable.filterNotNull().joinToString("")
            }
            // If the last syllable is still incomplete (e.g. xih -> xi + h...),
            // preserve candidates for the completed prefix and predict dictionary
            // entries whose compact Pinyin starts with what the user has typed.
            phrases.addAll(prefixCandidates(raw))
            phrases.distinct().take(12).ifEmpty { listOf(raw) }
        }
        candidateCache[raw]=base
        return rank(raw,base)
    }

    private fun prefixCandidates(raw:String):List<String>{
        if(raw.isBlank()) return emptyList()
        val out=LinkedHashSet<String>()

        // 1) O(1)-style lookup from a prebuilt compact-Pinyin prefix index.
        // No full dictionary scan while the user is pressing keys.
        prefixIndex[raw.take(8)]?.let { out.addAll(it) }

        // 2) Preserve a completed leading syllable while the next one is partial.
        // Example: xih -> xi + h..., so 喜/西/希 remain useful candidates.
        for(split in raw.length-1 downTo 1){
            val left=raw.substring(0,split)
            val right=raw.substring(split)
            val leftWords=pinyin[left]
            if(leftWords!=null && syllables.any { it.startsWith(right) }){
                out.addAll(leftWords.take(8))
                break
            }
        }
        return out.take(24)
    }

    private fun expandedCandidateList(raw:String):List<String>{
        val out=mutableListOf<String>()
        pinyin[raw]?.let { out.addAll(rank(raw,it)) }
        for(parts in segment(raw).take(24)){
            val spaced=parts.joinToString(" ")
            pinyin[spaced]?.let { out.addAll(rank(raw,it)) }
            // Build several combinations instead of only the first character of each syllable.
            var combos=listOf("")
            for(part in parts){
                val choices=pinyin[part].orEmpty().take(4)
                combos=combos.flatMap { prefix -> choices.map { prefix+it } }.take(64)
            }
            out.addAll(combos)
            if(out.size>=96) break
        }
        return out.distinct().take(96).ifEmpty { listOf(raw) }
    }

    private fun segment(raw:String):List<List<String>>{
        if(raw.isBlank()) return emptyList()
        segmentationCache[raw]?.let { return it }
        val memo=mutableMapOf<Int,List<List<String>>>()
        fun walk(pos:Int):List<List<String>>{
            if(pos==raw.length)return listOf(emptyList())
            memo[pos]?.let{return it}
            val out=mutableListOf<List<String>>()
            for(s in syllables){
                if(raw.startsWith(s,pos)){
                    for(tail in walk(pos+s.length)) {
                        out += listOf(s)+tail
                        if(out.size>=24) break
                    }
                }
                if(out.size>=24) break
            }
            memo[pos]=out
            return out
        }
        return walk(0).also { segmentationCache[raw]=it }
    }

    private fun choose(word:String){
        val raw=composing
        learn(raw,word)
        // setComposingText() already placed the visible Pinyin in the editor.
        // Replace that composing span with the selected Chinese candidate;
        // do NOT finish it first, otherwise the raw Pinyin becomes permanent.
        currentInputConnection.commitText(word,1)
        composing=""
        candidatesExpanded=false
        refreshCandidates()
    }

    private fun learn(raw:String, word:String){
        if(raw.isBlank() || word==raw)return
        val key="freq|" + raw + "|" + word
        prefs.edit().putInt(key,prefs.getInt(key,0)+1).apply()
    }

    private fun rank(raw:String, words:List<String>):List<String>{
        return words.withIndex()
            .sortedWith(compareByDescending<IndexedValue<String>> { item ->
                prefs.getInt("freq|" + raw + "|" + item.value,0)
            }.thenBy { item -> item.index })
            .map { item -> item.value }
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
            refreshCandidates()
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
