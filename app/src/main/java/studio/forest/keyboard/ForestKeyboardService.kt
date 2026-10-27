package studio.forest.keyboard

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.view.Gravity
import android.view.KeyEvent
import android.view.HapticFeedbackConstants
import android.view.SoundEffectConstants
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
import android.graphics.drawable.StateListDrawable
import android.os.Handler
import android.os.Looper
import android.view.inputmethod.EditorInfo
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.abs

class ForestKeyboardService : InputMethodService() {
    private lateinit var root: LinearLayout
    private lateinit var candidateHost: LinearLayout
    private lateinit var candidateRow: LinearLayout
    private lateinit var candidateScroll: HorizontalScrollView
    private lateinit var modeButton: Button
    private lateinit var expandButton: TextView
    private var renderedRaw=""
    private data class CandidateTarget(val raw:String, val word:String)
    private var numeric=false
    private var english=false
    private var passwordField=false
    private var learningRestricted=false
    private var shift=false
    private var composing=""
    private var spaceStartX=0f
    private var lastCursorStep=0
    private var spaceDragged=false
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
    private val prefs by lazy { getSharedPreferences(KeyboardPreferences.FILE, Context.MODE_PRIVATE) }
    private val prefsListener=android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if(key==KeyboardPreferences.KEY_HEIGHT || key==KeyboardPreferences.PUNCTUATION_ROW) {
            if(::root.isInitialized) render()
        }
        if(key==KeyboardPreferences.LEARNING_REVISION) {
            candidateWorker.submit {
                learnedByRaw.clear()
                candidateCache.clear()
                mainHandler.post {
                    if(::root.isInitialized && !passwordField && composing.isNotBlank()) refreshCandidates()
                }
            }
        }
        if(key==KeyboardPreferences.CUSTOM_REVISION) {
            candidateWorker.submit {
                reloadCustomDictionary()
                candidateCache.clear()
                mainHandler.post {
                    if(::root.isInitialized && !passwordField && composing.isNotBlank()) refreshCandidates()
                }
            }
        }
    }

    // These snapshots are rebuilt only on the dictionary worker when settings change.
    private var customByPinyin:Map<String,List<String>> = emptyMap()
    private var customPrefixIndex:Map<String,List<String>> = emptyMap()

    // Worker-owned index, restored once. Queries never scan all preferences.
    private val learnedByRaw by lazy {
        val out=mutableMapOf<String,MutableSet<String>>()
        for((key,value) in prefs.all) {
            if(value !is Int || value<=0 || !key.startsWith("freq|")) continue
            val parts=key.split("|",limit=3)
            if(parts.size==3 && compatible(parts[1],parts[2]))
                out.getOrPut(parts[1]) { linkedSetOf() }.add(parts[2])
        }
        out
    }

    private fun compatible(raw:String,word:String):Boolean =
        raw.isNotBlank() && wordPinyin[word].orEmpty().any {
            it.startsWith(raw) || raw.startsWith(it)
        }

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
                if(bucket.size<96){
                    for(word in words){
                        if(word !in bucket) bucket.add(word)
                        if(bucket.size>=96) break
                    }
                }
            }
        }
        out.mapValues { it.value.toList() }
    }
    // Multi-syllable initials (e.g. "jd" -> 覺得), built once with the
    // dictionary so abbreviation queries never scan all entries while typing.
    private val initialsIndex by lazy {
        val out=linkedMapOf<String,MutableList<String>>()
        for((py,words) in pinyin){
            val parts=py.split(' ').filter { it.isNotBlank() }
            if(parts.size<2) continue
            val initials=parts.joinToString("") { it.first().toString() }
            val bucket=out.getOrPut(initials){ mutableListOf() }
            for(word in words){
                if(bucket.size>=96) break
                if(word !in bucket) bucket.add(word)
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

    private fun reloadCustomDictionary() {
        val exact=KeyboardPreferences.customPhrases(this)
        val prefixes=linkedMapOf<String,MutableList<String>>()
        exact.forEach { (py,words) ->
            for(length in 1..py.length) {
                val bucket=prefixes.getOrPut(py.take(length)) { mutableListOf() }
                for(word in words) if(word !in bucket) bucket.add(word)
            }
        }
        customByPinyin=exact
        customPrefixIndex=prefixes.mapValues { it.value.toList() }
    }

    override fun onCreate() {
        super.onCreate()
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        candidateWorker.submit {
            // Warm up off the UI thread, including SharedPreferences disk loading.
            reloadCustomDictionary()
            pinyin.size; compactDictionary.size; wordPinyin.size
            syllablesByInitial.size; syllablePrefixes.size; prefixIndex.size; initialsIndex.size
            learnedByRaw.size
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
        val wasPassword=passwordField
        val inputType=attribute?.inputType ?: 0
        val inputClass=inputType and android.text.InputType.TYPE_MASK_CLASS
        val variation=inputType and android.text.InputType.TYPE_MASK_VARIATION
        passwordField=(inputClass==android.text.InputType.TYPE_CLASS_TEXT && variation in setOf(
            android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD,
            android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            android.text.InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
        )) || (inputClass==android.text.InputType.TYPE_CLASS_NUMBER &&
            variation==android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        learningRestricted=passwordField || (attribute?.imeOptions?.and(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) ?: 0)!=0
        if(passwordField) english=true else if(wasPassword) english=false
        numeric=false
        resetCandidates()
        if(::root.isInitialized) render()
    }

    override fun onFinishInput() {
        currentInputConnection?.finishComposingText()
        resetCandidates()
        super.onFinishInput()
    }

    override fun onDestroy() {
        resetCandidates()
        prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        candidateWorker.shutdownNow()
        super.onDestroy()
    }

    override fun onCreateInputView(): View {
        root=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(3.dp,4.dp,3.dp,12.dp)
            background=GradientDrawable().apply {
                cornerRadius=24.dp.toFloat()
                setColor(Color.rgb(225,227,232))
            }
            clipToOutline=true
        }
        candidateHost=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        renderedRaw=""
        render(); return root
    }

    private fun key(label:String, weight:Float=1f, action:(()->Unit)?=null)=Button(this).apply {
        text=label; textSize=18f; isAllCaps=false; minHeight=0; minimumHeight=0; setPadding(0,0,0,0)
        setTextColor(Color.rgb(25,25,28))
        val special=label in listOf("⇧","⌫","123","☺","↵")
        val normalColor=if(special) Color.rgb(214,216,221) else Color.WHITE
        background=StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed),keyShape(
                if(special) Color.rgb(177,181,190) else Color.rgb(202,207,216)))
            addState(intArrayOf(),keyShape(normalColor))
        }
        // Paint gaps inside the full touch cell, avoiding dead strips between keys.
        background=InsetDrawable(background,3.dp,5.dp,3.dp,5.dp)
        isSoundEffectsEnabled=false // Feedback is emitted once, on press, rather than again on click.
        stateListAnimator=null
        elevation=0f
        layoutParams=LinearLayout.LayoutParams(0,
            prefs.getInt(KeyboardPreferences.KEY_HEIGHT,KeyboardPreferences.DEFAULT_KEY_HEIGHT).coerceIn(44,62).dp,weight)
        setOnTouchListener { view,event ->
            if(event.actionMasked==MotionEvent.ACTION_DOWN) pressFeedback(view)
            false
        }
        setOnClickListener { action?.invoke() ?: commit(label) }
    }

    private fun pressFeedback(view:View) {
        if(prefs.getBoolean(KeyboardPreferences.HAPTIC_FEEDBACK,true))
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        if(prefs.getBoolean(KeyboardPreferences.KEY_SOUND,true)) {
            view.isSoundEffectsEnabled=true
            view.playSoundEffect(SoundEffectConstants.CLICK)
            view.isSoundEffectsEnabled=false
        }
    }

    private fun keyShape(color:Int)=GradientDrawable().apply {
        cornerRadius=8.dp.toFloat()
        setColor(color)
    }

    private fun row(vararg keys:Button)=LinearLayout(this).apply {
        orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER; keys.forEach(::addView)
    }

    private fun render(){
        root.removeAllViews()
        if(numeric) renderNumeric() else {
            if(candidateHost.parent!=null) (candidateHost.parent as? LinearLayout)?.removeView(candidateHost)
            candidateHost.visibility=if(passwordField) View.GONE else View.VISIBLE
            root.addView(candidateHost)
            if(!passwordField) refreshCandidates()
            renderLetterKeys()
        }
    }

    private fun refreshCandidates(){
        if(passwordField) {
            candidateRevision++
            candidateJob?.cancel(true)
            scheduledRefresh?.let { mainHandler.removeCallbacks(it) }
            scheduledRefresh=null
            displayedRaw=""
            displayedWords=emptyList()
            displayedConsumption=emptyMap()
            return
        }
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
            if(chooseOnReady && words.isNotEmpty()) {
                chooseOnReady=false
                choose(words.first())
                return
            }
            renderCandidates(candidateHost,raw,words,all)
        }
        if(raw.isBlank()) {
            applyWords(listOf("「」","、","：","；"),emptyList(),emptyMap())
            return
        }
        // Old candidates may remain visible while loading, but cannot commit for
        // a different composing string. Coalesce rapid keys into one query/frame.
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
        if(host.childCount==0) {
            val bar=LinearLayout(this).apply {
                orientation=LinearLayout.HORIZONTAL
                gravity=Gravity.CENTER_VERTICAL
                setBackgroundColor(Color.rgb(225,227,232))
            }
            modeButton=Button(this).apply {
                textSize=13f; isAllCaps=false; minHeight=0; minimumHeight=0
                setTextColor(Color.rgb(70,70,74)); setSingleLine(true)
                layoutParams=LinearLayout.LayoutParams(52.dp,43.dp)
                setOnClickListener { flushRaw(); english=!english; render() }
            }
            bar.addView(modeButton)
            candidateScroll=HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled=false
                layoutParams=LinearLayout.LayoutParams(0,43.dp,1f)
            }
            candidateRow=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
            candidateScroll.addView(candidateRow); bar.addView(candidateScroll)
            expandButton=TextView(this).apply {
                textSize=24f; gravity=Gravity.CENTER
                setTextColor(Color.rgb(35,35,38))
                layoutParams=LinearLayout.LayoutParams(42.dp,43.dp)
                setOnClickListener {
                    if(composing.isNotBlank()) {
                        candidatesExpanded=!candidatesExpanded
                        if(!candidatesExpanded) collapseCandidatePanel()
                        refreshCandidates()
                    }
                }
            }
            bar.addView(expandButton); host.addView(bar)
        }
        // Keep the bar, scroll container and candidate targets mounted. Only
        // rebind labels; this avoids allocation/layout churn on every key.
        modeButton.text=if(english) "ABC" else "拼"
        expandButton.text=if(candidatesExpanded) "⌃" else "⌄"
        while(candidateRow.childCount<words.size) {
            candidateRow.addView(TextView(this).apply {
                textSize=20f; gravity=Gravity.CENTER; setPadding(18.dp,0,18.dp,0)
                minimumWidth=56.dp
                layoutParams=LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,43.dp)
                setTextColor(Color.rgb(38,38,42)); setBackgroundColor(Color.rgb(225,227,232))
                setSingleLine(true)
                trackCandidateTouch(this)
                setOnClickListener {
                    val target=tag as? CandidateTarget ?: return@setOnClickListener
                    if(target.raw!=composing) return@setOnClickListener
                    when {
                        target.raw.isNotBlank() -> choose(target.word)
                        target.word=="「」" -> { commit("「」"); send(KeyEvent.KEYCODE_DPAD_LEFT) }
                        target.word in listOf("、","：","；") -> punct(target.word)
                    }
                }
            })
        }
        for(i in 0 until candidateRow.childCount) {
            val view=candidateRow.getChildAt(i) as TextView
            if(i<words.size) {
                view.visibility=View.VISIBLE
                view.text=words[i]
                view.tag=CandidateTarget(raw,words[i])
            } else {
                view.visibility=View.GONE
                view.tag=null
            }
        }
        if(renderedRaw!=raw) candidateScroll.scrollTo(0,0)
        renderedRaw=raw
        while(host.childCount>1) host.removeViewAt(1)

        if(candidatesExpanded && composing.isNotBlank()){
            val grid=GridLayout(this).apply {
                columnCount=4
                setPadding(8.dp,4.dp,8.dp,8.dp)
                setBackgroundColor(Color.rgb(225,227,232))
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

    private fun collapseCandidatePanel() {
        candidatesExpanded=false
        if(::candidateHost.isInitialized) {
            while(candidateHost.childCount>1) candidateHost.removeViewAt(1)
        }
        if(::expandButton.isInitialized) expandButton.text="⌄"
    }

    private fun renderLetterKeys(){
        // Persistent punctuation row: available even while composing.
        val marks=if(english) listOf(",",".","?","!") else listOf("，","。","？","！")
        if(prefs.getBoolean(KeyboardPreferences.PUNCTUATION_ROW,false)) root.addView(row(*marks.map { mark ->
            key(mark){ punct(mark) }.apply {
                layoutParams=LinearLayout.LayoutParams(0,34.dp,1f).apply { setMargins(1.dp,1.dp,1.dp,1.dp) }
                textSize=20f
            }
        }.toTypedArray()))
        root.addView(row(*"qwertyuiop".map { c ->
            val label=if(shift)c.uppercase() else c.toString(); key(label){letter(label)}
        }.toTypedArray()))
        root.addView(LinearLayout(this).apply {
            orientation=LinearLayout.HORIZONTAL
            addView(View(this@ForestKeyboardService), LinearLayout.LayoutParams(0,1,0.5f))
            "asdfghjkl".forEach { c -> val label=if(shift)c.uppercase() else c.toString(); addView(key(label){letter(label)}) }
            addView(View(this@ForestKeyboardService), LinearLayout.LayoutParams(0,1,0.5f))
        })
        root.addView(row(
            key("⇧",1.35f){shift=!shift;render()},
            *"zxcvbnm".map { c -> val label=if(shift)c.uppercase() else c.toString(); key(label){letter(label)} }.toTypedArray(),
            key("⌫",1.35f){backspace()}
        ))
        root.addView(row(
            key("123",1.2f){flushRaw();numeric=true;render()},
            key("☺",1.2f){flushRaw();commit("☺")},
            key(if(english)"English" else "拼音",5.2f).also(::setupSpace),
            key("↵",2.4f){flushRaw();commit("\n")}
        ))
    }

    private fun letter(s:String){
        if(english || passwordField){ commit(s); return }
        collapseCandidatePanel()
        composing += s.lowercase()
        currentInputConnection.setComposingText(composing,1)
        refreshCandidates()
    }

    private fun candidateList(raw:String):List<String>{
        // Keep ranking dynamic so personal learning takes effect immediately.
        candidateCache[raw]?.let { return personalize(raw,it) }
        val dictionaryExact=pinyin[raw].orEmpty()
        val customExact=customByPinyin[raw].orEmpty()
        val customPredicted=customPrefixIndex[raw].orEmpty()
        val base = if(dictionaryExact.isNotEmpty() || customExact.isNotEmpty()) {
            (customExact + dictionaryExact + customPredicted).distinct().take(24)
        } else {
            // Cache dictionary order only; personal ranking stays dynamic.
            val exact=compactDictionary[raw].orEmpty()
            val abbreviated=if(raw.length>=2) initialsIndex[raw].orEmpty() else emptyList()
            val predicted=prefixCandidates(raw)
            val leading=leadingCandidates(raw)
            (customPredicted + exact + abbreviated + predicted.take(6) + leading.take(8) + predicted)
                .distinct().take(24).ifEmpty { listOf(raw) }
        }
        if(Thread.currentThread().isInterrupted) throw InterruptedException()
        candidateCache[raw]=base
        return personalize(raw,base)
    }

    private fun personalize(raw:String,base:List<String>):List<String> {
        val head=if(raw.isBlank()) null else
            syllablesByInitial[raw.first()].orEmpty().firstOrNull { raw.startsWith(it) }
        val learned=learnedByRaw[raw].orEmpty() + head?.let { learnedByRaw[it] }.orEmpty()
        val valid=learned.filter { compatible(raw,it) }
        // Merge before limiting so words chosen in the expanded panel can return
        // to the ordinary row even if absent from its cached dictionary bucket.
        return rank(raw,(base + valid).distinct()).take(24)
    }

    private fun leadingCandidates(raw:String):List<String> {
        if(raw.isBlank()) return emptyList()
        val head=syllablesByInitial[raw.first()].orEmpty().firstOrNull { raw.startsWith(it) }
            ?: return emptyList()
        return pinyin[head].orEmpty()
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
        out.addAll(customPrefixIndex[raw].orEmpty())
        out.addAll(customByPinyin[raw].orEmpty())
        out.addAll(compactDictionary[raw].orEmpty())
        out.addAll(initialsIndex[raw].orEmpty())
        out.addAll(prefixIndex[raw].orEmpty())
        pinyin[raw]?.let { out.addAll(rank(raw,it)) }
        // Offer actual dictionary entries, not Cartesian products of homophones.
        return rank(raw,out.distinct()).take(160).ifEmpty { listOf(raw) }
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
        if(consumed<raw.length) learn(raw,word)
        candidatesExpanded=false
        refreshCandidates()
    }

    private fun learn(raw:String, word:String){
        if(raw.isBlank() || word==raw || learningRestricted ||
            !prefs.getBoolean(KeyboardPreferences.AUTO_LEARNING,true) ||
            customPrefixIndex[raw].orEmpty().contains(word)) return
        val key="freq|" + raw + "|" + word
        prefs.edit().putInt(key,prefs.getInt(key,0)+1).apply()
        candidateWorker.submit {
            if(compatible(raw,word)) learnedByRaw.getOrPut(raw) { linkedSetOf() }.add(word)
        }
    }

    private fun rank(raw:String, words:List<String>):List<String>{
        val customExact=customByPinyin[raw].orEmpty().toHashSet()
        val customPredicted=customPrefixIndex[raw].orEmpty().toHashSet()
        val exactDictionary=compactDictionary[raw].orEmpty().toHashSet()
        val frequencies=words.associateWith { word ->
            val direct=prefs.getInt("freq|" + raw + "|" + word,0)
            val consumed=consumedLength(raw,word)
            val leading=if(consumed<raw.length)
                prefs.getInt("freq|" + raw.take(consumed) + "|" + word,0) else 0
            maxOf(direct,leading)
        }
        return words.withIndex()
            .sortedWith(compareBy<IndexedValue<String>> {
                when {
                    it.value in customExact -> 0
                    it.value in customPredicted -> 1
                    it.value in exactDictionary -> 2
                    else -> 3
                }
            }
                .thenByDescending { frequencies[it.value] ?: 0 }
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
        collapseCandidatePanel()
        if(composing.isNotEmpty()){
            composing=composing.dropLast(1)
            currentInputConnection.setComposingText(composing,1)
            if(composing.isEmpty()) currentInputConnection.finishComposingText()
            refreshCandidates()
        } else currentInputConnection.deleteSurroundingText(1,0)
    }

    private fun renderNumeric(){
        val left=arrayOf(arrayOf("#","RM","&","-"),arrayOf("@","(",")","="),arrayOf("+",":",";","%"),arrayOf("ABC","°","—","*"))
        val nums=arrayOf(arrayOf("1","2","3"),arrayOf("4","5","6"),arrayOf("7","8","9"),arrayOf(",","0","."))
        for(i in 0..3){
            val buttons=mutableListOf<Button>()
            left[i].forEach { s->buttons+=key(s,if(s=="ABC")1.25f else 1f){if(s=="ABC"){numeric=false;render()}else commit(s)} }
            nums[i].forEach { buttons+=key(it,1.15f) }
            buttons+=when(i){0->key("?");1->key("!");2->key("⌫"){currentInputConnection.deleteSurroundingText(1,0)};else->key("↵"){commit("\n")}}
            root.addView(row(*buttons.toTypedArray()))
        }
    }

    private fun setupSpace(v:Button){
        v.setOnTouchListener { _,e->
            when(e.action){
                MotionEvent.ACTION_DOWN->{spaceStartX=e.x;lastCursorStep=0;spaceDragged=false;v.isPressed=true;pressFeedback(v);true}
                MotionEvent.ACTION_MOVE->{
                    if(abs(e.x-spaceStartX)>=12.dp) spaceDragged=true
                    val step=((e.x-spaceStartX)/24.dp).toInt(); val diff=step-lastCursorStep
                    if(diff!=0){
                        if(composing.isNotEmpty()) { flushRaw(); refreshCandidates() }
                        val code=if(diff>0)KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT; repeat(abs(diff)){send(code)};lastCursorStep=step }
                    true
                }
                MotionEvent.ACTION_UP->{
                    v.isPressed=false
                    if(!spaceDragged && abs(e.x-spaceStartX)<12.dp){
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
                MotionEvent.ACTION_CANCEL->{v.isPressed=false;spaceDragged=false;lastCursorStep=0;true}
                else->false
            }
        }
    }

    private fun send(code:Int)=currentInputConnection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN,code))
    private fun commit(s:String)=currentInputConnection.commitText(s,1)
    private val Int.dp:Int get()=(this*resources.displayMetrics.density).toInt()
}

