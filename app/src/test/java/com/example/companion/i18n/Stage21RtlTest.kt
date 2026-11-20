package com.example.companion.i18n

import com.example.companion.audio.AudioTransferFeatures
import com.example.companion.calls.CallFeatures
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 21: JVM tests for the RTL / LTR layout rules. WRITTEN BUT NOT RUN (no Gradle / Android SDK was available).
 * Views cannot be built on a plain JVM, so most of these are SOURCE checks (the code-based UI is plain Kotlin text) plus the pure
 * [TextDirection] helpers. How the screens actually look still needs a device; see app/STAGE21_NOTES.md.
 */
class Stage21RtlTest {

    private val en = AppLanguage.ENGLISH
    private val fa = AppLanguage.PERSIAN

    private fun moduleFile(path: String): File =
        listOf(File(path), File("app/$path")).first { it.exists() }

    private fun uiSources(): List<File> =
        moduleFile("src/main/java/com/example/companion/ui").listFiles { f -> f.name.endsWith(".kt") }!!.toList()

    /** Code lines only: comment and KDoc lines are dropped, so a sentence about "Gravity.LEFT" does not count. */
    private fun codeLines(f: File): List<Pair<Int, String>> =
        f.readLines().mapIndexed { i, l -> (i + 1) to l }.filter { (_, l) ->
            val t = l.trimStart(); !(t.startsWith("*") || t.startsWith("//") || t.startsWith("/*"))
        }

    // ---------------------------------------------------------------- no absolute left / right

    @Test fun uiCodeNeverUsesAbsoluteLeftRight() {
        val banned = Regex("""Gravity\.(LEFT|RIGHT)\b|\bleftMargin\b|\brightMargin\b|\bpaddingLeft\b|\bpaddingRight\b|setCompoundDrawables\(|setCompoundDrawablesWithIntrinsicBounds\(""")
        uiSources().forEach { f ->
            codeLines(f).forEach { (n, l) -> assertFalse("${f.name}:$n uses an absolute side: ${l.trim()}", banned.containsMatchIn(l)) }
        }
    }

    @Test fun paddingAndMarginsAreSymmetricOrRelative() {
        val call = Regex("""(setPadding|setMargins)\(([^()]*(?:\([^()]*\)[^()]*)*)\)""")
        uiSources().forEach { f ->
            codeLines(f).forEach { (n, l) ->
                call.findAll(l).forEach { m ->
                    val args = m.groupValues[2].split(Regex(""",(?![^()]*\))""")).map { it.trim() }
                    // Ui.kt's marginsRelative() passes (start, top, end, bottom) and sets marginStart / marginEnd as well.
                    if (args.size == 4 && args[0] != args[2] && !(args[0] == "start" && args[2] == "end"))
                        throw AssertionError("${f.name}:$n has different left and right values: ${l.trim()}")
                }
            }
        }
    }

    // ---------------------------------------------------------------- the three Stage 21 helper fixes

    private fun body(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertTrue("$signature not found", start >= 0)
        val open = source.indexOf('{', start)
        var depth = 0
        for (i in open until source.length) {
            if (source[i] == '{') depth++
            if (source[i] == '}') { depth--; if (depth == 0) return source.substring(open, i + 1) }
        }
        throw AssertionError("unbalanced braces after $signature")
    }

    @Test fun textAndPhoneHelpersTreatTextWithoutLettersAsLeftToRight() {
        val ui = moduleFile("src/main/java/com/example/companion/ui/Ui.kt").readText()
        listOf("fun TextView.followContentDirection()", "fun EditText.styleAsPhoneField()", "fun EditText.styleAsTextField()",
            "fun TextView.followLayoutStart()").forEach {
            val b = body(ui, it)
            assertTrue("$it must use firstStrongOrLtr()", b.contains("firstStrongOrLtr()"))
            assertFalse("$it must not set plain FIRST_STRONG", Regex("""textDirection\s*=\s*View\.TEXT_DIRECTION_FIRST_STRONG\b""").containsMatchIn(b))
        }
        // English keeps the plain rule (unchanged screens), Persian uses the "no letters = LTR" rule.
        val rule = body(ui, "fun TextView.firstStrongOrLtr()")
        assertTrue(rule.contains("!com.example.companion.i18n.AppLocale.isRtl -> View.TEXT_DIRECTION_FIRST_STRONG"))
        assertTrue(rule.contains("TEXT_DIRECTION_FIRST_STRONG_LTR"))
    }

    @Test fun notificationTitlesIsolateNamesAndNumbers() {
        val dir = "src/main/java/com/example/companion/ui/"
        assertTrue(moduleFile(dir + "SmsNotifier.kt").readText().contains("TextDirection.isolate(SmsNotificationState.senderTitle("))
        val calls = moduleFile(dir + "IncomingCallNotifier.kt").readText()
        assertTrue(calls.contains("TextDirection.isolate(c.caller"))
        assertTrue(calls.contains("TextDirection.isolate(c.secondary"))
    }

    // ---------------------------------------------------------------- every screen gets the app language's direction

    @Test fun everyScreenRootGetsTheAppDirectionAndTheManifestAllowsRtl() {
        val base = moduleFile("src/main/java/com/example/companion/i18n/LocalizedActivity.kt").readText()
        assertTrue(base.contains("view.applyAppLayoutDirection()"))
        assertTrue(base.contains("LocalizedContext.wrap(newBase, languageAtCreate)"))
        assertTrue(moduleFile("src/main/AndroidManifest.xml").readText().contains("android:supportsRtl=\"true\""))
        assertTrue(fa.isRtl); assertFalse(en.isRtl)
    }

    @Test fun nobodyCallsTheContentViewOverloadThatSkipsTheDirection() {
        // LocalizedActivity overrides setContentView(View) only; a layout-id or (view, params) call would skip applyAppLayoutDirection.
        val skipping = Regex("""setContentView\(\s*(R\.|[^()]*,\s*[^()]*LayoutParams)""")
        uiSources().forEach { f -> codeLines(f).forEach { (n, l) -> assertFalse("${f.name}:$n", skipping.containsMatchIn(l)) } }
    }

    // ---------------------------------------------------------------- mixed-language data (pure helpers)

    @Test fun isolateOnlyChangesPersianAndOnlyAddsInvisibleMarks() {
        val number = "+98 912 345 6789"
        assertEquals(number, TextDirection.isolate(number, en))
        val wrapped = TextDirection.isolate(number, fa)
        assertEquals("\u2068$number\u2069", wrapped)
        assertEquals(number, wrapped.removePrefix("\u2068").removeSuffix("\u2069"))
        assertEquals("", TextDirection.isolate("", fa))
    }

    @Test fun timesAndNumbersKeepTheirOrderInPersianLines() {
        val time = "11/20/26, 3:45 PM"
        assertEquals("\u2066$time\u2069", TextDirection.ltrData(time, fa))
        assertEquals(time, TextDirection.ltrData(time, en))
        assertEquals("۱۲", TextDirection.number(12, fa)); assertEquals("12", TextDirection.number(12, en))
        // Phone numbers are data: localizeDigits is for counts only and must leave English text alone.
        assertEquals("+98 912", TextDirection.localizeDigits("+98 912", en))
    }

    // ---------------------------------------------------------------- constraints

    @Test fun callingAudioHfpAndMagiskStayDisabled() {
        assertFalse(CallFeatures.isCallingEnabled())
        assertFalse(AudioTransferFeatures.isTransferEnabled())
        assertFalse(AudioTransferFeatures.HFP_ENABLED)
        assertFalse(AudioTransferFeatures.MAGISK_ENABLED)
    }
}
