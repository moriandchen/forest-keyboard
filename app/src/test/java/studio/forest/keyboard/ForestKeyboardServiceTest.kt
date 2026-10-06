package studio.forest.keyboard

import android.os.Looper
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.widget.LinearLayout
import android.widget.TextView
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ForestKeyboardServiceTest {
    private lateinit var service: ForestKeyboardService
    private lateinit var connection: BaseInputConnection

    @Before fun setup() {
        service=Robolectric.buildService(ForestKeyboardService::class.java).create().get()
        connection=BaseInputConnection(View(service),true)
        val field=android.inputmethodservice.InputMethodService::class.java
            .getDeclaredField("mStartedInputConnection")
        field.isAccessible=true
        field.set(service,connection)
        service.onCreateInputView()
        drain()
    }

    @After fun cleanup() { service.onDestroy() }

    private fun invoke(name:String, vararg args:String): Any? {
        val method=ForestKeyboardService::class.java.getDeclaredMethod(name,*args.map { String::class.java }.toTypedArray())
        method.isAccessible=true
        return method.invoke(service,*args)
    }

    private fun field(name:String): Any? {
        val field=ForestKeyboardService::class.java.getDeclaredField(name)
        field.isAccessible=true
        return field.get(service)
    }

    private fun drain() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
        (field("candidateWorker") as ExecutorService).submit {}.get(10,TimeUnit.SECONDS)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun selectedChineseReplacesPinyin() {
        "pengyou".forEach { invoke("letter",it.toString()) }
        invoke("choose","朋友")
        drain()
        assertEquals("朋友",connection.editable.toString())
        assertEquals("",field("composing"))
        assertEquals(false,field("candidatesExpanded"))
    }

    @Test fun switchingModeDoesNotDuplicateRawPinyin() {
        invoke("letter","w"); invoke("letter","o")
        invoke("flushRaw")
        drain()
        assertEquals("wo",connection.editable.toString())
        assertEquals("",field("composing"))
    }

    @Test fun backspaceDeletesLastComposingLetter() {
        invoke("letter","w"); invoke("letter","o")
        invoke("backspace"); invoke("backspace")
        drain()
        assertEquals("",connection.editable.toString())
        assertEquals("",field("composing"))
    }

    @Test fun punctuationReplacesSpanOnceAndClearsPendingResults() {
        invoke("letter","w"); invoke("letter","o")
        invoke("punct","，")
        drain()
        assertEquals("wo，",connection.editable.toString())
        assertEquals("",field("displayedRaw"))
    }

    @Test fun rapidInputOnlyDisplaysLatestQuery() {
        "jingshenlingxiu".forEach { invoke("letter",it.toString()) }
        drain()
        assertEquals("jingshenlingxiu",field("displayedRaw"))
        assertTrue((field("displayedWords") as List<*>).contains("精神領袖"))
    }

    @Test fun candidateFromOldQueryCannotCommit() {
        invoke("letter","w"); invoke("letter","o"); drain()
        val host=field("candidateHost") as LinearLayout
        val bar=host.getChildAt(0) as LinearLayout
        val scroll=bar.getChildAt(1) as android.widget.HorizontalScrollView
        val words=scroll.getChildAt(0) as LinearLayout
        val oldCandidate=words.getChildAt(0) as TextView
        invoke("letter","m")
        oldCandidate.performClick()
        assertEquals("wom",field("composing"))
        assertEquals("wom",connection.editable.toString())
        drain()
    }

    @Test fun expandedListPreservesLivePrefixCandidates() {
        (field("candidateWorker") as ExecutorService).submit {
            @Suppress("UNCHECKED_CAST")
            val regular=invoke("candidateList","xih") as List<String>
            @Suppress("UNCHECKED_CAST")
            val expanded=invoke("expandedCandidateList","xih") as List<String>
            assertTrue(regular.contains("喜歡"))
            assertTrue(expanded.containsAll(regular))
        }.get(10,TimeUnit.SECONDS)
    }

    @Test fun invalidLongPrefixDoesNotMatchEightLetterBucket() {
        (field("candidateWorker") as ExecutorService).submit {
            @Suppress("UNCHECKED_CAST")
            val words=invoke("prefixCandidates","jingshenzzz") as List<String>
            assertFalse(words.contains("精神"))
            assertFalse(words.contains("精神領袖"))
        }.get(10,TimeUnit.SECONDS)
    }

    @Test fun selectOneCharacterAtATimeWithoutLosingSuffix() {
        "buzhidao".forEach { invoke("letter",it.toString()) }; drain()
        assertTrue((field("displayedWords") as List<*>).contains("不知道"))
        assertTrue((field("displayedWords") as List<*>).contains("不"))
        invoke("choose","不"); drain()
        assertEquals("zhidao",field("composing"))
        assertEquals("不zhidao",connection.editable.toString())
        invoke("choose","知"); drain()
        assertEquals("dao",field("composing"))
        assertEquals("不知dao",connection.editable.toString())
        invoke("choose","道"); drain()
        assertEquals("",field("composing"))
        assertEquals("不知道",connection.editable.toString())
    }

    @Test fun geiwoCanBeSelectedSeparately() {
        "geiwo".forEach { invoke("letter",it.toString()) }; drain()
        assertTrue((field("displayedWords") as List<*>).contains("給我"))
        invoke("choose","給"); drain()
        assertEquals("wo",field("composing"))
        assertEquals("給wo",connection.editable.toString())
        invoke("choose","我"); drain()
        assertEquals("給我",connection.editable.toString())
    }

    @Test fun partialSyllableRemainsAfterChoosingLeadingCharacter() {
        "xih".forEach { invoke("letter",it.toString()) }; drain()
        invoke("choose","喜"); drain()
        assertEquals("h",field("composing"))
        assertEquals("喜h",connection.editable.toString())
    }

    @Test fun wholePhraseStillCommitsInOneTap() {
        "buzhidao".forEach { invoke("letter",it.toString()) }; drain()
        invoke("choose","不知道"); drain()
        assertEquals("",field("composing"))
        assertEquals("不知道",connection.editable.toString())
    }


    private fun expand() {
        val host=field("candidateHost") as LinearLayout
        val bar=host.getChildAt(0) as LinearLayout
        bar.getChildAt(2).performClick()
        drain()
        assertEquals(true,field("candidatesExpanded"))
        assertEquals(2,host.childCount)
    }

    @Test fun typingClosesPanelImmediatelyAndDoesNotReopenIt() {
        "shi".forEach { invoke("letter",it.toString()) }; drain(); expand()
        val host=field("candidateHost") as LinearLayout
        invoke("letter","j")
        assertEquals(false,field("candidatesExpanded"))
        assertEquals(1,host.childCount)
        drain()
        assertEquals(1,host.childCount)
        invoke("letter","i"); drain()
        assertEquals(1,host.childCount)
    }

    @Test fun backspaceClosesExpandedPanel() {
        "shi".forEach { invoke("letter",it.toString()) }; drain(); expand()
        invoke("backspace")
        assertEquals(false,field("candidatesExpanded"))
        assertEquals(1,(field("candidateHost") as LinearLayout).childCount)
        drain()
    }

    @Test fun typingReusesCandidateBarAndTargets() {
        invoke("letter","w"); drain()
        val host=field("candidateHost") as LinearLayout
        val bar=host.getChildAt(0)
        val row=field("candidateRow") as LinearLayout
        val first=row.getChildAt(0)
        invoke("letter","o"); drain()
        assertSame(bar,host.getChildAt(0))
        assertSame(first,row.getChildAt(0))
    }

    @Test fun ordinaryTypingDoesNotRunRecursiveSegmentation() {
        "buzhidao".forEach { invoke("letter",it.toString()) }; drain()
        assertEquals(0,(field("segmentationCache") as Map<*,*>).size)
    }

    @Test fun expansionShowsCandidatesBeyondOldPrefixLimit() {
        (field("candidateWorker") as ExecutorService).submit {
            @Suppress("UNCHECKED_CAST")
            val expanded=invoke("expandedCandidateList","b") as List<String>
            assertTrue(expanded.size>24)
        }.get(10,TimeUnit.SECONDS)
    }

    @Test fun completePhraseStaysFirstDespiteFrequentLeadingWord() {
        repeat(20) { invoke("learn","bu","不") }
        val words=invoke("candidateList","buzhidao") as List<*>
        assertEquals("不知道",words.first())
    }

    @Test fun partialLearningChangesCachedCandidateOrder() {
        invoke("candidateList","geiwo")
        repeat(5) { invoke("learn","gei","給") }
        val words=invoke("candidateList","geiwo") as List<*>
        assertEquals("給我",words.first())
        assertEquals("給",words[1])
    }

    @Test fun expansionDoesNotInventHomophonePhrases() {
        val words=invoke("expandedCandidateList","buzhidao") as List<*>
        assertTrue(words.contains("不知道"))
        assertFalse(words.contains("不之到"))
        assertFalse(words.contains("不只倒"))
    }

    @Test fun everydayPhrasesHaveExactFirstCandidate() {
        val examples=mapOf("jintian" to "今天", "keyi" to "可以", "xihuan" to "喜歡",
            "pengyou" to "朋友", "xuesheng" to "學生", "laoshi" to "老師",
            "kuaile" to "快樂", "haode" to "好的", "xiexie" to "謝謝",
            "wanan" to "晚安", "meiguanxi" to "沒關係", "geiwo" to "給我",
            "buzhidao" to "不知道")
        examples.forEach { (raw,word) ->
            assertEquals(raw,word,(invoke("candidateList",raw) as List<*>).first())
        }
    }

    @Test fun idleShortcutsActuallyInsertPunctuation() {
        val row=field("candidateRow") as LinearLayout
        assertEquals("「」",(row.getChildAt(0) as TextView).text.toString())
        row.getChildAt(1).performClick()
        assertEquals("、",connection.editable.toString())
    }

}
