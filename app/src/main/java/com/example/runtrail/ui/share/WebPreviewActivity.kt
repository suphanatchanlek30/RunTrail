package com.example.runtrail.ui.share

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import com.example.runtrail.R
import com.example.runtrail.data.PrefsHelper
import com.example.runtrail.data.RoutePoint
import com.example.runtrail.data.Run
import com.example.runtrail.data.RunRepository
import com.example.runtrail.databinding.ActivityWebPreviewBinding
import com.example.runtrail.domain.Fmt
import com.example.runtrail.domain.PaceColor
import com.example.runtrail.domain.RunCalculator
import com.example.runtrail.net.ApiConfig
import com.example.runtrail.ui.common.applySystemBarsPadding
import com.example.runtrail.ui.common.setupEdgeToEdge
import com.example.runtrail.ui.common.toast
import java.util.Locale

/**
 * 14 · Shared Web Page — ตัวอย่างหน้าเว็บ view.php?code=… ที่เพื่อนจะเห็น
 * ช่วง UI สร้าง HTML ในเครื่อง (แผนที่วาดเป็น SVG) ภายหลังจะเปิด URL จริงของเซิร์ฟเวอร์
 */
class WebPreviewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWebPreviewBinding

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupEdgeToEdge()
        binding = ActivityWebPreviewBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarsPadding(top = true, bottom = true)

        val run = RunRepository.getById(intent.getLongExtra(EXTRA_RUN_ID, 0)) ?: run { finish(); return }
        val url = ApiConfig.shareUrl(run.shareCode ?: "------")
        binding.tvUrl.text = url
        binding.urlBox.setOnClickListener {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("RunTrail", "https://$url"))
            toast(getString(R.string.toast_link_copied))
        }

        binding.web.settings.javaScriptEnabled = false
        binding.web.settings.allowFileAccess = true      // โหลดฟอนต์/โลโก้จาก file:///android_res/
        binding.web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (request.url.scheme == "runtrail") finish()
                return true
            }
        }
        val html = buildHtml(run)
        binding.btnRefresh.setOnClickListener { load(html) }
        load(html)
    }

    private fun load(html: String) {
        binding.web.loadDataWithBaseURL("file:///android_res/", html, "text/html", "utf-8", null)
    }

    private fun buildHtml(run: Run): String {
        val prefs = PrefsHelper(this)
        val hideEnds = intent.getBooleanExtra(EXTRA_HIDE_ENDS, true)
        val showKcal = intent.getBooleanExtra(EXTRA_SHOW_KCAL, false)
        val route = RunRepository.route(run)
        val pace = Fmt.pace(RunCalculator.pace(run))
        val kcalCard = if (showKcal) card("แคลอรี่", Fmt.number(run.calories), "kcal") else ""
        val endTime = run.endTime ?: (run.startTime + run.movingTimeS * 1000)
        val hideChip = if (hideEnds) """<div class="chip">◌ ซ่อนจุดเริ่ม/จบ 200 ม.</div>""" else ""

        return """
<!doctype html><html lang="th"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<style>
@font-face{font-family:Plex;src:url('font/plex_regular.ttf');font-weight:400}
@font-face{font-family:Plex;src:url('font/plex_medium.ttf');font-weight:500}
@font-face{font-family:Plex;src:url('font/plex_semibold.ttf');font-weight:600}
@font-face{font-family:Anuphan;src:url('font/anuphan_semibold.ttf');font-weight:600}
@font-face{font-family:Anuphan;src:url('font/anuphan_bold.ttf');font-weight:700}
:root{--ink:#0F1412;--green:#00D47A;--greenDark:#00A862;--canvas:#F5F7FA;--t2:#475467;--t3:#667085;--border:#E4E7EC}
*{box-sizing:border-box;margin:0;padding:0}
body{font-family:Plex,sans-serif;background:#fff;color:#0B0F17;padding:16px 20px 28px}
.head{display:flex;align-items:center;gap:10px;height:32px}
.head img{width:32px;height:32px;border-radius:9px}
.brand{flex:1;font-family:Anuphan;font-weight:700;font-size:18px}
.open{background:var(--canvas);border-radius:999px;padding:7px 13px;font-size:12px;font-weight:500;color:#0B0F17;text-decoration:none}
h1{font-family:Anuphan;font-weight:600;font-size:28px;line-height:39px;margin-top:16px;letter-spacing:-.3px}
.date{font-size:12px;line-height:18px;color:var(--t3);margin-top:2px}
.map{position:relative;margin-top:16px;height:250px;border-radius:20px;overflow:hidden;background:#EEF2EE}
.map svg{width:100%;height:100%;display:block}
.chip{position:absolute;left:12px;bottom:14px;background:#fff;border-radius:999px;padding:6px 10px;font-size:12px;font-weight:500;box-shadow:0 1px 3px rgba(0,0,0,.08)}
.grid{display:grid;grid-template-columns:1fr 1fr;gap:10px;margin-top:16px}
.card{background:var(--canvas);border-radius:16px;padding:14px}
.lab{font-size:12px;color:var(--t3);display:flex;align-items:center;gap:6px}
.val{font-family:Anuphan;font-weight:700;font-size:18px;line-height:26px;margin-top:6px}
.val small{font-family:Plex;font-weight:500;font-size:12px;color:var(--t3);margin-left:4px}
.foot{display:flex;gap:8px;align-items:flex-start;margin-top:16px;font-size:12px;line-height:18px;color:var(--t3)}
</style></head><body>
<div class="head"><img src="drawable/logo_app_icon.png" alt=""><div class="brand">RunTrail</div>
<a class="open" href="runtrail://open">เปิดในแอป</a></div>
<h1>${esc(prefs.firstName)} วิ่ง ${Fmt.distance(run.distanceM)} กม.</h1>
<div class="date">${Fmt.longDate(run.startTime)} · ${Fmt.time(run.startTime)}–${Fmt.time(endTime)}</div>
<div class="map">${svg(route, hideEnds)}$hideChip</div>
<div class="grid">
${card("เวลา", Fmt.duration(run.movingTimeS), "")}
${card("เพซเฉลี่ย", pace, "/กม.")}
${card("ความสูงสะสม", "+${run.elevationGainM}", "ม.")}
${card("Cadence", if (run.cadenceSpm > 0) run.cadenceSpm.toString() else "--", "spm")}
$kcalCard
</div>
<div class="foot"><span>🛡</span><span>หน้านี้ดึงข้อมูลจาก detail.php?code=${run.shareCode} · แผนที่ © OpenStreetMap contributors</span></div>
</body></html>
""".trimIndent()
    }

    private fun card(label: String, value: String, unit: String) =
        """<div class="card"><div class="lab">$label</div><div class="val">$value<small>$unit</small></div></div>"""

    /** แผนที่ + เส้นทางไล่สีตามเพซเป็น SVG (ชุดสีเดียวกับในแอป) */
    private fun svg(route: List<RoutePoint>, hideEnds: Boolean): String {
        val w = 350f
        val h = 250f
        val size = 230f
        val left = (w - size) / 2
        val top = (h - size) / 2
        fun x(p: RoutePoint) = String.format(Locale.US, "%.1f", left + p.x * size)
        fun y(p: RoutePoint) = String.format(Locale.US, "%.1f", top + p.y * size)
        val trim = if (hideEnds) (route.size * 0.04f).toInt() else 0
        val pts = route.subList(trim, route.size - trim)
        val sb = StringBuilder()
        sb.append("""<svg viewBox="0 0 350 250" preserveAspectRatio="xMidYMid slice" xmlns="http://www.w3.org/2000/svg">""")
        sb.append("""<rect width="350" height="250" fill="#EEF2EE"/>""")
        for (r in 0..4) for (c in 0..6) {
            sb.append("""<rect x="${c * 56 - 10 + (r % 2) * 20}" y="${r * 56 - 8}" width="44" height="40" rx="6" fill="#E3E8E4"/>""")
        }
        sb.append("""<rect x="84" y="62" width="182" height="132" rx="36" fill="#D3F0DC"/>""")
        sb.append("""<path d="M-10 18 C120 6 200 44 360 26" stroke="#CDE5F4" stroke-width="18" fill="none"/>""")
        sb.append("""<path d="M-10 75 L360 60 M48 -10 L77 260 M300 -10 L280 260 M-10 190 L360 195" stroke="#fff" stroke-width="8" fill="none"/>""")
        val outline = pts.joinToString(" ") { "${x(it)},${y(it)}" }
        sb.append("""<polyline points="$outline" stroke="#fff" stroke-width="10" fill="none" stroke-linecap="round" stroke-linejoin="round"/>""")
        var i = 0
        while (i < pts.size - 1) {
            val color = PaceColor.forPace(pts[i].paceSecPerKm)
            var j = i + 1
            while (j < pts.size - 1 && PaceColor.forPace(pts[j].paceSecPerKm) == color) j++
            val seg = pts.subList(i, j + 1).joinToString(" ") { "${x(it)},${y(it)}" }
            sb.append("""<polyline points="$seg" stroke="${hex(color)}" stroke-width="6" fill="none" stroke-linecap="round" stroke-linejoin="round"/>""")
            i = j
        }
        val s = pts.first()
        val e = pts.last()
        sb.append("""<circle cx="${x(s)}" cy="${y(s)}" r="9" fill="#fff"/><circle cx="${x(s)}" cy="${y(s)}" r="6" fill="#0EDA62"/>""")
        sb.append("""<circle cx="${x(e)}" cy="${y(e)}" r="9" fill="#fff"/><circle cx="${x(e)}" cy="${y(e)}" r="6" fill="#0F1412"/>""")
        sb.append("</svg>")
        return sb.toString()
    }

    private fun hex(color: Int) = String.format("#%06X", color and 0xFFFFFF)

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;")

    companion object {
        const val EXTRA_RUN_ID = "run_id"
        const val EXTRA_HIDE_ENDS = "hide_ends"
        const val EXTRA_SHOW_KCAL = "show_kcal"
        const val EXTRA_SHOW_PHOTO = "show_photo"
    }
}
