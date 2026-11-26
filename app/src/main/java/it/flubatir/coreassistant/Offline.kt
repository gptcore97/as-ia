package it.flubatir.coreassistant

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.SpeechRecognizer
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.SpeechService
import java.io.File
import java.io.FilterInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream
import org.vosk.android.RecognitionListener as VoskListener

private val mainH = Handler(Looper.getMainLooper())

/**
 * Riconoscimento vocale OFFLINE (Vosk, modello italiano piccolo ~50 MB, scaricato una volta sola).
 * Non usa Google, non usa internet e non fa nessun "bip" di sistema.
 */
object Offline {
    const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-it-0.22.zip"

    @Volatile var downloading = false
    @Volatile var progress = 0
    @Volatile var lastError = ""
    private var model: Model? = null

    fun dir(c: Context) = File(c.applicationContext.filesDir, "vosk-it")
    fun ready(c: Context) = File(dir(c), "am/final.mdl").exists()

    @Synchronized
    fun model(c: Context): Model? {
        if (model == null && ready(c)) model = try { Model(dir(c).absolutePath) } catch (e: Throwable) { null }
        return model
    }

    @Synchronized
    fun delete(c: Context) {
        try { model?.close() } catch (e: Throwable) { }
        model = null
        dir(c).deleteRecursively()
    }

    /** Scarica e decomprime il modello. onDone viene chiamato (thread di rete) a fine operazione, riuscita o no. */
    fun download(c: Context, onDone: () -> Unit = {}) {
        if (downloading) return
        if (ready(c)) { onDone(); return }
        val app = c.applicationContext
        downloading = true; progress = 0; lastError = ""
        Thread {
            val tmp = File(app.filesDir, "vosk-it.tmp")
            try {
                tmp.deleteRecursively()
                val conn = URL(MODEL_URL).openConnection() as HttpURLConnection
                conn.connectTimeout = 20000; conn.readTimeout = 60000
                val total = conn.contentLengthLong
                var got = 0L
                val counting = object : FilterInputStream(conn.inputStream) {
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        val n = super.read(b, off, len)
                        if (n > 0) { got += n; if (total > 0) progress = (got * 100 / total).toInt().coerceAtMost(99) }
                        return n
                    }
                }
                ZipInputStream(counting).use { zin ->
                    while (true) {
                        val e = zin.nextEntry ?: break
                        val name = e.name.substringAfter('/', "")   // toglie la cartella radice dello zip
                        if (name.isEmpty()) continue
                        val f = File(tmp, name)
                        if (!f.canonicalPath.startsWith(tmp.canonicalPath)) continue
                        if (e.isDirectory) f.mkdirs() else { f.parentFile?.mkdirs(); f.outputStream().use { o -> zin.copyTo(o) } }
                    }
                }
                conn.disconnect()
                val d = dir(app)
                d.deleteRecursively()
                if (!File(tmp, "am/final.mdl").exists() || !tmp.renameTo(d)) throw Exception("modello non valido o impossibile da salvare")
                progress = 100
            } catch (e: Exception) {
                lastError = e.message ?: e.javaClass.simpleName
                tmp.deleteRecursively()
            } finally {
                downloading = false
            }
            try { onDone() } catch (e: Exception) { }
        }.start()
    }
}

private fun jtext(s: String?, key: String): String = try { JSONObject(s ?: "{}").optString(key, "").trim() } catch (e: Exception) { "" }

/** Ascolto di un comando (una frase): stessa interfaccia del riconoscimento Google. */
class OfflineListener(private val ctx: Context, private val cb: Listener.Callbacks) {
    private var svc: SpeechService? = null
    private var rec: Recognizer? = null
    private var done = false

    fun start() {
        stop()
        val m = Offline.model(ctx)
        if (m == null) { cb.onError(SpeechRecognizer.ERROR_CLIENT); return }
        done = false
        try {
            val r = Recognizer(m, 16000.0f)
            val s = SpeechService(r, 16000.0f)
            rec = r; svc = s
            val ok = s.startListening(object : VoskListener {
                override fun onPartialResult(h: String?) {
                    val t = jtext(h, "partial")
                    if (t.isNotEmpty() && !done) { cb.onLevel(0.55f); cb.onPartial(t) }
                }
                override fun onResult(h: String?) {
                    val t = jtext(h, "text")
                    if (t.isNotEmpty() && !done) { done = true; cb.onFinal(t); mainH.post { stop() } }
                }
                override fun onFinalResult(h: String?) {}
                override fun onError(e: Exception?) { if (!done) { done = true; cb.onError(SpeechRecognizer.ERROR_CLIENT) } }
                override fun onTimeout() { if (!done) { done = true; cb.onError(SpeechRecognizer.ERROR_SPEECH_TIMEOUT); mainH.post { stop() } } }
            }, 9000)
            if (!ok) { cb.onError(SpeechRecognizer.ERROR_RECOGNIZER_BUSY); return }
            cb.onReady()
        } catch (e: Throwable) {
            cb.onError(SpeechRecognizer.ERROR_CLIENT)
        }
    }

    fun stop() {
        val s = svc; val r = rec
        svc = null; rec = null
        try { s?.stop(); s?.shutdown() } catch (e: Throwable) { }
        try { r?.close() } catch (e: Throwable) { }
    }
}

/** Ascolto continuo per "Ehi Core": non fa bip. onText riceve ogni frase riconosciuta (thread principale). */
class OfflineWake(private val ctx: Context, private val onText: (String) -> Unit) {
    private var svc: SpeechService? = null
    private var rec: Recognizer? = null

    fun start(): Boolean {
        stop()
        val m = Offline.model(ctx) ?: return false
        return try {
            val r = Recognizer(m, 16000.0f)
            val s = SpeechService(r, 16000.0f)
            rec = r; svc = s
            s.startListening(object : VoskListener {
                override fun onPartialResult(h: String?) {}
                override fun onResult(h: String?) { val t = jtext(h, "text"); if (t.isNotEmpty()) onText(t) }
                override fun onFinalResult(h: String?) {}
                override fun onError(e: Exception?) { mainH.post { stop() } }
                override fun onTimeout() {}
            })
        } catch (e: Throwable) { false }
    }

    val active get() = svc != null

    fun stop() {
        val s = svc; val r = rec
        svc = null; rec = null
        try { s?.stop(); s?.shutdown() } catch (e: Throwable) { }
        try { r?.close() } catch (e: Throwable) { }
    }
}
