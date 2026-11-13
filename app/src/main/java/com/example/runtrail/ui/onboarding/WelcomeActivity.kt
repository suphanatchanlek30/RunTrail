package com.example.runtrail.ui.onboarding

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.example.runtrail.R
import com.example.runtrail.databinding.ActivityWelcomeBinding
import com.example.runtrail.domain.RouteGenerator
import com.example.runtrail.ui.common.applySystemBarsPadding
import com.example.runtrail.ui.common.dp
import com.example.runtrail.ui.common.installExitGuard
import com.example.runtrail.ui.common.setupEdgeToEdge

/** First-launch welcome carousel. */
class WelcomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWelcomeBinding
    private val handler = Handler(Looper.getMainLooper())
    private var page = 0

    private val titles = intArrayOf(R.string.welcome_title_1, R.string.welcome_title_2, R.string.welcome_title_3)
    private val bodies = intArrayOf(R.string.welcome_body_1, R.string.welcome_body_2, R.string.welcome_body_3)

    private val autoNext = object : Runnable {
        override fun run() {
            showPage((page + 1) % titles.size)
            handler.postDelayed(this, AUTO_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupEdgeToEdge(lightStatusBar = false)
        binding = ActivityWelcomeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarsPadding(top = true, bottom = true)

        binding.heroMap.clipToOutline = true
        binding.map.dark = true
        binding.map.routeTop = 0.02f
        binding.map.routeBottom = 0.78f
        binding.map.setRoute(RouteGenerator.demoRoute(seed = 1))

        page = savedInstanceState?.getInt(KEY_PAGE) ?: 0
        showPage(page)

        listOf(binding.dot0, binding.dot1, binding.dot2).forEachIndexed { index, dot ->
            dot.setOnClickListener { restartAuto(); showPage(index) }
        }
        val next = View.OnClickListener { restartAuto(); showPage((page + 1) % titles.size) }
        binding.tvHeadline.setOnClickListener(next)
        binding.tvBody.setOnClickListener(next)

        binding.btnStart.setOnClickListener {
            startActivity(Intent(this, PermissionsActivity::class.java))
        }
        installExitGuard(onlyWhenTaskRoot = true)
    }

    private fun showPage(index: Int) {
        page = index
        binding.tvHeadline.setText(titles[index])
        binding.tvBody.setText(bodies[index])
        listOf(binding.dot0, binding.dot1, binding.dot2).forEachIndexed { i, dot ->
            dot.setBackgroundResource(if (i == index) R.drawable.pager_active else R.drawable.bg_pill_ink2)
            dot.layoutParams = dot.layoutParams.apply { width = dp(if (i == index) 24f else 6f) }
        }
    }

    private fun restartAuto() {
        handler.removeCallbacks(autoNext)
        handler.postDelayed(autoNext, AUTO_MS)
    }

    override fun onResume() {
        super.onResume()
        handler.postDelayed(autoNext, AUTO_MS)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(autoNext)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_PAGE, page)
    }

    companion object {
        private const val KEY_PAGE = "page"
        private const val AUTO_MS = 4500L
    }
}
