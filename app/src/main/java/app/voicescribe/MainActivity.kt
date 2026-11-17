package app.voicescribe

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.WindowCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // palette
    private var dark = false
    private val bg get() = if (dark) 0xFF0F1115.toInt() else 0xFFF4F5FA.toInt()
    private val card get() = if (dark) 0xFF1A1D24.toInt() else Color.WHITE
    private val ink get() = if (dark) 0xFFECEEF3.toInt() else 0xFF14161C.toInt()
    private val muted get() = if (dark) 0xFF9AA0AE.toInt() else 0xFF6B7280.toInt()
    private val accent = 0xFF5B5BF0.toInt()
    private val accentSoft get() = if (dark) 0xFF272A4A.toInt() else 0xFFE8E8FD.toInt()

    private lateinit var status: TextView
    private lateinit var hint: LinearLayout
    private lateinit var historyCard: LinearLayout
    private val history by lazy { HistoryStore(this) }
    private var expanded: Long? = null
    private lateinit var progress: ProgressBar
    private lateinit var download: Button
    private lateinit var store: ModelStore
    private var pending: Uri? = null
    private var busy = false
    private val prefs by lazy { getSharedPreferences("prefs", MODE_PRIVATE) }
    private lateinit var chipSmall: TextView
    private lateinit var chipMedium: TextView
    private lateinit var chipHigh: TextView
    private lateinit var serverCard: LinearLayout
    private lateinit var serverInfo: TextView
    private lateinit var codeInput: EditText
    private lateinit var pairBtn: Button
    private lateinit var serverLinks: LinearLayout
    private val server by lazy { ServerClient(this) }
    private var variant = "small"
    private lateinit var langRow: TextView

    /** Whisper language codes offered in the picker; "auto" lets the model detect the language. */
    private val languages = listOf(
        "auto" to "Automatisch erkennen", "de" to "Deutsch", "en" to "English", "fr" to "Français",
        "es" to "Español", "it" to "Italiano", "pt" to "Português", "nl" to "Nederlands",
        "pl" to "Polski", "tr" to "Türkçe", "ru" to "Русский", "uk" to "Українська",
        "ar" to "العربية", "zh" to "中文", "ja" to "日本語", "ko" to "한국어",
    )

    private fun lang(): String = prefs.getString("lang", "de")?.takeIf { c -> languages.any { it.first == c } } ?: "de"

    private fun updateLangRow() {
        val name = languages.first { it.first == lang() }.second
        langRow.text = "Sprache:  $name  ▾"
    }

    private fun pickLanguage() {
        val names = languages.map { it.second }.toTypedArray()
        android.app.AlertDialog.Builder(this)
            .setTitle("Sprache der Nachricht")
            .setSingleChoiceItems(names, languages.indexOfFirst { it.first == lang() }) { d, which ->
                prefs.edit().putString("lang", languages[which].first).apply()
                updateLangRow()
                d.dismiss()
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
        if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun tv(size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        textSize = size; setTextColor(color)
        if (bold) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private fun pill(label: String, filled: Boolean, onClick: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 15f
        stateListAnimator = null
        setTextColor(if (filled) Color.WHITE else accent)
        background = if (filled) rounded(accent, 14) else rounded(Color.TRANSPARENT, 14, accent)
        setPadding(dp(18), dp(12), dp(18), dp(12))
        setOnClickListener { onClick() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val crashFile = java.io.File(filesDir, "crash.txt")
        Thread.setDefaultUncaughtExceptionHandler { _, e -> crashFile.writeText(android.util.Log.getStackTraceString(e)) }
        try { setup(crashFile) } catch (e: Throwable) {
            setContentView(TextView(this).apply {
                text = "Startfehler:\n" + android.util.Log.getStackTraceString(e)
                setPadding(48, 160, 48, 48); setTextIsSelectable(true)
            })
        }
    }

    private fun setup(crashFile: java.io.File) {
        dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        window.statusBarColor = bg
        window.navigationBarColor = bg
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
        variant = prefs.getString("variant", "small") ?: "small"
        // `store` always holds the last chosen LOCAL model, so switching away from "high" is instant
        store = ModelStore(this, if (variant == "high") prefs.getString("local", "small") ?: "small" else variant)

        // header
        val logo = TextView(this).apply {
            text = "🎙"; textSize = 22f; gravity = Gravity.CENTER
            background = rounded(accent, 16)
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
        }
        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, 0, 0)
            addView(tv(24f, ink, true).apply { text = "VoiceScribe" })
            addView(tv(13f, muted).apply { text = "Sprachnachrichten lokal in Text umwandeln" })
        }
        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(logo); addView(titles)
        }

        status = tv(15f, muted).apply {
            if (crashFile.exists()) {
                text = "Letzter Absturz:\n" + crashFile.readText().also { crashFile.delete() }
                setTextColor(0xFFD64545.toInt())
            }
        }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000; visibility = View.GONE
            progressTintList = ColorStateList.valueOf(accent)
            indeterminateTintList = ColorStateList.valueOf(accent)
            progressBackgroundTintList = ColorStateList.valueOf(accentSoft)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6)).apply {
                topMargin = dp(10); bottomMargin = dp(4)
            }
        }
        download = pill("Sprachmodell laden (ca. 375 MB)", true) { downloadModel() }.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(12) }
        }

        // model chooser
        fun chip(label: String, variant: String) = TextView(this).apply {
            text = label; textSize = 14f; gravity = Gravity.CENTER
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setOnClickListener { selectVariant(variant) }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        chipSmall = chip("Schnell", "small")
        chipMedium = chip("Genauer", "medium")
        chipHigh = chip("Hoch ☁", "high")
        val chooser = LinearLayout(this).apply {
            background = rounded(accentSoft, 16)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            addView(chipSmall); addView(chipMedium); addView(chipHigh)
        }
        styleChips()

        // language picker
        langRow = tv(14f, ink).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(card, 14)
            elevation = dp(1).toFloat()
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setOnClickListener { pickLanguage() }
        }
        updateLangRow()

        // server card (only visible in mode "Hoch")
        serverInfo = tv(14f, muted)
        codeInput = EditText(this).apply {
            hint = "Kopplungscode einfügen (vs1:…)"
            setHintTextColor(muted); setTextColor(ink); textSize = 13f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            minLines = 2; maxLines = 4
            background = rounded(accentSoft, 12)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) }
        }
        pairBtn = pill("Verbinden", true) { pair() }.apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) }
        }
        serverLinks = LinearLayout(this).apply {
            setPadding(0, dp(6), 0, 0)
            addView(link("Verbindung testen") { testServer() })
            addView(link("Trennen") { confirmUnpair() })
        }
        serverCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            background = rounded(card, 20)
            elevation = dp(2).toFloat()
            setPadding(dp(18), dp(14), dp(18), dp(10))
            addView(tv(13f, muted, true).apply { text = "SERVER"; letterSpacing = 0.08f })
            addView(serverInfo.apply { setPadding(0, dp(6), 0, 0) })
            addView(codeInput); addView(pairBtn); addView(serverLinks)
            addView(tv(12f, muted).apply {
                text = "Im Modus Hoch wird die Audiodatei an deinen eigenen Server gesendet und dort transkribiert."
                setPadding(0, dp(10), 0, dp(4))
            })
        }

        // status card
        val statusCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(card, 20)
            elevation = dp(2).toFloat()
            setPadding(dp(18), dp(16), dp(18), dp(16))
            addView(status); addView(progress); addView(download)
        }

        // how-to hint
        fun step(n: String, text: String) = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
            addView(TextView(this@MainActivity).apply {
                this.text = n; setTextColor(accent); textSize = 14f; gravity = Gravity.CENTER
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                background = rounded(accentSoft, 12)
                layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)).apply { rightMargin = dp(12) }
            })
            addView(tv(15f, ink).apply { this.text = text })
        }
        hint = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(card, 20)
            elevation = dp(2).toFloat()
            setPadding(dp(18), dp(14), dp(18), dp(14))
            addView(tv(13f, muted, true).apply { text = "SO GEHT'S"; letterSpacing = 0.08f })
            addView(step("1", "Sprachnachricht in WhatsApp lange drücken"))
            addView(step("2", "Teilen wählen"))
            addView(step("3", "VoiceScribe auswählen"))
        }

        // history card (filled by renderHistory)
        historyCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            background = rounded(card, 20)
            elevation = dp(2).toFloat()
            setPadding(dp(18), dp(14), dp(18), dp(10))
        }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(20), dp(18), dp(28))
            addView(header)
            val gap = { LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18) } }
            addView(chooser, gap())
            addView(langRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
            addView(serverCard, gap())
            addView(statusCard, gap()); addView(hint, gap()); addView(historyCard, gap())
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(bg); isFillViewport = true
            addView(column)
        })
        renderHistory()
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun copyText(text: String) {
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("transcript", text))
        Toast.makeText(this, "Kopiert", Toast.LENGTH_SHORT).show()
    }

    private fun shareText(text: String) {
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text)
        }, null))
    }

    private fun link(label: String, onClick: () -> Unit) = tv(14f, accent, true).apply {
        text = label
        setPadding(0, dp(8), dp(20), dp(8))
        setOnClickListener { onClick() }
    }

    private fun renderHistory() {
        val list = history.load()
        historyCard.removeAllViews()
        historyCard.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
        if (!busy) hint.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        if (list.isEmpty()) return

        historyCard.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(tv(13f, muted, true).apply { text = "VERLAUF"; letterSpacing = 0.08f },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(tv(14f, accent, true).apply {
                text = "Alle löschen"; setPadding(dp(10), dp(6), 0, dp(6))
                setOnClickListener { confirmClear() }
            })
        })
        list.forEachIndexed { i, e ->
            if (i > 0) historyCard.addView(View(this).apply {
                setBackgroundColor(if (dark) 0xFF2A2E38.toInt() else 0xFFE5E7EB.toInt())
                layoutParams = LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(6) }
            })
            historyCard.addView(entryView(e))
        }
    }

    private fun entryView(e: Entry): View {
        val open = e.id == expanded
        val fmt = java.text.SimpleDateFormat("d.M. HH:mm", java.util.Locale.GERMANY)
        val toggle = { expanded = if (open) null else e.id; renderHistory() }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, dp(2))
            addView(LinearLayout(this@MainActivity).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(tv(12f, muted).apply {
                    text = "${fmt.format(java.util.Date(e.id))} · ${e.seconds} s · ${e.model}"
                    setOnClickListener { toggle() }
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(tv(18f, muted).apply {
                    text = "✕"; contentDescription = "Löschen"
                    setPadding(dp(16), dp(4), dp(2), dp(4))
                    setOnClickListener {
                        history.delete(e.id)
                        if (expanded == e.id) expanded = null
                        renderHistory()
                    }
                })
            })
            addView(tv(16f, ink).apply {
                text = e.text
                setLineSpacing(0f, 1.2f)
                setPadding(0, dp(4), 0, dp(4))
                if (open) setTextIsSelectable(true) else {
                    maxLines = 3; ellipsize = android.text.TextUtils.TruncateAt.END
                    setOnClickListener { toggle() }
                }
            })
            if (open) addView(LinearLayout(this@MainActivity).apply {
                addView(link("Kopieren") { copyText(e.text) })
                addView(link("Teilen") { shareText(e.text) })
                addView(link("Zuklappen") { toggle() })
            })
        }
    }

    private fun confirmClear() {
        android.app.AlertDialog.Builder(
            this,
            if (dark) android.R.style.Theme_Material_Dialog_Alert else android.R.style.Theme_Material_Light_Dialog_Alert,
        )
            .setMessage("Alle gespeicherten Transkripte löschen?")
            .setPositiveButton("Löschen") { _, _ -> history.clear(); expanded = null; renderHistory() }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    @Suppress("DEPRECATION")
    private fun handle(intent: Intent) {
        if (intent.action == Intent.ACTION_SEND && intent.hasExtra(Intent.EXTRA_STREAM)) {
            pending = intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
        refresh()
    }

    private fun styleChips() {
        val sel = variant
        for ((chip, v) in listOf(chipSmall to "small", chipMedium to "medium", chipHigh to "high")) {
            val on = v == sel
            chip.setTextColor(if (on) Color.WHITE else muted)
            chip.typeface = Typeface.create(Typeface.DEFAULT, if (on) Typeface.BOLD else Typeface.NORMAL)
            chip.background = if (on) rounded(accent, 12) else null
        }
    }

    private fun selectVariant(v: String) {
        if (busy || v == variant) return
        prefs.edit().putString("variant", v).apply()
        if (v != "high") {
            prefs.edit().putString("local", v).apply()
            store = ModelStore(this, v)
        }
        variant = v
        styleChips()
        refresh()
    }

    private fun updateServerCard() {
        val p = server.pairing()
        serverCard.visibility = if (variant == "high") View.VISIBLE else View.GONE
        codeInput.visibility = if (p == null) View.VISIBLE else View.GONE
        pairBtn.visibility = if (p == null) View.VISIBLE else View.GONE
        serverLinks.visibility = if (p == null) View.GONE else View.VISIBLE
        serverInfo.text = if (p == null)
            "Noch nicht gekoppelt. Füge den Kopplungscode ein, den dein Server ausgibt (manage.py add-device)."
        else buildString {
            append("Gekoppelt als „${p.name}“")
            if (p.lan.isNotEmpty()) append("\nLokal: ${p.lan}")
            if (p.remote.isNotEmpty()) append("\nInternet: ${p.remote}")
        }
    }

    private fun pair() {
        val code = codeInput.text.toString()
        val p = try { Pairing.parse(code) } catch (e: IllegalArgumentException) {
            status.text = e.message; return
        }
        server.save(p)
        codeInput.setText("")
        updateServerCard()
        testServer()
    }

    private fun testServer() {
        status.text = "Teste Verbindung …"
        progress.isIndeterminate = true
        progress.visibility = View.VISIBLE
        scope.launch {
            val msg = withContext(Dispatchers.IO) { try { server.test() } catch (e: Exception) { "Fehler: ${e.message}" } }
            progress.visibility = View.GONE
            status.text = msg
        }
    }

    private fun confirmUnpair() {
        android.app.AlertDialog.Builder(
            this,
            if (dark) android.R.style.Theme_Material_Dialog_Alert else android.R.style.Theme_Material_Light_Dialog_Alert,
        )
            .setMessage("Kopplung auf diesem Handy entfernen? Das Gerät bleibt auf dem Server eingetragen, bis du es dort widerrufst.")
            .setPositiveButton("Entfernen") { _, _ -> server.clear(); updateServerCard(); refresh() }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun refresh() {
        updateServerCard()
        if (variant == "high") {
            download.visibility = View.GONE
            val paired = server.pairing() != null
            val p = pending
            if (!paired) {
                status.text = if (p != null) "Sprachnachricht erhalten. Bitte zuerst mit dem Server koppeln."
                else "Koppel dieses Handy mit deinem Server, um den Modus Hoch zu nutzen."
            } else if (p != null) runServer(p)
            else status.text = "Bereit (Server). Teile eine Sprachnachricht mit dieser App."
            return
        }
        if (!store.isReady()) {
            val mb = store.pendingBytes() / 1_000_000
            download.text = "Sprachmodell laden (ca. $mb MB)"
            status.text = if (pending != null)
                "Sprachnachricht erhalten. Lade zuerst das Modell (einmalig, danach alles offline)."
            else if (store.variant == "medium")
                "Das genauere Modell ist größer und braucht etwas länger beim Transkribieren."
            else "Einmalig ein Sprachmodell laden. Danach läuft alles offline auf deinem Handy."
            download.visibility = View.VISIBLE
        } else {
            download.visibility = View.GONE
            val p = pending
            if (p != null) run(p) else {
                status.text = "Bereit. Teile eine Sprachnachricht mit dieser App."
            }
        }
    }

    private fun downloadModel() {
        busy = true
        download.isEnabled = false
        progress.isIndeterminate = false
        progress.progress = 0
        progress.visibility = View.VISIBLE
        status.text = "Starte Download …"
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    store.download { name, done, total ->
                        runOnUiThread {
                            progress.progress = (done * 1000 / total).toInt()
                            status.text = "Lade $name\n%d %%  (%d / %d MB)".format(
                                done * 100 / total, done / 1_000_000, total / 1_000_000)
                        }
                    }
                }
                progress.visibility = View.GONE
                refresh()
            } catch (e: Exception) {
                status.text = "Download fehlgeschlagen: ${e.message}\nErneut tippen setzt fort."
            } finally { download.isEnabled = true; busy = false }
        }
    }

    private fun runServer(uri: Uri) {
        busy = true
        pending = null
        hint.visibility = View.GONE
        status.text = "Lese Audio …"
        progress.isIndeterminate = true
        progress.visibility = View.VISIBLE
        scope.launch {
            try {
                val res = withContext(Dispatchers.IO) {
                    val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: error("Audiodatei konnte nicht gelesen werden.")
                    server.transcribe(bytes, lang()) { msg -> runOnUiThread { status.text = msg } }
                }
                progress.visibility = View.GONE
                busy = false
                if (res.text.isBlank()) {
                    status.text = "Kein Text erkannt."
                } else {
                    status.text = "Fertig · %.1f s auf dem Server (%s)".format(res.serverSeconds, res.route)
                    val entry = Entry(System.currentTimeMillis(), res.text, Math.round(res.audioSeconds).toInt(), "high")
                    history.add(entry)
                    expanded = entry.id
                }
                renderHistory()
            } catch (e: Throwable) {
                progress.visibility = View.GONE
                busy = false
                status.text = "Fehler: ${e.message}"
                renderHistory()
            }
        }
    }

    private fun run(uri: Uri) {
        busy = true
        pending = null
        hint.visibility = View.GONE
        status.text = "Dekodiere Audio …"
        progress.isIndeterminate = true
        progress.visibility = View.VISIBLE
        scope.launch {
            try {
                val t0 = System.currentTimeMillis()
                var seconds = 0f
                val text = withContext(Dispatchers.Default) {
                    val samples = AudioDecoder.decode(this@MainActivity, uri)
                    seconds = samples.size / 16000f
                    val t = Transcriber(store, if (lang() == "auto") "" else lang()) // "" = Whisper auto-detects
                    try {
                        t.transcribe(samples) { done, n ->
                            runOnUiThread {
                                // a single chunk can't report partial progress -> indeterminate
                                progress.isIndeterminate = n == 1 || done == 0
                                progress.progress = done * 1000 / n
                                status.text = "Transkribiere … Abschnitt ${minOf(done + 1, n)} von $n"
                            }
                        }
                    } finally { t.release() }
                }
                progress.visibility = View.GONE
                busy = false
                if (text.isBlank()) {
                    status.text = "Kein Text erkannt."
                } else {
                    status.text = "Fertig · %.1f s Rechenzeit".format((System.currentTimeMillis() - t0) / 1000f)
                    val entry = Entry(System.currentTimeMillis(), text, Math.round(seconds), store.variant)
                    history.add(entry)
                    expanded = entry.id
                }
                renderHistory()
            } catch (e: Throwable) {
                progress.visibility = View.GONE
                busy = false
                status.text = "Fehler: ${e.message}"
                renderHistory()
            }
        }
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
