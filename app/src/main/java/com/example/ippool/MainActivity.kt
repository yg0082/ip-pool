package com.example.ippool

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.text.SpannableString
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ForegroundColorSpan
import android.text.style.URLSpan
import android.text.style.UnderlineSpan
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.NestedScrollView
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    companion object {
        // 配置只存本机（SharedPreferences "ippool"）：Gist 用户名 + Gist ID。
        // 代码里绝不出现任何真实 Gist ID、Gist 链接、UUID 或令牌；Gist 链接由用户自己粘贴一次存下来。
        const val PREFS = "ippool"
        const val KEY_GIST_USER = "gist_user"
        const val KEY_GIST_ID = "gist_id"
        const val GIST_RAW_BASE = "https://gist.githubusercontent.com"
        const val GIST_API_BASE = "https://api.github.com/gists"
        const val CF_LIST_URL = "https://www.cloudflare.com/ips-v4"
        // 地区分组：代号 to 显示名
        val REGIONS = listOf(
            "hk" to "香港", "sg" to "新加坡", "jp" to "日本",
            "us" to "美国", "tw" to "台湾", "kr" to "韩国"
        )
        val CF_COUNTS = listOf(200, 500, 1000, 1500)
    }

    // Cloudflare 官方 IPv4 段（https://www.cloudflare.com/ips-v4，共 15 个，约 152 万地址）
    // Triple 存（网络地址，掩码，段内地址总数）
    private val cfNetworks: List<Triple<Int, Int, Long>> by lazy {
        listOf(
            "173.245.48.0/20", "103.21.244.0/22", "103.22.200.0/22", "103.31.4.0/22",
            "141.101.64.0/18", "108.162.192.0/18", "190.93.240.0/20", "188.114.96.0/20",
            "197.234.240.0/22", "198.41.128.0/17", "162.158.0.0/15", "104.16.0.0/13",
            "104.24.0.0/14", "172.64.0.0/13", "131.0.72.0/22"
        ).mapNotNull { cidr ->
            val parts = cidr.split("/")
            if (parts.size != 2) return@mapNotNull null
            val net = ipv4ToInt(parts[0]) ?: return@mapNotNull null
            val prefix = parts[1].toIntOrNull() ?: return@mapNotNull null
            if (prefix !in 0..32) return@mapNotNull null
            val mask = if (prefix == 0) 0 else (-1 shl (32 - prefix))
            val total = 1L shl (32 - prefix)
            Triple(net and mask, mask, total)
        }
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    private var gistUser: String? = null
    private var gistId: String? = null
    private var poolJob: Job? = null

    // IP 池数据：代号 -> IP 列表（"all" 为全部）
    private val poolData = mutableMapOf<String, List<String>>()
    private val poolCountViews = mutableMapOf<String, TextView>()
    private val poolCopyBtns = mutableMapOf<String, Button>()
    private var allIpListView: TextView? = null
    // Cloudflare 随机抽结果：条数 -> IP 列表（只存内存）
    private val cfData = mutableMapOf<Int, List<String>>()
    private val cfCountViews = mutableMapOf<Int, TextView>()
    private val cfGenTime = mutableMapOf<Int, String>()

    private lateinit var btnTabPool: Button
    private lateinit var btnTabCf: Button
    private lateinit var panelPool: NestedScrollView
    private lateinit var panelCf: NestedScrollView
    private lateinit var inputGistLink: EditText
    private lateinit var gistStatus: TextView
    private lateinit var subEmpty: TextView
    private lateinit var subClashRow: LinearLayout
    private lateinit var subSingboxRow: LinearLayout
    private lateinit var subClashLink: TextView
    private lateinit var subSingboxLink: TextView
    private lateinit var poolTitleSub: TextView
    private lateinit var poolCollectInfo: TextView
    private lateinit var poolStatus: TextView
    private lateinit var poolGroups: LinearLayout
    private lateinit var cfSource: TextView
    private lateinit var cfStatus: TextView
    private lateinit var cfGroups: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        // 启动崩溃捕捉：任何未捕获的异常都先把堆栈存下来，下次启动时显示在屏幕上，方便定位闪退原因
        Thread.setDefaultUncaughtExceptionHandler { _, e ->
            try {
                val sw = java.io.StringWriter()
                e.printStackTrace(java.io.PrintWriter(sw))
                getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("last_crash", sw.toString()).apply()
            } catch (_: Exception) { }
        }
        val lastCrash = try {
            getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("last_crash", "") ?: ""
        } catch (_: Exception) { "" }
        if (lastCrash.isNotBlank()) {
            super.onCreate(savedInstanceState)
            try { getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("last_crash").apply() } catch (_: Exception) { }
            showCrashScreen("上次启动崩溃的原因：\n\n$lastCrash")
            return
        }
        try {
            super.onCreate(savedInstanceState)
            initMain()
        } catch (e: Throwable) {
            saveCrashTrace(e)
            try { showCrashScreen("启动时出错：\n\n" + e.stackTraceToString()) } catch (_: Exception) { }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    private fun saveCrashTrace(e: Throwable) {
        try {
            val sw = java.io.StringWriter()
            e.printStackTrace(java.io.PrintWriter(sw))
            getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("last_crash", sw.toString()).apply()
        } catch (_: Exception) { }
    }

    // 崩溃信息显示页：纯代码写的简单界面，不依赖 layout，出错时也能显示
    private fun showCrashScreen(text: String) {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(0xFF0D1117.toInt())
        val scroll = ScrollView(this)
        scroll.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        val tv = TextView(this)
        tv.text = text + "\n\n请把这页截图发给小二。"
        tv.textSize = 13f
        tv.setTextColor(0xFFE6EDF3.toInt())
        tv.setPadding(32, 32, 32, 32)
        tv.setTextIsSelectable(true)
        scroll.addView(tv)
        val btn = Button(this)
        btn.text = "知道了，进入应用（重试）"
        btn.textSize = 15f
        btn.setOnClickListener {
            try { getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("last_crash").apply() } catch (_: Exception) { }
            try {
                initMain()
            } catch (e: Throwable) {
                saveCrashTrace(e)
                try { showCrashScreen("启动时出错：\n\n" + e.stackTraceToString()) } catch (_: Exception) { }
            }
        }
        root.addView(scroll)
        root.addView(btn)
        setContentView(root)
    }

    private fun initMain() {
        setContentView(R.layout.activity_main)
        btnTabPool = findViewById(R.id.btnTabPool)
        btnTabCf = findViewById(R.id.btnTabCf)
        panelPool = findViewById(R.id.panelPool)
        panelCf = findViewById(R.id.panelCf)
        inputGistLink = findViewById(R.id.inputGistLink)
        gistStatus = findViewById(R.id.gistStatus)
        subEmpty = findViewById(R.id.subEmpty)
        subClashRow = findViewById(R.id.subClashRow)
        subSingboxRow = findViewById(R.id.subSingboxRow)
        subClashLink = findViewById(R.id.subClashLink)
        subSingboxLink = findViewById(R.id.subSingboxLink)
        poolTitleSub = findViewById(R.id.poolTitleSub)
        poolCollectInfo = findViewById(R.id.poolCollectInfo)
        poolStatus = findViewById(R.id.poolStatus)
        poolGroups = findViewById(R.id.poolGroups)
        cfSource = findViewById(R.id.cfSource)
        cfStatus = findViewById(R.id.cfStatus)
        cfGroups = findViewById(R.id.cfGroups)

        // 来源行：链接部分蓝色下划线，点开 Cloudflare 官方 IP 列表
        val sourceText = "来源：Cloudflare 官方 IP 列表（15 个 IPv4 段，共约 1,524,736 个地址）"
        val linkText = "Cloudflare 官方 IP 列表"
        val span = SpannableString(sourceText)
        val linkStart = sourceText.indexOf(linkText)
        val linkEnd = linkStart + linkText.length
        span.setSpan(URLSpan(CF_LIST_URL), linkStart, linkEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        span.setSpan(ForegroundColorSpan(0xFF58A6FF.toInt()), linkStart, linkEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        span.setSpan(UnderlineSpan(), linkStart, linkEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        cfSource.text = span
        cfSource.movementMethod = LinkMovementMethod.getInstance()

        // 读回已保存的 Gist 配置
        gistUser = prefs.getString(KEY_GIST_USER, null)
        gistId = prefs.getString(KEY_GIST_ID, null)
        if (!gistUser.isNullOrBlank() && !gistId.isNullOrBlank()) {
            inputGistLink.setText("https://gist.github.com/$gistUser/$gistId")
            gistStatus.text = "已配置（只存本机）"
        }
        updateSubLinks()
        buildPoolGroups()
        buildCfGroups()

        btnTabPool.setOnClickListener { showTab(pool = true) }
        btnTabCf.setOnClickListener { showTab(pool = false) }
        findViewById<Button>(R.id.btnSaveGist).setOnClickListener { saveGist() }
        findViewById<Button>(R.id.btnRefreshPool).setOnClickListener { refreshPool() }
        findViewById<Button>(R.id.btnCopyClash).setOnClickListener { copySubLink(isClash = true) }
        findViewById<Button>(R.id.btnOpenClash).setOnClickListener { openSubLink(isClash = true) }
        findViewById<Button>(R.id.btnCopySingbox).setOnClickListener { copySubLink(isClash = false) }
        findViewById<Button>(R.id.btnOpenSingbox).setOnClickListener { openSubLink(isClash = false) }

        // 已配置过 Gist 的话，打开就自动拉一次 IP 池
        if (!gistUser.isNullOrBlank() && !gistId.isNullOrBlank()) refreshPool()
    }

    // ---------------- 标签切换 ----------------
    private fun showTab(pool: Boolean) {
        panelPool.visibility = if (pool) View.VISIBLE else View.GONE
        panelCf.visibility = if (pool) View.GONE else View.VISIBLE
        btnTabPool.setBackgroundResource(if (pool) R.drawable.bg_button_primary else R.drawable.bg_tab_inactive)
        btnTabPool.setTextColor(if (pool) 0xFFFFFFFF.toInt() else 0xFF58A6FF.toInt())
        btnTabCf.setBackgroundResource(if (pool) R.drawable.bg_tab_inactive else R.drawable.bg_button_primary)
        btnTabCf.setTextColor(if (pool) 0xFF58A6FF.toInt() else 0xFFFFFFFF.toInt())
    }

    // ---------------- Gist 配置 ----------------
    // 接受两种输入：完整的 Gist 链接（https://gist.github.com/<用户>/<ID>，或 raw 链接），
    // 或裸的 32 位 Gist ID（此时用户名用默认的 yg0082）。返回（用户名，ID），解析不出返回 null。
    private fun parseGistLink(input: String): Pair<String, String>? {
        val s = input.trim()
        if (s.isEmpty()) return null
        if (Regex("^[0-9a-fA-F]{32}$").matches(s)) {
            return "yg0082" to s.lowercase(Locale.US)
        }
        val m = Regex("gist\\.github(?:usercontent)?\\.com/([^/\\s]+)/([0-9a-fA-F]{32})").find(s)
        if (m != null) return m.groupValues[1] to m.groupValues[2].lowercase(Locale.US)
        return null
    }

    private fun saveGist() {
        val parsed = parseGistLink(inputGistLink.text.toString())
        if (parsed == null) {
            gistStatus.text = "链接不对：粘贴聚合器 Gist 链接，或 32 位 Gist ID"
            Toast.makeText(this, "链接不对，请检查", Toast.LENGTH_SHORT).show()
            return
        }
        gistUser = parsed.first
        gistId = parsed.second
        prefs.edit().putString(KEY_GIST_USER, gistUser).putString(KEY_GIST_ID, gistId).apply()
        gistStatus.text = "已保存（只存本机）"
        updateSubLinks()
        Toast.makeText(this, "Gist 已保存", Toast.LENGTH_SHORT).show()
        refreshPool()
    }

    private fun updateSubLinks() {
        val user = gistUser
        val id = gistId
        val configured = !user.isNullOrBlank() && !id.isNullOrBlank()
        subEmpty.visibility = if (configured) View.GONE else View.VISIBLE
        subClashRow.visibility = if (configured) View.VISIBLE else View.GONE
        subSingboxRow.visibility = if (configured) View.VISIBLE else View.GONE
        if (!configured) return
        subClashLink.text = "$GIST_RAW_BASE/$user/$id/raw/clash.yaml"
        subSingboxLink.text = "$GIST_RAW_BASE/$user/$id/raw/singbox.json"
    }

    private fun subUrl(isClash: Boolean): String? {
        val user = gistUser
        val id = gistId
        if (user.isNullOrBlank() || id.isNullOrBlank()) return null
        return if (isClash) "$GIST_RAW_BASE/$user/$id/raw/clash.yaml"
        else "$GIST_RAW_BASE/$user/$id/raw/singbox.json"
    }

    private fun copySubLink(isClash: Boolean) {
        val url = subUrl(isClash)
        if (url == null) {
            Toast.makeText(this, "还没配置 Gist 链接", Toast.LENGTH_SHORT).show()
            return
        }
        copyText(if (isClash) "Clash 订阅链接" else "sing-box 订阅链接", url)
    }

    private fun openSubLink(isClash: Boolean) {
        val url = subUrl(isClash)
        if (url == null) {
            Toast.makeText(this, "还没配置 Gist 链接", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            Toast.makeText(this, "打不开链接", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------------- 优选 IP 池 ----------------
    private fun buildPoolGroups() {
        poolGroups.removeAllViews()
        poolCountViews.clear()
        poolCopyBtns.clear()
        for ((region, label) in REGIONS) {
            poolGroups.addView(buildPoolCard(region, label, withListBox = false))
        }
        poolGroups.addView(buildPoolCard("all", "全部", withListBox = true))
    }

    // 一个地区一张卡片：第一行地区名 + 条数，第二行一键复制全部 N 条 + 下载 .txt；
    // 全部组再加一个 400dp 高的可滚动 IP 显示框
    private fun buildPoolCard(region: String, label: String, withListBox: Boolean): LinearLayout {
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.setBackgroundResource(R.drawable.bg_card)
        card.setPadding(dp(16), dp(16), dp(16), dp(16))
        card.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(12) }

        val row1 = LinearLayout(this)
        row1.orientation = LinearLayout.HORIZONTAL
        row1.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        val nameTv = TextView(this)
        nameTv.text = label
        nameTv.textSize = 18f
        nameTv.setTypeface(null, Typeface.BOLD)
        nameTv.setTextColor(0xFFE6EDF3.toInt())
        nameTv.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        row1.addView(nameTv)
        val countTv = TextView(this)
        countTv.text = "0 条"
        countTv.textSize = 16f
        countTv.setTextColor(0xFF8B949E.toInt())
        row1.addView(countTv)
        card.addView(row1)
        poolCountViews[region] = countTv

        val row2 = LinearLayout(this)
        row2.orientation = LinearLayout.HORIZONTAL
        row2.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(12) }
        val copyBtn = Button(this)
        copyBtn.text = "一键复制全部"
        copyBtn.textSize = 15f
        copyBtn.setTextColor(0xFFFFFFFF.toInt())
        copyBtn.setBackgroundResource(R.drawable.bg_button_primary)
        copyBtn.setAllCaps(false)
        copyBtn.minimumHeight = 0
        copyBtn.layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f)
        copyBtn.setOnClickListener { copyPoolGroup(region, label) }
        row2.addView(copyBtn)
        val dlBtn = Button(this)
        dlBtn.text = "下载 .txt"
        dlBtn.textSize = 15f
        dlBtn.setTextColor(0xFF58A6FF.toInt())
        dlBtn.setBackgroundResource(R.drawable.bg_button_outline)
        dlBtn.setAllCaps(false)
        dlBtn.minimumHeight = 0
        dlBtn.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, dp(48)
        ).apply { marginStart = dp(8) }
        dlBtn.setOnClickListener { downloadPoolGroup(region, label) }
        row2.addView(dlBtn)
        card.addView(row2)
        poolCopyBtns[region] = copyBtn

        if (withListBox) {
            val scroll = NestedScrollView(this)
            scroll.setBackgroundResource(R.drawable.bg_input)
            scroll.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(400)
            ).apply { topMargin = dp(12) }
            val listTv = TextView(this)
            listTv.text = ""
            listTv.textSize = 14f
            listTv.setTextColor(0xFFE6EDF3.toInt())
            listTv.setPadding(dp(8), dp(8), dp(8), dp(8))
            listTv.setTextIsSelectable(true)
            listTv.setLineSpacing(dp(4).toFloat(), 1f)
            scroll.addView(listTv)
            allIpListView = listTv
            card.addView(scroll)
        }
        return card
    }

    private fun refreshPool() {
        val user = gistUser
        val id = gistId
        if (user.isNullOrBlank() || id.isNullOrBlank()) {
            poolStatus.text = "还没配置 Gist 链接，先在上面粘贴并保存"
            return
        }
        if (poolJob?.isActive == true) return
        poolStatus.text = "正在拉取 IP 池…"
        poolJob = scope.launch {
            val keys = REGIONS.map { it.first } + "all"
            val fetched = mutableMapOf<String, List<String>>()
            val failed = mutableListOf<String>()
            val jobs = keys.map { region ->
                async(Dispatchers.IO) {
                    val file = if (region == "all") "preferred_ips_all.txt" else "preferred_ips_${region}.txt"
                    val url = "$GIST_RAW_BASE/$user/$id/raw/$file"
                    try {
                        val body = httpGet(url) ?: throw Exception("空响应")
                        region to parseIpLines(body)
                    } catch (e: Exception) {
                        failed.add(region)
                        null
                    }
                }
            }
            // 上次收集时间：调 Gist 接口读 updated_at，取 UTC 原值 MM/DD HH:MM（和空间显示一致）；拿不到就只显示频率
            val lastCollected = async(Dispatchers.IO) {
                try {
                    val body = httpGet("$GIST_API_BASE/$id") ?: return@async null
                    val raw = JSONObject(body).optString("updated_at", "")
                    parseGistTime(raw)
                } catch (e: Exception) {
                    null
                }
            }
            for (d in jobs) {
                val r = d.await()
                if (r != null) fetched[r.first] = r.second
            }
            val collectedAt = lastCollected.await()
            // 只更新拉取成功的组，失败的组保留旧数据
            for ((region, ips) in fetched) poolData[region] = ips
            updatePoolCounts()
            poolCollectInfo.text = if (collectedAt != null) {
                "每 4 小时自动收集一次 · 上次收集 $collectedAt"
            } else {
                "每 4 小时自动收集一次"
            }
            val total = poolData["all"]?.size ?: 0
            poolStatus.text = if (failed.isEmpty()) {
                "已更新：${fetched.size} 组，全部共 $total 条"
            } else {
                val failLabels = failed.map { region ->
                    if (region == "all") "全部" else (REGIONS.firstOrNull { it.first == region }?.second ?: region)
                }
                "已更新 ${fetched.size} 组，全部共 $total 条；失败 ${failLabels.joinToString("、")}（保留旧数据）"
            }
        }
    }

    private fun updatePoolCounts() {
        for ((region, label) in REGIONS) {
            val n = poolData[region]?.size ?: 0
            poolCountViews[region]?.text = "$n 条"
            poolCopyBtns[region]?.text = if (n > 0) "一键复制全部 $n 条" else "一键复制全部"
        }
        val allN = poolData["all"]?.size ?: 0
        poolCountViews["all"]?.text = "$allN 条"
        poolCopyBtns["all"]?.text = if (allN > 0) "一键复制全部 $allN 条" else "一键复制全部"
        poolTitleSub.text = "聚合器收集 · 按地区分开 · 共 $allN 条"
        allIpListView?.text = poolData["all"]?.joinToString("\n") ?: ""
    }

    private fun copyPoolGroup(region: String, label: String) {
        val ips = poolData[region]
        if (ips.isNullOrEmpty()) {
            Toast.makeText(this, "还没有数据，先点刷新拉取", Toast.LENGTH_SHORT).show()
            return
        }
        copyText("$label ${ips.size} 条", ips.joinToString("\n"))
    }

    private fun downloadPoolGroup(region: String, label: String) {
        val ips = poolData[region]
        if (ips.isNullOrEmpty()) {
            Toast.makeText(this, "还没有数据，先点刷新拉取", Toast.LENGTH_SHORT).show()
            return
        }
        val filename = if (region == "all") "preferred_ips_all.txt" else "preferred_ips_${region}.txt"
        val msg = saveToDownloads(filename, ips.joinToString("\n") + "\n")
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    // ---------------- Cloudflare 优选 IP ----------------
    private fun buildCfGroups() {
        cfGroups.removeAllViews()
        cfCountViews.clear()
        for (count in CF_COUNTS) {
            cfGroups.addView(buildCfCard(count))
        }
    }

    // 一组一张卡片：第一行 N 个 + 当前 N 个 · 生成于时间，第二行抓取 N 个，第三行一键复制全部 N 个 IP + 下载 .txt（IP 不在页面上显示）
    private fun buildCfCard(count: Int): LinearLayout {
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.setBackgroundResource(R.drawable.bg_card)
        card.setPadding(dp(16), dp(16), dp(16), dp(16))
        card.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(12) }

        val row1 = LinearLayout(this)
        row1.orientation = LinearLayout.HORIZONTAL
        row1.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        val nameTv = TextView(this)
        nameTv.text = "$count 个"
        nameTv.textSize = 18f
        nameTv.setTypeface(null, Typeface.BOLD)
        nameTv.setTextColor(0xFFE6EDF3.toInt())
        row1.addView(nameTv)
        val statusTv = TextView(this)
        statusTv.text = "尚未生成"
        statusTv.textSize = 14f
        statusTv.setTextColor(0xFF8B949E.toInt())
        statusTv.gravity = Gravity.END
        statusTv.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            .apply { marginStart = dp(8) }
        row1.addView(statusTv)
        card.addView(row1)
        cfCountViews[count] = statusTv

        val genBtn = Button(this)
        genBtn.text = "抓取 $count 个"
        genBtn.textSize = 15f
        genBtn.setTextColor(0xFFFFFFFF.toInt())
        genBtn.setBackgroundResource(R.drawable.bg_button_primary)
        genBtn.setAllCaps(false)
        genBtn.minimumHeight = 0
        genBtn.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, dp(48)
        ).apply { topMargin = dp(12) }
        genBtn.setOnClickListener { generateCfGroup(count) }
        card.addView(genBtn)

        val row2 = LinearLayout(this)
        row2.orientation = LinearLayout.HORIZONTAL
        row2.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) }
        val copyBtn = Button(this)
        copyBtn.text = "一键复制全部 $count 个 IP"
        copyBtn.textSize = 15f
        copyBtn.setTextColor(0xFFFFFFFF.toInt())
        copyBtn.setBackgroundResource(R.drawable.bg_button_primary)
        copyBtn.setAllCaps(false)
        copyBtn.minimumHeight = 0
        copyBtn.layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f)
        copyBtn.setOnClickListener { copyCfGroup(count) }
        row2.addView(copyBtn)
        val dlBtn = Button(this)
        dlBtn.text = "下载 .txt"
        dlBtn.textSize = 15f
        dlBtn.setTextColor(0xFF58A6FF.toInt())
        dlBtn.setBackgroundResource(R.drawable.bg_button_outline)
        dlBtn.setAllCaps(false)
        dlBtn.minimumHeight = 0
        dlBtn.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, dp(48)
        ).apply { marginStart = dp(8) }
        dlBtn.setOnClickListener { downloadCfGroup(count) }
        row2.addView(dlBtn)
        card.addView(row2)
        return card
    }

    private fun generateCfGroup(count: Int) {
        val ips = generateCf(count)
        cfData[count] = ips
        val now = nowStr()
        cfGenTime[count] = now
        cfCountViews[count]?.text = "当前 ${ips.size} 个 · 生成于 $now"
        cfStatus.text = "已生成 $count 条（去重后 ${ips.size} 条）"
    }

    private fun copyCfGroup(count: Int) {
        val ips = cfData[count]
        if (ips.isNullOrEmpty()) {
            Toast.makeText(this, "还没生成，先点抓取 $count 个", Toast.LENGTH_SHORT).show()
            return
        }
        copyText("Cloudflare ${ips.size} 条", ips.joinToString("\n"))
    }

    private fun downloadCfGroup(count: Int) {
        val ips = cfData[count]
        if (ips.isNullOrEmpty()) {
            Toast.makeText(this, "还没生成，先点抓取 $count 个", Toast.LENGTH_SHORT).show()
            return
        }
        val msg = saveToDownloads("cloudflare_$count.txt", ips.joinToString("\n") + "\n")
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    // 从 15 个官方段里按可用地址数加权随机抽 count 个不重复 IP（去掉网络地址和广播地址）
    private fun generateCf(count: Int): List<String> {
        val random = java.util.Random()
        val seen = HashSet<String>()
        val out = ArrayList<String>(count)
        // 可用主机数：总数 > 2 时去掉网络地址和广播地址，否则全可用
        fun usable(total: Long): Long = if (total > 2) total - 2 else total
        val totalWeight = cfNetworks.sumOf { usable(it.third) }
        if (totalWeight <= 0) return out
        var attempts = 0
        val maxAttempts = count * 20 + 100
        while (out.size < count && attempts < maxAttempts) {
            attempts++
            var r = (random.nextDouble() * totalWeight).toLong()
            var chosen = cfNetworks.last()
            for (entry in cfNetworks) {
                if (r < usable(entry.third)) { chosen = entry; break }
                r -= usable(entry.third)
            }
            val (net, _, total) = chosen
            if (total <= 0) continue
            // 主机偏移：/32 取 0；/31 取 0 或 1；其余取 1..总数-2（去掉网络地址和广播地址）
            val offset = when {
                total <= 1 -> 0
                total == 2L -> random.nextInt(2)
                else -> 1 + random.nextInt((total - 2).toInt())
            }
            val ip = intToIpv4(net + offset)
            if (seen.add(ip)) out.add(ip)
        }
        return out
    }

    // ---------------- 网络 ----------------
    private fun httpGet(url: String): String? {
        return try {
            val req = Request.Builder().url(url).header("User-Agent", "IPPool/1.1").build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                resp.body?.string()
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun parseIpLines(body: String): List<String> {
        return body.lines().map { it.trim() }.filter { it.isNotEmpty() && ipv4ToInt(it) != null }
    }

    // Gist updated_at 是 UTC 时间 "2026-11-15T13:41:22Z"，取原值转成 MM/DD HH:MM（和空间显示一致）
    private fun parseGistTime(raw: String): String? {
        val m = Regex("^(\\d{4})-(\\d{2})-(\\d{2})T(\\d{2}):(\\d{2})").find(raw.trim()) ?: return null
        return "${m.groupValues[2]}/${m.groupValues[3]} ${m.groupValues[4]}:${m.groupValues[5]}"
    }

    // 当前本地时间 MM/DD HH:mm（用于 Cloudflare 各组“生成于”）
    private fun nowStr(): String {
        return SimpleDateFormat("MM/dd HH:mm", Locale.US).format(Date())
    }

    // ---------------- 工具 ----------------
    private fun ipv4ToInt(ip: String): Int? {
        val parts = ip.split(".")
        if (parts.size != 4) return null
        var v = 0
        for (p in parts) {
            val n = p.toIntOrNull() ?: return null
            if (n !in 0..255) return null
            v = (v shl 8) or n
        }
        return v
    }

    private fun intToIpv4(v: Int): String {
        return "${(v ushr 24) and 0xFF}.${(v ushr 16) and 0xFF}.${(v ushr 8) and 0xFF}.${v and 0xFF}"
    }

    private fun dp(n: Int): Int = (n * resources.displayMetrics.density).toInt()

    private fun copyText(label: String, text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(this, "已复制：$label", Toast.LENGTH_SHORT).show()
    }

    // 保存 .txt 到下载文件夹：Android 10+ 用 MediaStore（无需权限），更早版本走公开下载目录
    private fun saveToDownloads(filename: String, text: String): String {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return "保存失败：无法创建文件"
                contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(text.toByteArray(Charsets.UTF_8))
                } ?: return "保存失败：无法写入文件"
                "已保存到下载：$filename"
            } else {
                @Suppress("DEPRECATION")
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!dir.exists()) dir.mkdirs()
                val file = File(dir, filename)
                FileOutputStream(file).use { out ->
                    out.write(text.toByteArray(Charsets.UTF_8))
                }
                "已保存到下载：$filename"
            }
        } catch (e: Exception) {
            "保存失败：${e.message ?: "未知错误"}"
        }
    }
}
