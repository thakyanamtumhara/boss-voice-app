package com.ketu.boss

import android.content.Context
import android.util.Log
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

/**
 * Owns the offline speech model. Loading it costs a second or two and about
 * 60 MB of memory, so it is loaded once and kept for the life of the process.
 */
object VoskEngine {
    private const val TAG = "BossVosk"

    /** Bump when the bundled model changes, to force a re-unpack. */
    private const val MODEL_STAMP = "small-en-in-0.4"
    private const val ASSET = "vosk-model.zip"

    @Volatile private var model: Model? = null

    private fun root(ctx: Context) = File(ctx.filesDir, "vosk")
    private fun modelDir(ctx: Context) = File(root(ctx), "model")
    private fun stampFile(ctx: Context) = File(root(ctx), "stamp")

    fun isUnpacked(ctx: Context): Boolean =
        File(modelDir(ctx), "am/final.mdl").exists() &&
            runCatching { stampFile(ctx).readText().trim() }.getOrNull() == MODEL_STAMP

    /**
     * Unpacks the model if needed and loads it. Blocking — call from a worker
     * thread. Throws if the model cannot be prepared.
     */
    @Synchronized
    fun load(ctx: Context, onProgress: (String) -> Unit = {}): Model {
        model?.let { return it }
        if (!isUnpacked(ctx)) {
            onProgress("Unpacking the speech model…")
            unpack(ctx)
        }
        onProgress("Loading the speech model…")
        LibVosk.setLogLevel(LogLevel.WARNINGS)
        val m = Model(modelDir(ctx).absolutePath)
        model = m
        Log.i(TAG, "model loaded from ${modelDir(ctx)}")
        return m
    }

    private fun unpack(ctx: Context) {
        val root = root(ctx)
        // A half-finished unpack from a killed process must not be trusted.
        root.deleteRecursively()
        root.mkdirs()
        val canonicalRoot = root.canonicalPath
        ctx.assets.open(ASSET).use { raw ->
            ZipInputStream(raw.buffered()).use { zip ->
                var e = zip.nextEntry
                while (e != null) {
                    val out = File(root, e.name)
                    // Zip-slip guard: the model is ours, but a path check here
                    // costs nothing and keeps the extractor honest.
                    if (!out.canonicalPath.startsWith(canonicalRoot + File.separator)) {
                        throw SecurityException("bad zip entry ${e.name}")
                    }
                    if (e.isDirectory) {
                        out.mkdirs()
                    } else {
                        out.parentFile?.mkdirs()
                        FileOutputStream(out).use { fos -> zip.copyTo(fos, 64 * 1024) }
                    }
                    zip.closeEntry()
                    e = zip.nextEntry
                }
            }
        }
        check(File(modelDir(ctx), "am/final.mdl").exists()) { "model missing after unpack" }
        stampFile(ctx).writeText(MODEL_STAMP)
    }
}
