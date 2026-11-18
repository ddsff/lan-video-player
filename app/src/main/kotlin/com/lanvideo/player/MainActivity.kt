package com.lanvideo.player

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.text.method.PasswordTransformationMethod
import android.text.format.Formatter
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val network = Executors.newSingleThreadExecutor()
    private val discovery = Executors.newSingleThreadExecutor()
    private val history by lazy { ConnectionHistory(applicationContext) }
    private var savedConnections: List<ConnectionHistory.Record>? = null
    private var lastConnection: ConnectionHistory.Record? = null
    private var privacyDialog: AlertDialog? = null
    private var historyReadFailed = false
    private var scanDialog: AlertDialog? = null
    private lateinit var scroll: ScrollView
    private lateinit var page: LinearLayout
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var submit: View
    private lateinit var rememberPassword: CheckBox
    private var login: SmbLibrary.Login? = null
    private var path = ""
    private var request = 0
    private var browsing = false
    private var loading = false

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        page = column().apply { isFocusableInTouchMode = true }
        val wrapper = object : FrameLayout(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val available = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
                page.layoutParams.width = minOf(available.coerceAtLeast(0), dp(500))
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            }
        }.apply {
            addView(page, FrameLayout.LayoutParams(-1, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        }
        scroll = ScrollView(this).apply {
            setBackgroundColor(Theme.background)
            isFillViewport = true
            clipToPadding = false
            isVerticalScrollBarEnabled = false
            addView(wrapper)
            setOnApplyWindowInsetsListener { _, insets ->
                if (Build.VERSION.SDK_INT >= 30) {
                    val safe = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
                    wrapper.setPadding(dp(22) + safe.left, dp(18) + safe.top, dp(22) + safe.right, dp(24) + safe.bottom)
                } else {
                    @Suppress("DEPRECATION")
                    wrapper.setPadding(
                        dp(22) + insets.systemWindowInsetLeft, dp(18) + insets.systemWindowInsetTop,
                        dp(22) + insets.systemWindowInsetRight, dp(24) + insets.systemWindowInsetBottom
                    )
                }
                insets
            }
        }
        setContentView(scroll)
        showConnection()
    }

    private fun showConnection(prefill: ConnectionHistory.Record? = lastConnection ?: savedConnections?.firstOrNull()) {
        browsing = false
        loading = false
        page.removeAllViews()
        page.block(row().apply {
            addView(Icon(this@MainActivity, Glyph.PLAY, Theme.ink, 19).apply {
                background = panel(Theme.accent, 12)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(39), dp(39)))
            addView(label("局域网播放器", 17f, bold = true), LinearLayout.LayoutParams(0, -2, 1f).apply {
                marginStart = dp(11)
            })
            addView(label("隐私政策", 14f, Theme.accent).apply {
                minHeight = dp(48)
                gravity = Gravity.CENTER
                setPadding(dp(8), 0, dp(8), 0)
                setOnClickListener { showPrivacyPolicy() }
            }, LinearLayout.LayoutParams(-2, -2))
        })
        page.block(connectionHero(), 16)

        val form = column().apply {
            background = panel(Theme.surface, 24, Theme.border)
            setPadding(dp(18), dp(16), dp(18), dp(18))
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }
        page.block(form, 15)
        val host = field(form, "电脑 IP 地址", "例如 192.168.1.20", prefill?.host,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        val scan = row().apply {
            background = ripple(0xFF234039.toInt(), 13)
            minimumHeight = dp(44)
            setPadding(dp(12), 0, dp(12), 0)
            addView(Icon(this@MainActivity, Glyph.COMPUTER, Theme.accent, 19).apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(24), dp(24)))
            addView(label("扫描局域网", 14f, Theme.accent, bold = true), LinearLayout.LayoutParams(-2, -2).apply {
                marginStart = dp(8)
            })
            setOnClickListener { if (!loading) scanForHost(host) }
        }
        form.block(row().apply {
            addView(scan, LinearLayout.LayoutParams(0, -2, 1f))
            addView(label("连接历史", 14f, Theme.accent, bold = true).apply {
                minHeight = dp(44)
                setPadding(dp(12), 0, dp(12), 0)
                gravity = Gravity.CENTER
                background = ripple(Theme.surface, 13, Theme.border)
                setOnClickListener { if (!loading) showConnectionHistory() }
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) })
        }, 8)
        val share = field(form, "共享名称", "例如 Videos", prefill?.share, InputType.TYPE_CLASS_TEXT, 10)
        val account = field(form, "Windows 用户名", "例如 电脑名\\用户名", prefill?.account,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, 10,
            help = ::showAccountHelp)
        val password = field(form, "Windows 密码", "输入账户密码", prefill?.password,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD, 10, password = true)
        var passwordSource = prefill?.takeIf { it.password != null }
        val rememberPassword = CheckBox(this).apply {
            text = "记住密码"
            setTextColor(Theme.accent)
            minHeight = dp(48)
            isChecked = passwordSource != null
        }
        this.rememberPassword = rememberPassword
        form.block(rememberPassword)
        rememberPassword.setOnCheckedChangeListener { _, checked ->
            val source = passwordSource
            if (!checked && source != null) {
                loading = true
                submit.isEnabled = false
                rememberPassword.isEnabled = false
                network.execute {
                    val result = runCatching { history.forgetPassword(source) }
                    runOnUiThread {
                        if (isDestroyed) return@runOnUiThread
                        loading = false
                        submit.isEnabled = true
                        rememberPassword.isEnabled = true
                        result.onSuccess {
                            savedConnections = it
                            if (lastConnection?.matches(source) == true) {
                                lastConnection = lastConnection?.withoutPassword()
                            }
                        }.onFailure {
                            if (passwordSource === source) rememberPassword.isChecked = true
                            showStatus("无法删除已保存的密码，请重试。", error = true)
                        }
                    }
                }
            }
        }
        val forgetFilledPassword = object : TextWatcher {
            override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(text: Editable?) {
                val source = passwordSource ?: return
                if (!host.text.toString().trim().equals(source.host, ignoreCase = true) ||
                    !account.text.toString().trim().equals(source.account, ignoreCase = true)) {
                    passwordSource = null
                    password.setText("")
                    rememberPassword.isChecked = false
                }
            }
        }
        host.addTextChangedListener(forgetFilledPassword)
        account.addTextChangedListener(forgetFilledPassword)
        host.imeOptions = EditorInfo.IME_ACTION_NEXT
        share.imeOptions = EditorInfo.IME_ACTION_NEXT
        account.imeOptions = EditorInfo.IME_ACTION_NEXT
        password.imeOptions = EditorInfo.IME_ACTION_GO
        host.nextFocusForwardId = share.id
        share.nextFocusForwardId = account.id
        account.nextFocusForwardId = password.id

        fun connect() {
            if (loading) return
            val address = host.text.toString().trim()
            val name = share.text.toString().trim()
            val user = account.text.toString().trim()
            if (address.isEmpty() || name.isEmpty() || user.isEmpty() ||
                address.any { it == '/' || it == '\\' || it == ':' } ||
                name.any { it == '/' || it == '\\' }) {
                showStatus("请填写有效的电脑地址、共享名称和 Windows 用户名。", error = true)
                return
            }
            getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(password.windowToken, 0)
            page.requestFocus()
            val candidate = SmbLibrary.Login(address, name, user, password.text.toString())
            val record = ConnectionHistory.Record(address, name, user, candidate.password.takeIf { rememberPassword.isChecked })
            load(candidate, "", record)
        }

        submit = label("连接共享", 16f, Theme.ink, bold = true).apply {
            gravity = Gravity.CENTER
            minHeight = dp(52)
            background = ripple(Theme.accent, 16)
            setOnClickListener { connect() }
        }
        form.block(submit, 15)
        addDiagnosticsButton(form)
        addStatus(form)
        password.setOnEditorActionListener { _, action, _ ->
            if (action != EditorInfo.IME_ACTION_GO) return@setOnEditorActionListener false
            if (!loading) connect()
            true
        }
        page.requestFocus()
        scroll.scrollTo(0, 0)
        if (savedConnections == null) {
            network.execute {
                val result = runCatching { history.load() }
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    savedConnections = result.getOrDefault(emptyList())
                    historyReadFailed = result.isFailure
                    val latest = savedConnections?.firstOrNull()
                    if (latest != null && !loading && form.parent === page &&
                        listOf(host, share, account, password).all { it.text.isEmpty() }) {
                        host.setText(latest.host)
                        share.setText(latest.share)
                        account.setText(latest.account)
                        password.setText(latest.password)
                        passwordSource = latest.takeIf { it.password != null }
                        rememberPassword.isChecked = passwordSource != null
                    }
                }
            }
        }
    }

    private fun showPrivacyPolicy() {
        val html = assets.open("privacy-policy.html").bufferedReader().use { it.readText() }
        val web = WebView(this).apply {
            settings.blockNetworkLoads = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
        }
        val body = column().apply {
            addView(web, LinearLayout.LayoutParams(-1, dp(resources.configuration.screenHeightDp * 3 / 5)))
        }
        privacyDialog = AlertDialog.Builder(this)
            .setTitle("隐私政策")
            .setView(body)
            .setPositiveButton("关闭", null)
            .create().apply {
                setOnDismissListener {
                    body.removeView(web)
                    web.destroy()
                    privacyDialog = null
                }
                show()
            }
    }

    private fun showConnectionHistory() {
        val records = savedConnections
        val builder = AlertDialog.Builder(this)
            .setTitle("连接历史")
            .setNegativeButton("关闭", null)
        when {
            historyReadFailed -> builder.setMessage("无法读取已保存的记录。可以清除历史后重新连接。")
            records == null -> builder.setMessage("正在读取连接历史…")
            records.isEmpty() -> builder.setMessage("成功连接后会保存到这里。")
            else -> builder.setItems(records.map { "${it.host} / ${it.share}\n${it.account}" }.toTypedArray()) { _, index ->
                showConnection(records[index])
            }
        }
        if (historyReadFailed || !records.isNullOrEmpty()) {
            builder.setNeutralButton("清除历史") { _, _ ->
                AlertDialog.Builder(this)
                    .setTitle("清除连接历史？")
                    .setMessage("将删除本机保存的连接记录和密码。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("清除") { _, _ -> clearConnectionHistory() }
                    .show()
            }
        }
        builder.show()
    }

    private fun clearConnectionHistory() {
        loading = true
        submit.isEnabled = false
        rememberPassword.isEnabled = false
        network.execute {
            val result = runCatching { history.clear() }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                loading = false
                submit.isEnabled = true
                rememberPassword.isEnabled = true
                result.onSuccess {
                    savedConnections = emptyList()
                    historyReadFailed = false
                    login = null
                    lastConnection = null
                    showConnection()
                }.onFailure {
                    showStatus("无法清除连接历史，请重试。", error = true)
                }
            }
        }
    }

    private fun connectionHero() = FrameLayout(this).apply {
        background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
            intArrayOf(0xFF24493D.toInt(), 0xFF16362F.toInt())).apply {
            cornerRadius = dp(24).toFloat()
            setStroke(dp(1), 0xFF3A6355.toInt())
        }
        clipToOutline = true
        minimumHeight = dp(134)
        addView(NetworkArtwork(this@MainActivity), FrameLayout.LayoutParams(dp(149), dp(134),
            Gravity.END or Gravity.CENTER_VERTICAL))
        val copy = column().apply {
            setPadding(dp(18), dp(23), dp(6), dp(23))
            addView(label("连接电脑", 27f, bold = true).apply { markAsHeading() })
            block(label("播放 Windows 11 共享视频", 13f, Theme.muted), 6)
        }
        addView(copy, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_VERTICAL).apply {
            marginEnd = dp(128)
        })
    }

    private fun scanForHost(host: EditText) {
        if (scanDialog?.isShowing == true) return
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(host.windowToken, 0)
        page.requestFocus()
        val body = column().apply { setPadding(dp(24), dp(10), dp(24), dp(8)) }
        val message = label("正在查找开启文件共享的设备…", 14f, Theme.muted).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        body.block(message)
        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(Theme.accent)
        }
        body.block(progress, 12)
        val dialog = AlertDialog.Builder(this)
            .setTitle("局域网设备")
            .setView(body)
            .setNegativeButton("关闭", null)
            .create()
        scanDialog = dialog
        dialog.show()

        val connectivity = getSystemService(ConnectivityManager::class.java)
        val job = discovery.submit {
            val result = runCatching { LanDiscovery.scan(connectivity) }
            runOnUiThread {
                if (!dialog.isShowing || isDestroyed) return@runOnUiThread
                progress.visibility = View.GONE
                val devices = result.getOrDefault(emptyList())
                message.text = when {
                    devices.isNotEmpty() -> "找到 ${devices.size} 台设备，点选后填入地址。"
                    result.exceptionOrNull() is LanDiscovery.NoLocalNetwork ->
                        "请先连接带有 IPv4 地址的 Wi-Fi 或有线局域网。"
                    result.isFailure -> "扫描失败，请检查网络后重试。"
                    else -> "未找到开启 SMB 共享的设备。请确认电脑在线，并已启用文件和打印机共享。"
                }
                if (devices.isNotEmpty()) {
                    val list = ListView(this).apply {
                        adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_list_item_1, devices)
                        setOnItemClickListener { _, _, position, _ ->
                            host.setText(devices[position])
                            dialog.dismiss()
                        }
                    }
                    body.addView(list, LinearLayout.LayoutParams(-1, dp(minOf(6, devices.size) * 54)).apply {
                        topMargin = dp(8)
                    })
                }
            }
        }
        dialog.setOnDismissListener {
            job.cancel(true)
            scanDialog = null
        }
    }

    private fun field(
        form: LinearLayout, caption: String, hint: String, value: String?, type: Int,
        top: Int = 0, password: Boolean = false, help: (() -> Unit)? = null
    ): EditText {
        val input = EditText(this).apply {
            id = View.generateViewId()
            setSingleLine()
            inputType = type
            setSaveEnabled(false)
            setTextColor(Theme.text)
            setHintTextColor(Theme.muted)
            textSize = 15f
            this.hint = hint
            setText(value.orEmpty())
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = null
            minHeight = dp(46)
        }
        val title = label(caption, 13f, bold = true).apply { labelFor = input.id }
        if (help == null) {
            form.block(title, top)
        } else {
            form.block(row().apply {
                addView(title, LinearLayout.LayoutParams(0, -2, 1f))
                addView(label("怎么查？", 13f, Theme.accent, bold = true).apply {
                    gravity = Gravity.CENTER
                    minHeight = dp(48)
                    setPadding(dp(10), 0, dp(10), 0)
                    background = ripple(Theme.surface, 12)
                    contentDescription = "怎么查 Windows 用户名"
                    setOnClickListener { help() }
                }, LinearLayout.LayoutParams(-2, -2))
            }, top)
        }
        val shell = row().apply {
            background = panel(Theme.field, 13, Theme.border)
            addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        }
        input.setOnFocusChangeListener { _, focused ->
            shell.background = panel(Theme.field, 13, if (focused) Theme.accent else Theme.border)
        }
        if (password) {
            val reveal = iconAction("显示密码", Glyph.EYE) { icon ->
                val visible = input.transformationMethod != null
                input.transformationMethod = if (visible) null else PasswordTransformationMethod.getInstance()
                input.setSelection(input.length())
                icon.glyph = if (visible) Glyph.EYE_OFF else Glyph.EYE
                icon.contentDescription = if (visible) "隐藏密码" else "显示密码"
                icon.invalidate()
            }
            shell.addView(reveal, LinearLayout.LayoutParams(dp(48), dp(48)))
        }
        form.block(shell, 4)
        return input
    }

    private fun showAccountHelp() {
        AlertDialog.Builder(this)
            .setTitle("查看 Windows 用户名")
            .setMessage(
                "1. 在共享视频的 Windows 电脑上打开 PowerShell，输入 whoami 并按回车。\n\n" +
                    "2. 如果共享使用当前登录的账户，把显示的「电脑名\\用户名」填进 App。" +
                    "用户名可能是一串数字，按显示结果照填即可。\n\n" +
                    "3. 如果这台电脑用微软账户登录，而上面的写法认证失败，" +
                    "可试 MicrosoftAccount\\用户名，用户名取 whoami 结果中反斜杠后面的部分。\n\n" +
                    "密码栏填写该账户的密码。Windows Hello PIN 与账户密码是两回事。"
            )
            .setPositiveButton("知道了", null)
            .show()
    }

    private fun showDirectory(entries: List<SmbLibrary.Entry>) {
        val connection = login ?: return
        browsing = true
        page.removeAllViews()
        val heading = row()
        if (path.isNotEmpty()) {
            heading.addView(iconAction("返回上一级", Glyph.BACK) { parent() },
                LinearLayout.LayoutParams(dp(48), dp(48)))
        }
        heading.addView(label("共享视频", 24f, bold = true).apply {
            markAsHeading()
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(10) })
        heading.addView(iconAction("连接设置", Glyph.SETTINGS) { disconnect() },
            LinearLayout.LayoutParams(dp(48), dp(48)))
        page.block(heading)

        val location = row().apply {
            background = panel(0xFF1E3B34.toInt(), 19, Theme.border)
            setPadding(dp(12), dp(12), dp(8), dp(12))
            addView(Icon(this@MainActivity, Glyph.COMPUTER, Theme.accent).apply {
                background = panel(0xFF2B5145.toInt(), 13)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(42), dp(42)))
            addView(column().apply {
                addView(label(connection.host, 14f, bold = true))
                block(label(listOf(connection.share, path).filter { it.isNotEmpty() }
                    .joinToString(" / ").replace("\\", " / "), 13f, Theme.muted), 2)
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { setMarginStart(dp(10)) })
            submit = iconAction("刷新", Glyph.REFRESH) { load(connection, path) }
            addView(submit, LinearLayout.LayoutParams(dp(48), dp(48)))
        }
        page.block(location, 14)
        addDiagnosticsButton(page)
        addStatus(page)
        page.block(label(if (entries.isEmpty()) "此文件夹没有视频或子文件夹" else "${entries.size} 个项目",
            13f, Theme.muted), 14)
        for (entry in entries) page.block(entryRow(entry, connection), 7)
        scroll.scrollTo(0, 0)
    }

    private fun entryRow(entry: SmbLibrary.Entry, connection: SmbLibrary.Login) = row().apply {
        background = ripple(Theme.surface, 16, Theme.border)
        minimumHeight = dp(64)
        setPadding(dp(11), dp(8), dp(11), dp(8))
        addView(Icon(this@MainActivity, if (entry.directory) Glyph.FOLDER else Glyph.FILM, Theme.accent, 20).apply {
            background = panel(0xFF234039.toInt(), 11)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(40), dp(40)))
        addView(column().apply {
            addView(label(entry.name, 15f, bold = true))
            if (!entry.directory) {
                val extension = entry.name.substringAfterLast('.').uppercase(Locale.ROOT)
                block(label("$extension  ·  ${Formatter.formatShortFileSize(this@MainActivity, entry.size)}",
                    12f, Theme.muted), 2)
            }
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(11) })
        addView(Icon(this@MainActivity, if (entry.directory) Glyph.NEXT else Glyph.PLAY, Theme.muted, 18).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(27), dp(34)))
        setOnClickListener {
            if (loading) return@setOnClickListener
            val child = if (path.isEmpty()) entry.name else "$path\\${entry.name}"
            if (entry.directory) {
                load(connection, child)
            } else {
                PlaybackActivity.pending = PlaybackActivity.Selection(connection, child, entry.name)
                startActivity(Intent(this@MainActivity, PlaybackActivity::class.java))
            }
        }
    }

    private fun iconAction(description: String, glyph: Glyph, action: (Icon) -> Unit) =
        Icon(this, glyph, Theme.accent).apply {
            background = ripple(0x0027403C, 12)
            contentDescription = description
            isFocusable = true
            setOnClickListener { action(this) }
        }

    private fun addStatus(container: LinearLayout) {
        val line = row().apply { visibility = View.GONE }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleSmall).apply {
            indeterminateTintList = ColorStateList.valueOf(Theme.accent)
        }
        line.addView(progress, LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(8) })
        status = label("", 13f, Theme.muted).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        line.addView(status, LinearLayout.LayoutParams(0, -2, 1f))
        container.block(line, 9)
    }

    private fun showStatus(message: String, error: Boolean = false) {
        (status.parent as View).visibility = View.VISIBLE
        progress.visibility = if (error) View.GONE else View.VISIBLE
        status.setTextColor(if (error) Theme.error else Theme.muted)
        status.text = message
    }

    private fun addDiagnosticsButton(container: LinearLayout) {
        container.block(label("SMB 连接日志", 14f, Theme.accent, bold = true).apply {
            gravity = Gravity.CENTER
            minHeight = dp(48)
            background = ripple(Theme.surface, 13, Theme.border)
            setOnClickListener { showDiagnostics() }
        }, 8)
    }

    private fun showDiagnostics() {
        val output = label("", 13f).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        val logView = ScrollView(this).apply { addView(output) }
        val body = column().apply {
            val height = minOf(380, resources.configuration.screenHeightDp / 2)
            addView(logView, LinearLayout.LayoutParams(-1, dp(height)))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("SMB 连接日志")
            .setView(body)
            .setNegativeButton("关闭", null)
            .setNeutralButton("刷新", null)
            .setPositiveButton("复制", null)
            .show()
        val copy = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        var log = ""
        fun refresh() {
            log = SmbDiagnostics.snapshot()
            output.text = log.ifEmpty { "暂无日志。先连接一次共享，再回来查看。" }
            copy.isEnabled = log.isNotEmpty()
            logView.post { logView.fullScroll(View.FOCUS_DOWN) }
        }
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { refresh() }
        copy.setOnClickListener {
            val clipboard = getSystemService(ClipboardManager::class.java)
            val clip = ClipData.newPlainText("SMB 连接日志", log)
            val sensitiveKey = if (Build.VERSION.SDK_INT >= 33) ClipDescription.EXTRA_IS_SENSITIVE
                else "android.content.extra.IS_SENSITIVE"
            clip.description.extras = PersistableBundle().apply { putBoolean(sensitiveKey, true) }
            clipboard.setPrimaryClip(clip)
            if (Build.VERSION.SDK_INT < 33) {
                Toast.makeText(this, "日志已复制", Toast.LENGTH_SHORT).show()
            }
        }
        refresh()
    }

    private fun load(candidate: SmbLibrary.Login, directory: String, record: ConnectionHistory.Record? = null) {
        val current = ++request
        loading = true
        submit.isEnabled = false
        submit.alpha = .65f
        if (record != null) rememberPassword.isEnabled = false
        showStatus("正在读取共享文件夹…")
        network.execute {
            val result = runCatching { SmbLibrary.list(candidate, directory) }
            val saved = if (record != null && result.isSuccess) runCatching { history.remember(record) } else null
            runOnUiThread {
                if (isDestroyed || current != request) return@runOnUiThread
                loading = false
                result.onSuccess { entries ->
                    if (record != null) lastConnection = if (saved?.isSuccess == true) record else record.withoutPassword()
                    saved?.onSuccess {
                        savedConnections = it
                        historyReadFailed = false
                    }
                    login = candidate
                    path = directory
                    showDirectory(entries)
                    if (saved?.isFailure == true) {
                        showStatus("共享已连接，无法保存连接历史。", error = true)
                    }
                }.onFailure { error ->
                    submit.isEnabled = true
                    submit.alpha = 1f
                    if (record != null) rememberPassword.isEnabled = true
                    val message = if (SmbDiagnostics.isLogonFailure(error)) {
                        "Windows 拒绝了登录。用 whoami 确认用户名，并核对账户密码。"
                    } else {
                        "无法读取共享。请检查电脑地址、共享名称、账户密码和读取权限。"
                    }
                    showStatus(message, error = true)
                }
            }
        }
    }

    private fun disconnect() {
        request++
        showConnection()
    }

    private fun parent() {
        login?.let { load(it, path.substringBeforeLast('\\', "")) }
    }

    @Deprecated("Uses the platform back callback on Android 8 and later")
    override fun onBackPressed() {
        when {
            browsing && path.isNotEmpty() -> parent()
            browsing -> disconnect()
            else -> super.onBackPressed()
        }
    }

    override fun onDestroy() {
        privacyDialog?.dismiss()
        scanDialog?.dismiss()
        discovery.shutdownNow()
        network.shutdown()
        super.onDestroy()
    }

    private fun TextView.markAsHeading() {
        if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
    }
}
