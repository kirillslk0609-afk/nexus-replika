package ru.neuroscribe.app

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import ru.neuroscribe.app.core.Transcript
import ru.neuroscribe.app.core.Turn
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val model: ScribeViewModel by viewModels()
    private val ink = Color.rgb(241, 242, 244)
    private val accent = Color.rgb(156, 201, 244)
    private val muted = Color.rgb(173, 181, 190)
    private val paper = Color.rgb(29, 34, 40)
    private val panel = Color.rgb(38, 45, 53)
    private val border = Color.rgb(70, 81, 91)
    private val speakerColors = intArrayOf(accent, Color.rgb(185, 162, 218), Color.rgb(236, 187, 139), Color.rgb(151, 209, 180))
    private lateinit var title: TextView
    private lateinit var fileLabel: TextView
    private lateinit var fileHint: TextView
    private lateinit var statistics: TextView
    private lateinit var selectButton: Button
    private lateinit var runButton: Button
    private lateinit var exportButton: Button
    private lateinit var copyButton: Button
    private lateinit var cancelButton: Button
    private lateinit var optionsButton: ImageButton
    private lateinit var status: TextView
    private lateinit var error: TextView
    private lateinit var notice: TextView
    private lateinit var progress: ProgressBar
    private lateinit var hero: LinearLayout
    private lateinit var filePanel: LinearLayout
    private lateinit var settings: LinearLayout
    private lateinit var workCard: LinearLayout
    private lateinit var dock: LinearLayout
    private lateinit var results: LinearLayout
    private lateinit var language: Spinner
    private var optionsExpanded = false
    private var renderedTurns: List<Turn>? = null
    private var renderedFinished = false
    private var player: MediaPlayer? = null
    private var prepared = false
    private var playWhenReady = false
    private var activeEntry: Entry? = null
    private var playbackTick: Runnable? = null
    private data class Entry(val turn: Turn, val color: Int, val container: LinearLayout,
        val control: ImageButton, val progressRow: LinearLayout, val progress: ProgressBar, val clock: TextView)
    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { stopPlayer(); optionsExpanded = false; model.importAudio(uri) }
    }
    private val exporter = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) lifecycleScope.launch {
            runCatching { model.export(uri) }.onSuccess {
                Toast.makeText(this@MainActivity, "Расшифровка сохранена", Toast.LENGTH_SHORT).show()
            }.onFailure { Toast.makeText(this@MainActivity, "Ошибка экспорта: ${it.message}", Toast.LENGTH_LONG).show() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        optionsExpanded = savedInstanceState?.getBoolean("optionsExpanded") ?: false
        buildScreen()
        language.setSelection(savedInstanceState?.getInt("language", 0) ?: 0)
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { model.state.collect(::render) } }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("optionsExpanded", optionsExpanded)
        outState.putInt("language", language.selectedItemPosition)
        super.onSaveInstanceState(outState)
    }
    private fun chooseAudio() { picker.launch(arrayOf("audio/*", "application/octet-stream")) }

    @Suppress("DEPRECATION")
    private fun buildScreen() {
        val screen = column().apply { setBackgroundColor(paper) }
        screen.setOnApplyWindowInsetsListener { view, insets ->
            view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            insets
        }
        val scroll = ScrollView(this).apply { isFillViewport = true; id = R.id.main_scroll }
        val root = column().apply {
            setPadding(dp(20), dp(16), dp(20), dp(20))
            clipChildren = false; clipToPadding = false
        }
        scroll.addView(centered(root))
        screen.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val brand = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        brand.addView(ImageView(this).apply {
            setImageResource(R.drawable.replica_mark)
            background = surface(paper, 20); clipToOutline = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginStart = -dp(8); marginEnd = dp(4) })
        brand.addView(label("Реплика", 18, ink, true).apply { isSingleLine = true }, LinearLayout.LayoutParams(0, -2, 1f))
        val compactHeader = resources.configuration.screenWidthDp < 360 || resources.configuration.fontScale > 1.2f
        if (compactHeader) brand.addView(iconButton(R.drawable.ic_verified_user, "На устройстве. Как защищены аудио и текст", ::showPrivacy).apply {
            id = R.id.privacy_info
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        else brand.addView(button("На устройстве", false) { showPrivacy() }.apply {
            id = R.id.privacy_info; textSize = 12f; setPadding(dp(6), dp(4), dp(6), dp(4))
            setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_verified_user, 0, 0, 0)
            compoundDrawableTintList = ColorStateList.valueOf(muted); compoundDrawablePadding = dp(6)
            contentDescription = "На устройстве. Как защищены аудио и текст"
        }, LinearLayout.LayoutParams(-2, -2))
        root.addView(brand)
        title = label("Аудио в текст", 24, ink, true)
        root.addView(title, rowParams(22))
        hero = column().apply {
            addView(label("Загрузите разговор — получите текст с говорящими и временными метками.", 16, muted))
            addView(label("Без интернета. Все модели уже в приложении.", 13, muted), rowParams(12))
        }
        root.addView(hero, rowParams(12))

        filePanel = column().apply { background = surface(panel, 12); setPadding(dp(14), dp(4), dp(8), dp(4)) }
        val fileRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        fileRow.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_insert_drive_file); imageTintList = ColorStateList.valueOf(muted)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(28), dp(32)).apply { marginEnd = dp(12) })
        val fileText = column()
        fileLabel = label("Аудиозапись", 16, ink, true).apply { id = R.id.file_label; maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        fileText.addView(fileLabel)
        statistics = label("", 12, muted).apply { id = R.id.recording_summary }
        fileText.addView(statistics, rowParams(3))
        fileRow.addView(fileText, LinearLayout.LayoutParams(0, -2, 1f))
        optionsButton = iconButton(R.drawable.ic_more_vert, "Параметры обработки") {
            optionsExpanded = !optionsExpanded; render(model.state.value)
        }.apply { id = R.id.processing_options }
        fileRow.addView(optionsButton, LinearLayout.LayoutParams(dp(48), dp(48)))
        filePanel.addView(fileRow)
        filePanel.setOnClickListener { if (model.state.value.finished) showDetails() }
        root.addView(filePanel, rowParams(18))
        fileHint = label("WAV, MP3, M4A · до 15 минут и 256 МБ", 12, muted)
        root.addView(fileHint, rowParams(14))
        selectButton = button("Выбрать аудио", true, ::chooseAudio).apply { id = R.id.select_audio }
        root.addView(selectButton, rowParams(14))
        settings = column()
        settings.addView(label("Язык разговора", 12, muted, true))
        language = Spinner(this).apply {
            id = R.id.audio_language
            adapter = object : ArrayAdapter<String>(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("Русский", "Английский", "Определить автоматически")) {
                override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View =
                    (super.getView(position, convertView, parent) as TextView).apply { setTextColor(ink); textSize = 15f; maxLines = 2 }
                override fun getDropDownView(position: Int, convertView: View?, parent: android.view.ViewGroup): View =
                    (super.getDropDownView(position, convertView, parent) as TextView).apply {
                        setTextColor(ink); setBackgroundColor(panel); textSize = 15f; minHeight = dp(48)
                    }
            }
            contentDescription = "Язык аудиозаписи"; minimumHeight = dp(52); backgroundTintList = ColorStateList.valueOf(accent)
        }
        settings.addView(language, rowParams(2))
        runButton = button("Расшифровать запись", true) {
            stopPlayer(); optionsExpanded = false; model.transcribe(listOf("ru", "en", "")[language.selectedItemPosition])
        }.apply { id = R.id.transcribe }
        settings.addView(runButton, rowParams(8))
        settings.addView(button("О записи", false, ::showDetails).apply { id = R.id.recording_details }, rowParams(4))
        root.addView(settings, rowParams(16))

        workCard = column().apply { setPadding(dp(16), dp(16), dp(16), dp(16)); background = surface(panel, 12) }
        workCard.addView(label("ОБРАБОТКА НА УСТРОЙСТВЕ", 10, accent, true))
        status = label("", 18, ink, true).apply { id = R.id.processing_status; accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        workCard.addView(status, rowParams(10))
        progress = progressBar(accent)
        workCard.addView(progress, rowParams(12))
        workCard.addView(label("Оставьте приложение открытым. Экран останется включённым до завершения.", 13, muted), rowParams(10))
        cancelButton = button("Отменить обработку", false) { model.cancel() }.apply { id = R.id.cancel_processing }
        workCard.addView(cancelButton, rowParams(10))
        root.addView(workCard, rowParams(18))
        error = label("", 14, Color.rgb(255, 181, 169)).apply {
            id = R.id.error_message; setTextIsSelectable(true); setPadding(dp(14), dp(12), dp(14), dp(12))
            background = surface(Color.rgb(67, 41, 41), 12); accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        root.addView(error, rowParams(12))
        notice = label("", 13, muted).apply { id = R.id.idle_notice; accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        root.addView(notice, rowParams(12))
        results = column().apply { id = R.id.transcript_turns }
        root.addView(results, rowParams(10).apply { marginStart = -dp(10); marginEnd = -dp(10) })

        // A separate layout region reserves real space for the dock; it never overlays the transcript.
        dock = column().apply { id = R.id.action_dock; setBackgroundColor(paper) }
        dock.addView(View(this).apply { setBackgroundColor(border) }, LinearLayout.LayoutParams(-1, dp(1)))
        val actions = column().apply { setPadding(dp(20), dp(14), dp(20), dp(4)) }
        exportButton = button("Сохранить JSON", true) {
            exporter.launch(model.state.value.fileName.substringBeforeLast('.').take(60).ifBlank { "transcript" } + ".json")
        }.apply { id = R.id.export_json }
        actions.addView(exportButton, rowParams(0))
        val stack = resources.configuration.screenWidthDp < 360 && resources.configuration.fontScale > 1.2f
        val secondary = LinearLayout(this).apply { orientation = if (stack) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL; isBaselineAligned = false }
        copyButton = button("Копировать текст", false, ::copyTranscript).apply { id = R.id.copy_transcript; textSize = 13f }
        addButtonIcon(copyButton, R.drawable.ic_content_copy)
        val other = button("Другая запись", false, ::chooseAudio).apply { id = R.id.new_recording; textSize = 13f }
        addButtonIcon(other, R.drawable.ic_insert_drive_file)
        secondary.addView(copyButton, if (stack) rowParams(0) else LinearLayout.LayoutParams(0, -2, 1f))
        secondary.addView(other, if (stack) rowParams(0) else LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(secondary, rowParams(6))
        dock.addView(centered(actions))
        screen.addView(dock, LinearLayout.LayoutParams(-1, -2))
        setContentView(screen)
    }

    private fun render(state: ScreenState) {
        val hasFile = state.fileName.isNotBlank()
        title.text = if (state.finished) "Расшифровка" else if (state.busy) "Обрабатываем запись" else "Аудио в текст"
        hero.visibility = if (!hasFile && !state.busy) View.VISIBLE else View.GONE
        filePanel.visibility = if (hasFile || state.busy) View.VISIBLE else View.GONE
        fileLabel.text = state.fileName.ifBlank { "Загружаем запись…" }; fileLabel.contentDescription = fileLabel.text
        filePanel.contentDescription = if (state.finished) "${state.fileName}. О записи" else null
        filePanel.isClickable = state.finished
        fileHint.visibility = if (state.finished || state.busy) View.GONE else View.VISIBLE
        selectButton.visibility = if (state.busy || state.finished) View.GONE else View.VISIBLE
        selectButton.text = if (hasFile) "Заменить аудио" else "Выбрать аудио"
        styleButton(selectButton, !hasFile)
        selectButton.isEnabled = !state.busy
        settings.visibility = if (hasFile && !state.busy && (!state.finished || optionsExpanded)) View.VISIBLE else View.GONE
        findViewById<View>(R.id.recording_details).visibility = if (state.finished) View.VISIBLE else View.GONE
        optionsButton.visibility = if (state.finished && !state.busy) View.VISIBLE else View.GONE
        optionsButton.contentDescription = if (optionsExpanded) "Скрыть параметры обработки" else "Параметры обработки"
        runButton.isEnabled = !state.busy && hasFile; language.isEnabled = !state.busy
        runButton.text = if (state.finished) "Обработать заново" else "Расшифровать запись"
        dock.visibility = if (state.finished && !state.busy) View.VISIBLE else View.GONE
        exportButton.isEnabled = state.finished && !state.busy; copyButton.isEnabled = state.finished && state.turns.isNotEmpty()
        workCard.visibility = if (state.busy) View.VISIBLE else View.GONE
        status.text = if (state.cancelling) "Отменяем после текущего этапа…" else state.stage
        progress.isIndeterminate = state.progress == 0; progress.progress = state.progress
        cancelButton.isEnabled = !state.cancelling
        error.text = state.error.orEmpty(); error.visibility = if (state.error != null) View.VISIBLE else View.GONE
        notice.text = state.stage
        notice.visibility = if (!state.busy && !state.finished && state.error == null && state.stage.isNotBlank()) View.VISIBLE else View.GONE
        statistics.text = if (state.finished) {
            val count = state.turns.map { it.speakerId }.distinct().size
            "${counted(count, "говорящий", "говорящих", "говорящих")} · ${displayTime(state.durationMs)}" +
                if (state.overlap) "\nЕсть одновременная речь — возможны неточности." else ""
        } else if (state.busy) "Обработка без интернета" else "Запись готова к обработке"
        results.visibility = if (state.finished) View.VISIBLE else View.GONE
        if (state.busy) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (renderedTurns != state.turns || renderedFinished != state.finished) {
            stopPlayer(); renderedTurns = state.turns; renderedFinished = state.finished; results.removeAllViews()
            if (state.turns.isEmpty() && state.finished) results.addView(column().apply {
                setPadding(dp(10), dp(16), dp(10), dp(16)); addView(label("Речь не найдена", 18, ink, true))
                addView(label("Проверьте, что в записи есть разборчивая речь, и попробуйте другой файл.", 14, muted), rowParams(8))
            })
            state.turns.forEachIndexed { index, turn ->
                if (index > 0) results.addView(View(this).apply { setBackgroundColor(border) },
                    LinearLayout.LayoutParams(-1, dp(1)).apply { setMargins(dp(10), dp(10), dp(10), dp(10)) })
                results.addView(buildEntry(turn, index).container, rowParams(0))
            }
        }
    }

    private fun buildEntry(turn: Turn, index: Int): Entry {
        val number = turn.speakerId.removePrefix("speaker_")
        val color = speakerColors[((number.toIntOrNull() ?: 1) - 1).coerceAtLeast(0) % speakerColors.size]
        val container = column().apply { setPadding(dp(10), dp(10), dp(10), dp(10)); tag = "turn-$index" }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(View(this).apply { background = surface(color, 3); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO },
            LinearLayout.LayoutParams(dp(5), dp(34)).apply { marginEnd = dp(12) })
        header.addView(column().apply {
            addView(label("Говорящий $number", 16, ink, true))
            addView(label("${displayTime(turn.startMs)} — ${displayTime(turn.endMs)}", 13, muted), rowParams(3))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        lateinit var entry: Entry
        val control = iconButton(R.drawable.ic_play_arrow, "Прослушать: говорящий $number, ${displayTime(turn.startMs)} — ${displayTime(turn.endMs)}") { togglePlayback(entry) }.apply {
            tag = "play-turn-$index"; background = RippleDrawable(ColorStateList.valueOf(border), surface(Color.rgb(54, 64, 73), 28), null)
        }
        header.addView(control, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = dp(8) })
        container.addView(header)
        container.addView(label(turn.text, 16, ink).apply { setTextIsSelectable(true); setLineSpacing(dp(3).toFloat(), 1f) }, rowParams(12))
        val progressRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE; tag = "playback-progress-$index" }
        val bar = progressBar(color).apply { max = 1000; tag = "playback-bar-$index" }
        progressRow.addView(bar, LinearLayout.LayoutParams(0, dp(12), 1f))
        val clock = label("", 11, muted)
        progressRow.addView(clock, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(12) })
        container.addView(progressRow, rowParams(12))
        entry = Entry(turn, color, container, control, progressRow, bar, clock)
        return entry
    }

    private fun togglePlayback(entry: Entry) {
        if (activeEntry === entry) {
            val p = player ?: return
            if (!prepared) { playWhenReady = !playWhenReady; updatePlayback(); return }
            if (p.isPlaying) { p.pause(); playWhenReady = false; removePlaybackTick() }
            else { p.start(); playWhenReady = true; schedulePlaybackTick() }
            updatePlayback(); return
        }
        stopPlayer()
        try {
            val p = MediaPlayer(); player = p; activeEntry = entry; prepared = false; playWhenReady = true
            entry.container.background = surface(panel, 12)
            entry.container.setPadding(dp(10), dp(10), dp(10), dp(10))
            entry.progressRow.visibility = View.VISIBLE
            updatePlayback()
            p.setDataSource(model.audioFile.absolutePath)
            p.setOnErrorListener { failed, _, _ -> if (player === failed) playbackFailed(); true }
            p.setOnPreparedListener { ready ->
                if (player === ready) {
                    ready.setOnSeekCompleteListener { seeked ->
                        if (player === seeked) {
                            prepared = true
                            if (playWhenReady) { seeked.start(); schedulePlaybackTick() }
                            updatePlayback()
                        }
                    }
                    ready.seekTo(entry.turn.startMs, MediaPlayer.SEEK_CLOSEST)
                }
            }
            p.setOnCompletionListener { if (player === it) stopPlayer() }
            p.prepareAsync()
        } catch (_: Exception) { playbackFailed() }
    }
    private fun updatePlayback() {
        val entry = activeEntry ?: return
        val position = if (prepared) player?.currentPosition?.toLong() ?: entry.turn.startMs else entry.turn.startMs
        entry.progress.progress = (((position - entry.turn.startMs).coerceAtLeast(0) * 1000) / (entry.turn.endMs - entry.turn.startMs).coerceAtLeast(1)).toInt().coerceIn(0, 1000)
        entry.clock.text = "${displayTime(position)} / ${displayTime(entry.turn.endMs)}"
        entry.control.setImageResource(if (playWhenReady) R.drawable.ic_pause else R.drawable.ic_play_arrow)
        entry.control.contentDescription = if (playWhenReady) "Приостановить реплику" else "Продолжить реплику"
        entry.control.isSelected = playWhenReady
    }
    private fun schedulePlaybackTick() {
        removePlaybackTick()
        val tick = object : Runnable {
            override fun run() {
                val entry = activeEntry ?: return
                val p = player ?: return
                if (!prepared || !playWhenReady) return
                if (p.currentPosition.toLong() >= entry.turn.endMs) { stopPlayer(); return }
                updatePlayback(); window.decorView.postDelayed(this, 80)
            }
        }
        playbackTick = tick; window.decorView.post(tick)
    }
    private fun removePlaybackTick() { playbackTick?.let { window.decorView.removeCallbacks(it) }; playbackTick = null }
    private fun playbackFailed() { stopPlayer(); Toast.makeText(this, "Не удалось воспроизвести аудио", Toast.LENGTH_SHORT).show() }
    private fun stopPlayer() {
        removePlaybackTick(); player?.release(); player = null; prepared = false; playWhenReady = false
        activeEntry?.apply {
            container.background = null; container.setPadding(dp(10), dp(10), dp(10), dp(10)); progressRow.visibility = View.GONE
            control.setImageResource(R.drawable.ic_play_arrow); control.isSelected = false
            control.contentDescription = "Прослушать: говорящий ${turn.speakerId.removePrefix("speaker_")}, ${displayTime(turn.startMs)} — ${displayTime(turn.endMs)}"
        }
        activeEntry = null
    }
    override fun onStop() { stopPlayer(); super.onStop() }

    private fun copyTranscript() {
        val state = model.state.value
        if (!state.finished || state.turns.isEmpty()) return
        val text = state.turns.joinToString("\n\n") {
            "Говорящий ${it.speakerId.removePrefix("speaker_")} · ${displayTime(it.startMs)} — ${displayTime(it.endMs)}\n${it.text}"
        }
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Расшифровка — Реплика", text))
        if (Build.VERSION.SDK_INT < 33) Toast.makeText(this, "Текст скопирован", Toast.LENGTH_SHORT).show()
    }
    private fun showPrivacy() {
        AlertDialog.Builder(this).setTitle("Обработка на устройстве").setMessage(
            "Распознавание, разделение говорящих и пунктуация выполняются без интернета. Аудио и результат хранятся в приложении.\n\nВы сами выбираете, куда сохранить JSON или скопировать текст."
        ).setPositiveButton("Понятно", null).show()
    }
    private fun showDetails() {
        val state = model.state.value
        if (!state.finished) return
        val rtf = state.processingMs.toDouble() / state.durationMs.coerceAtLeast(1)
        AlertDialog.Builder(this).setTitle("О записи").setMessage(
            "${state.fileName}\n\nДлительность: ${Transcript.clock(state.durationMs)}\nОбработка: ${Transcript.clock(state.processingMs)}\n" +
                "RTF: ${String.format(Locale.ROOT, "%.2f", rtf)}\nВызовы локальной LLM: ${state.llmCalls}\n\n" +
                "Реплика ${packageManager.getPackageInfo(packageName, 0).versionName} · без интернета"
        ).setPositiveButton("Понятно", null).show()
    }
    private fun displayTime(ms: Long) = String.format(Locale.ROOT, "%02d:%02d", ms.coerceAtLeast(0) / 60000, (ms.coerceAtLeast(0) / 1000) % 60)
    private fun counted(n: Int, one: String, few: String, many: String): String = "$n " +
        if (n % 100 in 11..14) many else when (n % 10) { 1 -> one; 2, 3, 4 -> few; else -> many }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun centered(content: View) = FrameLayout(this).apply {
        addView(content, FrameLayout.LayoutParams(-1, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            if (right - left != oldRight - oldLeft) {
                val width = (right - left).coerceAtMost(dp(680))
                if (content.layoutParams.width != width) content.layoutParams = (content.layoutParams as FrameLayout.LayoutParams).apply { this.width = width }
            }
        }
    }
    private fun label(value: String, size: Int, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size.toFloat(); setTextColor(color)
        typeface = Typeface.create(if (bold) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
        setLineSpacing(dp(2).toFloat(), 1f)
    }
    private fun surface(color: Int, radius: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun progressBar(color: Int) = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 100; progressTintList = ColorStateList.valueOf(color); progressBackgroundTintList = ColorStateList.valueOf(border)
    }
    private fun styleButton(view: Button, primary: Boolean) {
        view.setTextColor(ColorStateList(arrayOf(intArrayOf(android.R.attr.state_enabled), intArrayOf()), intArrayOf(if (primary) paper else muted, Color.rgb(116, 128, 140))))
        view.background = RippleDrawable(ColorStateList.valueOf(Color.argb(60, 156, 201, 244)), surface(if (primary) accent else Color.TRANSPARENT, 10), null)
    }
    private fun button(value: String, primary: Boolean, action: () -> Unit) = Button(this).apply {
        text = value; isAllCaps = false; textSize = 15f; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        minHeight = dp(48); minimumHeight = dp(48); minWidth = 0; minimumWidth = 0
        setPadding(dp(10), dp(10), dp(10), dp(10)); stateListAnimator = null
        styleButton(this, primary); setOnClickListener { action() }
    }
    private fun iconButton(icon: Int, description: String, action: () -> Unit) = ImageButton(this).apply {
        setImageResource(icon); imageTintList = ColorStateList.valueOf(ink); contentDescription = description
        setPadding(dp(12), dp(12), dp(12), dp(12)); minimumWidth = dp(48); minimumHeight = dp(48)
        background = RippleDrawable(ColorStateList.valueOf(border), surface(Color.TRANSPARENT, 24), null)
        setOnClickListener { action() }
    }
    private fun addButtonIcon(button: Button, icon: Int) {
        val drawable = getDrawable(icon)!!.mutate().apply { setTint(muted); setBounds(0, 0, dp(20), dp(20)) }
        button.setCompoundDrawablesRelative(drawable, null, null, null); button.compoundDrawablePadding = dp(6)
    }
    private fun rowParams(top: Int) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }
}
