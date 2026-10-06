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
import android.graphics.drawable.InsetDrawable
import android.os.Handler
import android.os.Looper
import android.view.inputmethod.EditorInfo
import java.util.concurrent.Executors
import java.util.concurrent.Future
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
    private val mainHandler = Handler(Looper.getMainLooper())
    // All dictionary caches are confined to this single worker.
    private val candidateWorker = Executors.newSingleThreadExecutor()
    private var candidateJob: Future<*>? = null
    private var candidateRevision = 0
    private var displayedRaw = ""
    private var displayedWords = emptyList<String>()
    private var displayedConsumption = emptyMap<String,Int>()
    private var chooseOnReady = false
    private var candidateTouch = false
    private var deferredCandidates: (() -> Unit)? = null
    private var scheduledRefresh: Runnable? = null
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
    private val compactDictionary by lazy {
        val out=linkedMapOf<String,MutableList<String>>()
        for((py,words) in pinyin) out.getOrPut(py.replace(" ","")) { mutableListOf() }.addAll(words)
        out.mapValues { it.value.distinct() }
    }
    private val wordPinyin by lazy {
        val out=mutableMapOf<String,MutableSet<String>>()
        for((py,words) in pinyin) for(word in words) {
            out.getOrPut(word) { linkedSetOf() }.add(py.replace(" ",""))
        }
        out.mapValues { it.value.toList() }
    }
    private val syllablesByInitial by lazy { syllables.groupBy { it.first() } }
    private val syllablePrefixes by lazy {
        syllables.flatMap { s -> (1..s.length).map { s.take(it) } }.toHashSet()
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
            val maxPrefix=compact.length
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

    override fun onCreate() {
        super.onCreate()
        candidateWorker.submit {
            // Warm up off the UI thread, including SharedPreferences disk loading.
            pinyin.size; compactDictionary.size; wordPinyin.size
            syllablesByInitial.size; syllablePrefixes.size; prefixIndex.size
            prefs.all
        }
    }

    private fun resetCandidates() {
        candidateRevision++
        candidateJob?.cancel(true)
        scheduledRefresh?.let { mainHandler.removeCallbacks(it) }
        scheduledRefresh=null
        deferredCandidates=null
        candidateTouch=false
        chooseOnReady=false
        displayedRaw=""
        displayedWords=emptyList()
        displayedConsumption=emptyMap()
        composing=""
        candidatesExpanded=false
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute,restarting)
        resetCandidates()
        if(::root.isInitialized && !numeric) refreshCandidates()
    }

    override fun onFinishInput() {
        currentInputConnection?.finishComposingText()
        resetCandidates()
        super.onFinishInput()
    }

    override fun onDestroy() {
        resetCandidates()
        candidateWorker.shutdownNow()
        super.onDestroy()
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
            cornerRadius=9.dp.toFloat()
            setColor(if(label in listOf("⇧","⌫","123","☺","↵")) Color.rgb(214,216,221) else Color.WHITE)
        }
        if(label.length==1 && label[0].isLetter()) {
            // Narrow the painted cap without reducing the button's touch target.
            background=InsetDrawable(background,1.dp,0,1.dp,0)
        }
        stateListAnimator=null
        elevation=0f
        layoutParams=LinearLayout.LayoutParams(0,49.dp,weight).apply { setMargins(2,3,2,3) }
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
        val revision=++candidateRevision
        val raw=composing
        val expanded=candidatesExpanded
        chooseOnReady=false
        candidateJob?.cancel(true)
        scheduledRefresh?.let { mainHandler.removeCallbacks(it) }
        deferredCandidates=null
        fun applyWords(words:List<String>, all:List<String>, consumption:Map<String,Int>) {
            if(revision!=candidateRevision || raw!=composing || numeric) return
            if(candidateTouch) {
                deferredCandidates={ applyWords(words,all,consumption) }
                return
            }
            displayedRaw=raw
            displayedWords=words
            displayedConsumption=consumption
            candidateHost.alpha=1f
            if(chooseOnReady && words.isNotEmpty()) {
                chooseOnReady=false
                choose(words.first())
                return
            }
            candidateHost.removeAllViews()
            renderCandidates(candidateHost,raw,words,all)
        }
        if(raw.isBlank()) {
            applyWords(listOf("繁","簡","「」","常用"),emptyList(),emptyMap())
            return
        }
        // Old candidates may remain visible while loading, but cannot commit for
        // a different composing string. Coalesce rapid keys into one query/frame.
        candidateHost.alpha=0.55f
        scheduledRefresh=Runnable {
            scheduledRefresh=null
            candidateJob=candidateWorker.submit {
                try {
                    val words=candidateList(raw)
                    val all=if(expanded) expandedCandidateList(raw) else emptyList()
                    val consumption=(words+all).distinct().associateWith { consumedLength(raw,it) }
                    if(!Thread.currentThread().isInterrupted) {
                        mainHandler.post { applyWords(words,all,consumption) }
                    }
                } catch (_: InterruptedException) {
                    // Superseded by a new key. Never cache a partial computation.
                }
            }
        }.also { mainHandler.postDelayed(it,16) }
    }

    private fun trackCandidateTouch(view:View) {
        view.setOnTouchListener { _,event ->
            when(event.actionMasked) {
                MotionEvent.ACTION_DOWN -> candidateTouch=true
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    candidateTouch=false
                    // Let the click finish before replacing its target view.
                    mainHandler.post {
                        val pending=deferredCandidates
                        deferredCandidates=null
                        pending?.invoke()
                    }
                }
            }
            false
        }
    }

    private fun renderCandidates(host:LinearLayout, raw:String, words:List<String>, all:List<String>){
        val bar=LinearLayout(this).apply {
            orientation=LinearLayout.HORIZONTAL
            gravity=Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.rgb(250,250,251))
        }
        val mode=Button(this).apply {
            text=if(english)"ABC" else "拼"
            textSize=13f; isAllCaps=false; minHeight=0; minimumHeight=0
            setTextColor(Color.rgb(70,70,74)); setSingleLine(true)
            layoutParams=LinearLayout.LayoutParams(52.dp,43.dp)
            setOnClickListener { flushRaw(); english=!english; render() }
        }
        bar.addView(mode)
        val scroll=HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled=false; layoutParams=LinearLayout.LayoutParams(0,43.dp,1f) }
        val candidates=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
        words.forEach { word ->
            candidates.addView(TextView(this).apply {
                text=word; textSize=20f; gravity=Gravity.CENTER; setPadding(18.dp,0,18.dp,0)
                minimumWidth=56.dp
                layoutParams=LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,43.dp)
                setTextColor(Color.rgb(38,38,42))
                setBackgroundColor(Color.rgb(250,250,251))
                setSingleLine(true)
                trackCandidateTouch(this)
                setOnClickListener {
                    if(raw!=composing) return@setOnClickListener
                    when {
                        raw.isNotBlank() -> choose(word)
                        word=="「」" -> { commit("「」"); send(KeyEvent.KEYCODE_DPAD_LEFT) }
                    }
                }
            })
        }
        scroll.addView(candidates); bar.addView(scroll)
        val expand=TextView(this).apply {
            text=if(candidatesExpanded)"⌃" else "⌄"; textSize=24f; gravity=Gravity.CENTER
            setTextColor(Color.rgb(35,35,38))
            layoutParams=LinearLayout.LayoutParams(42.dp,43.dp)
            setOnClickListener {
                if(composing.isNotBlank()){ candidatesExpanded=!candidatesExpanded; refreshCandidates() }
            }
        }
        bar.addView(expand); host.addView(bar)

        if(candidatesExpanded && composing.isNotBlank()){
            val grid=GridLayout(this).apply {
                columnCount=4
                setPadding(8.dp,4.dp,8.dp,8.dp)
                setBackgroundColor(Color.rgb(250,250,251))
            }
            all.forEach { word ->
                grid.addView(TextView(this).apply {
                    text=word; textSize=20f; gravity=Gravity.CENTER
                    setTextColor(Color.rgb(38,38,42)); setPadding(8.dp,10.dp,8.dp,10.dp)
                    minimumHeight=48.dp
                    layoutParams=GridLayout.LayoutParams().apply {
                        width=0
                        setMargins(3.dp,2.dp,3.dp,2.dp)
                        columnSpec=GridLayout.spec(GridLayout.UNDEFINED,1,1f)
                    }
                    trackCandidateTouch(this)
                    setOnClickListener {
                        if(raw!=composing) return@setOnClickListener
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
        // Persistent punctuation row: available even while composing.
        val marks=if(english) listOf(",",".","?","!") else listOf("，","。","？","！")
        root.addView(row(*marks.map { mark ->
            key(mark){ punct(mark) }.apply {
                layoutParams=LinearLayout.LayoutParams(0,34.dp,1f).apply { setMargins(2,2,2,2) }
                textSize=20f
            }
        }.toTypedArray()))
        root.addView(row(*"qwertyuiop".map { c ->
            val label=if(shift)c.uppercase() else c.toString(); key(label){letter(label)}
        }.toTypedArray()))
        root.addView(LinearLayout(this).apply {
            orientation=LinearLayout.HORIZONTAL
            addView(View(this@ForestKeyboardService), LinearLayout.LayoutParams(18.dp,1))
            "asdfghjkl".forEach { c -> val label=if(shift)c.uppercase() else c.toString(); addView(key(label){letter(label)}) }
            addView(View(this@ForestKeyboardService), LinearLayout.LayoutParams(14.dp,1))
        })
        root.addView(row(
            key("⇧",1.35f){shift=!shift;render()},
            *"zxcvbnm".map { c -> val label=if(shift)c.uppercase() else c.toString(); key(label){letter(label)} }.toTypedArray(),
            key("⌫",1.35f){backspace()}
        ))
        root.addView(row(
            key("123",1.15f){flushRaw();numeric=true;render()},
            key("☺",0.95f){flushRaw();commit("☺")},
            key(if(english)"English" else "拼音",4.65f).also(::setupSpace),
            key("↵",1.35f){flushRaw();commit("\n")}
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
            val exact=compactDictionary[raw].orEmpty()
            val phrases = mutableListOf<String>()
            for(parts in segment(raw).take(12)){
                pinyin[parts.joinToString(" ")]?.let { phrases.addAll(it) }
                val perSyllable=parts.map { pinyin[it]?.firstOrNull() }
                if(perSyllable.all { it != null }) phrases += perSyllable.filterNotNull().joinToString("")
            }
            // Keep known whole words first, then expose the completed leading
            // syllable so the user can resolve the rest one character at a time.
            val leading=leadingCandidates(raw)
            (exact + leading.take(8) + phrases + prefixCandidates(raw))
                .distinct().take(16).ifEmpty { listOf(raw) }
        }
        if(Thread.currentThread().isInterrupted) throw InterruptedException()
        candidateCache[raw]=base
        return rank(raw,base)
    }

    private fun leadingCandidates(raw:String):List<String> {
        if(raw.isBlank()) return emptyList()
        val head=syllablesByInitial[raw.first()].orEmpty().firstOrNull { raw.startsWith(it) }
            ?: return emptyList()
        return rank(head,pinyin[head].orEmpty())
    }

    private fun consumedLength(raw:String, word:String):Int {
        // Computed on the worker alongside the candidate snapshot, never while
        // handling a tap. Predicted whole words and synthesized combinations
        // consume the typed prefix; leading words consume only their own Pinyin.
        return wordPinyin[word].orEmpty().filter { raw.startsWith(it) }
            .maxOfOrNull { it.length } ?: raw.length
    }

    private fun prefixCandidates(raw:String):List<String>{
        if(raw.isBlank()) return emptyList()
        val out=LinkedHashSet<String>()

        // 1) O(1)-style lookup from a prebuilt compact-Pinyin prefix index.
        // No full dictionary scan while the user is pressing keys.
        prefixIndex[raw]?.let { out.addAll(it) }

        // 2) Preserve a completed leading syllable while the next one is partial.
        // Example: xih -> xi + h..., so 喜/西/希 remain useful candidates.
        for(split in raw.length-1 downTo 1){
            val left=raw.substring(0,split)
            val right=raw.substring(split)
            val leftWords=pinyin[left]
            if(leftWords!=null && right in syllablePrefixes){
                out.addAll(leftWords.take(8))
                break
            }
        }
        return out.take(24)
    }

    private fun expandedCandidateList(raw:String):List<String>{
        val out=candidateList(raw).toMutableList()
        out.addAll(leadingCandidates(raw))
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
            if(Thread.currentThread().isInterrupted) throw InterruptedException()
            for(s in syllablesByInitial[raw[pos]].orEmpty()){
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
        val result=walk(0)
        if(Thread.currentThread().isInterrupted) throw InterruptedException()
        segmentationCache[raw]=result
        return result
    }

    private fun choose(word:String){
        val raw=composing
        if(raw.isEmpty()) return
        val consumed=(if(displayedRaw==raw) displayedConsumption[word] else null)
            ?.coerceIn(1,raw.length) ?: raw.length
        val remaining=raw.drop(consumed)
        val connection=currentInputConnection ?: return
        // Replace the entire existing composing span with just the chosen word,
        // then create a new composing span for the unconsumed suffix. Batch both
        // editor operations to avoid an intermediate selection callback.
        connection.beginBatchEdit()
        try {
            if(!connection.commitText(word,1)) return
            composing=remaining
            if(remaining.isNotEmpty()) connection.setComposingText(remaining,1)
        } finally {
            connection.endBatchEdit()
        }
        learn(raw.take(consumed),word)
        candidatesExpanded=false
        refreshCandidates()
    }

    private fun learn(raw:String, word:String){
        if(raw.isBlank() || word==raw)return
        val key="freq|" + raw + "|" + word
        prefs.edit().putInt(key,prefs.getInt(key,0)+1).apply()
    }

    private fun rank(raw:String, words:List<String>):List<String>{
        val frequencies=words.associateWith { prefs.getInt("freq|" + raw + "|" + it,0) }
        return words.withIndex()
            .sortedWith(compareByDescending<IndexedValue<String>> { frequencies[it.value] ?: 0 }
                .thenBy { it.index })
            .map { it.value }
    }

    private fun flushRaw(){
        if(composing.isNotEmpty()){
            currentInputConnection.commitText(composing,1)
            composing=""
            candidatesExpanded=false
            refreshCandidates()
        }
    }

    private fun punct(s:String){
        // A punctuation tap never guesses an unseen candidate.
        flushRaw()
        candidatesExpanded=false
        commit(s)
        refreshCandidates()
    }

    private fun backspace(){
        if(composing.isNotEmpty()){
            composing=composing.dropLast(1)
            currentInputConnection.setComposingText(composing,1)
            if(composing.isEmpty()) currentInputConnection.finishComposingText()
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
                    if(diff!=0){
                        if(composing.isNotEmpty()) { flushRaw(); refreshCandidates() }
                        val code=if(diff>0)KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT; repeat(abs(diff)){send(code)};lastCursorStep=step }
                    true
                }
                MotionEvent.ACTION_UP->{
                    if(abs(e.x-spaceStartX)<12.dp){
                        if(composing.isNotEmpty()){
                            if(displayedRaw==composing && displayedWords.isNotEmpty()) {
                                choose(displayedWords.first())
                            } else {
                                // Wait for this exact query rather than selecting stale text.
                                chooseOnReady=true
                            }
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
