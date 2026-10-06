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

}
