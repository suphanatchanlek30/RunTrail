package com.example.runtrail.ui.history

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.runtrail.R
import com.example.runtrail.data.PrefsHelper
import com.example.runtrail.data.RunRepository
import com.example.runtrail.data.RunRepository.Filter
import com.example.runtrail.data.RunRepository.Sort
import com.example.runtrail.databinding.ActivityHistoryBinding
import com.example.runtrail.ui.common.BottomNav
import com.example.runtrail.ui.common.Dialogs
import com.example.runtrail.ui.common.applySystemBarsPadding
import com.example.runtrail.ui.common.setupEdgeToEdge
import com.example.runtrail.ui.common.runAsync
import com.example.runtrail.ui.common.toast
import com.example.runtrail.ui.detail.RunDetailActivity
import com.example.runtrail.ui.edit.AddEditRunActivity

/** Run history with search, filters and sorting. Long-press a card to delete it. */
class HistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHistoryBinding
    private lateinit var adapter: RunAdapter
    private var filter = Filter.ALL
    private var sort = Sort.NEWEST
    private var all: List<com.example.runtrail.data.Run> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupEdgeToEdge()
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.header.applySystemBarsPadding(top = true)

        filter = savedInstanceState?.getString(KEY_FILTER)?.let { Filter.valueOf(it) } ?: Filter.ALL
        sort = savedInstanceState?.getString(KEY_SORT)?.let { Sort.valueOf(it) } ?: Sort.NEWEST

        BottomNav.setup(this, binding.navContainer.bottomNav, R.id.nav_history)

        adapter = RunAdapter(
            onClick = { run ->
                startActivity(Intent(this, RunDetailActivity::class.java).putExtra(RunDetailActivity.EXTRA_RUN_ID, run.id))
            },
            onLongClick = { run ->
                Dialogs.confirmDelete(this, run) { alsoServer ->
                    RunRepository.delete(run.id, alsoServer)
            com.example.runtrail.sync.SyncManager.requestSync(this)
                    toast(getString(if (alsoServer) R.string.toast_deleted_both else R.string.toast_deleted))
                    reload()
                }
            },
        )
        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter

        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                binding.btnClearSearch.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
                load()
            }
        })
        binding.btnClearSearch.setOnClickListener { binding.etSearch.setText("") }

        binding.chipAll.setOnClickListener { filter = Filter.ALL; load() }
        binding.chipWeek.setOnClickListener { filter = Filter.THIS_WEEK; load() }
        binding.chipMonth.setOnClickListener { filter = Filter.THIS_MONTH; load() }
        binding.chip5k.setOnClickListener { filter = Filter.OVER_5K; load() }
        binding.btnFilter.setOnClickListener { startActivity(Intent(this, AddEditRunActivity::class.java)) }
        setupSortSpinner()
    }

    private val onDataChanged: () -> Unit = { reload() }

    override fun onResume() {
        super.onResume()
        binding.navContainer.bottomNav.selectedItemId = R.id.nav_history
        RunRepository.addListener(onDataChanged)
        reload()
    }

    /** Reloads from the database on open or data change; search and filters work on the in-memory list. */
    private var loadGen = 0

    private fun reload() {
        // Loading every run can take a few hundred ms, so read off the main thread.
        val gen = ++loadGen
        runAsync({ RunRepository.getAll() }) { r ->
            if (gen != loadGen) return@runAsync
            all = r.getOrDefault(all)
            load()
        }
    }

    override fun onPause() {
        super.onPause()
        RunRepository.removeListener(onDataChanged)
    }

    private fun load() {
        binding.chipAll.isSelected = filter == Filter.ALL
        binding.chipWeek.isSelected = filter == Filter.THIS_WEEK
        binding.chipMonth.isSelected = filter == Filter.THIS_MONTH
        binding.chip5k.isSelected = filter == Filter.OVER_5K

        val list = RunRepository.filterAndSort(all, binding.etSearch.text.toString(), filter, sort)
        adapter.miles = PrefsHelper(this).useMiles
        adapter.submit(list, groupByMonth = sort == Sort.NEWEST || sort == Sort.OLDEST)
        binding.emptyView.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
    }

    /** Sort options; the order of R.array.sort_options matches [Sort]. */
    private fun setupSortSpinner() {
        val sortAdapter = ArrayAdapter.createFromResource(this, R.array.sort_options, R.layout.item_spinner_sort)
        sortAdapter.setDropDownViewResource(R.layout.item_dropdown)
        binding.spinnerSort.adapter = sortAdapter
        binding.spinnerSort.setSelection(sort.ordinal, false)   // restored from saved state; no callback
        binding.spinnerSort.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val picked = Sort.entries[position]
                if (picked != sort) { sort = picked; load() }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_FILTER, filter.name)
        outState.putString(KEY_SORT, sort.name)
    }

    companion object {
        private const val KEY_FILTER = "filter"
        private const val KEY_SORT = "sort"
    }
}
