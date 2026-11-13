package com.example.runtrail.ui.share

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.example.runtrail.R
import com.example.runtrail.databinding.ActivityWebPreviewBinding
import com.example.runtrail.ui.common.applySystemBarsPadding
import com.example.runtrail.ui.common.setupEdgeToEdge
import com.example.runtrail.ui.common.toast

/**
 * Opens the public share page (/s/{code}) as friends will see it.
 * The page draws its map with Leaflet, so JavaScript is enabled; external links open in the browser.
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

        val url = intent.getStringExtra(EXTRA_URL) ?: run { finish(); return }
        val host = Uri.parse(url).host
        binding.tvUrl.text = url.removePrefix("https://").removePrefix("http://")
        binding.urlBox.setOnClickListener {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("RunTrail", url))
            toast(getString(R.string.toast_link_copied))
        }

        binding.web.settings.javaScriptEnabled = true
        binding.web.settings.domStorageEnabled = true
        binding.web.settings.allowFileAccess = false
        binding.web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (request.url.host == host) return false
                startActivity(Intent(Intent.ACTION_VIEW, request.url))
                return true
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) toast(getString(R.string.web_load_failed))
            }
        }
        binding.btnRefresh.setOnClickListener { binding.web.reload() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.web.canGoBack()) binding.web.goBack() else finish()
            }
        })
        if (savedInstanceState == null) binding.web.loadUrl(url) else binding.web.restoreState(savedInstanceState)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        binding.web.saveState(outState)
    }

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_RUN_ID = "run_id"
    }
}
