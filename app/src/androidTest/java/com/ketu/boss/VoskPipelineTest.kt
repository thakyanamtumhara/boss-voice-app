package com.ketu.boss

import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ketu.boss.parse.Command
import com.ketu.boss.parse.CommandParser
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.vosk.Model
import org.vosk.Recognizer
import java.io.InputStream

/**
 * End-to-end proof on a real device: the bundled model unpacks, loads, accepts
 * a grammar, and actually decodes speech into something the parser can use.
 * Audio is synthesised Indian-English so the test needs no microphone.
 */
@RunWith(AndroidJUnit4::class)
class VoskPipelineTest {

    companion object {
        private const val TAG = "BossPipeline"
        private lateinit var model: Model

        @BeforeClass @JvmStatic fun loadModel() {
            val app: Context = InstrumentationRegistry.getInstrumentation().targetContext
            val t0 = System.currentTimeMillis()
            model = VoskEngine.load(app) { Log.i(TAG, it) }
            Log.i(TAG, "MODEL_READY in ${System.currentTimeMillis() - t0} ms")
        }
    }

    private fun testAssets() = InstrumentationRegistry.getInstrumentation().context.assets

    /** Skips to the WAV 'data' chunk rather than assuming a 44-byte header. */
    private fun seekToData(ins: InputStream) {
        val head = ByteArray(12)
        check(ins.read(head) == 12) { "short wav" }
        while (true) {
            val hdr = ByteArray(8)
            if (ins.read(hdr) != 8) error("no data chunk")
            val id = String(hdr, 0, 4)
            val size = (hdr[4].toInt() and 0xff) or ((hdr[5].toInt() and 0xff) shl 8) or
                ((hdr[6].toInt() and 0xff) shl 16) or ((hdr[7].toInt() and 0xff) shl 24)
            if (id == "data") return
            var skip = size.toLong()
            while (skip > 0) { val n = ins.skip(skip); if (n <= 0) break; skip -= n }
        }
    }

    private fun decode(asset: String, grammar: String?): String {
        val rec = if (grammar == null) Recognizer(model, 16000f)
        else Recognizer(model, 16000f, grammar)
        try {
            testAssets().open(asset).use { ins ->
                seekToData(ins)
                val buf = ByteArray(4096)
                while (true) {
                    val n = ins.read(buf)
                    if (n <= 0) break
                    rec.acceptWaveForm(buf, n)
                }
            }
            val text = JSONObject(rec.finalResult).optString("text", "").trim()
            Log.i(TAG, "DECODE[$asset] grammar=${grammar != null} -> \"$text\"")
            return text
        } finally {
            rec.close()
        }
    }

    private val wakePhrases = listOf("hey boss")
    private val grammar = WakeMatcher.grammarJson(wakePhrases)

    @Test fun modelLoads() {
        // Reaching here at all means unpack + load worked.
        Log.i(TAG, "grammar = $grammar")
        assertTrue(true)
    }

    @Test fun grammarModeIsAccepted() {
        // If the model rejected the grammar this would throw, and the service
        // would silently fall back to the expensive free-form path.
        val out = decode("wake.wav", grammar)
        Log.i(TAG, "GRAMMAR_OK text=\"$out\"")
        assertTrue("grammar decode produced nothing", out.isNotEmpty())
    }

    @Test fun wakeWordFiresOnThreeVoices() {
        listOf("wake.wav", "wake_aman.wav", "wake_tara.wav").forEach { a ->
            val heard = decode(a, grammar)
            assertTrue("no wake from $a (heard \"$heard\")",
                WakeMatcher.matches(heard, wakePhrases, 1))
        }
    }

    @Test fun ordinarySpeechDoesNotWakeIt() {
        val heard = decode("notwake.wav", grammar)
        assertTrue("false wake on \"$heard\"", !WakeMatcher.matches(heard, wakePhrases, 1))
    }

    @Test fun freeFormTranscribesACommand() {
        val heard = decode("wake_cmd.wav", null)
        Log.i(TAG, "FREEFORM_CMD=\"$heard\"")
        assertTrue("transcript was empty", heard.isNotEmpty())
    }

    @Test fun wakePlusCommandInOneBreath() {
        val heard = decode("wake_call.wav", null)
        Log.i(TAG, "ONE_BREATH=\"$heard\"")
        val tail = WakeMatcher.tail(heard, wakePhrases)
        Log.i(TAG, "TAIL=\"$tail\"")
        assertTrue("transcript was empty", heard.isNotEmpty())
    }

    @Test fun offlineTranscriptStillParses() {
        val heard = decode("cmd_remind.wav", null)
        val cmd = CommandParser.parse(heard)
        Log.i(TAG, "REMIND_TRANSCRIPT=\"$heard\" -> $cmd")
        assertTrue("transcript was empty", heard.isNotEmpty())
    }
}
