package fr.bonobo.dnsphere.ui

import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.chip.ChipGroup
import fr.bonobo.dnsphere.R
import fr.bonobo.dnsphere.data.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class StatsActivity : AppCompatActivity() {

    private lateinit var database: AppDatabase

    // Views
    private lateinit var chipGroupPeriod: ChipGroup
    private lateinit var tvTotalBlocked: TextView
    private lateinit var tvAdsCount: TextView
    private lateinit var tvTrackersCount: TextView
    private lateinit var tvMalwareCount: TextView
    private lateinit var tvOtherCount: TextView
    private lateinit var progressAds: ProgressBar
    private lateinit var progressTrackers: ProgressBar
    private lateinit var progressMalware: ProgressBar
    private lateinit var progressOther: ProgressBar
    private lateinit var chartContainer: LinearLayout
    private lateinit var chartHourContainer: LinearLayout
    private lateinit var topDomainsContainer: LinearLayout
    private lateinit var tvNoData: TextView
    private lateinit var progressLoading: ProgressBar

    private var currentPeriod = StatsPeriod.TODAY

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            setContentView(R.layout.activity_stats)

            database = AppDatabase.getInstance(this)

            setupToolbar()
            initViews()
            setupPeriodSelector()
            loadStats()

        } catch (e: Exception) {
            Log.e("StatsActivity", "❌ Erreur onCreate", e)
            e.printStackTrace()
            finish()
        }
    }

    private fun setupToolbar() {
        try {
            val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
            toolbar.setNavigationOnClickListener { finish() }
        } catch (e: Exception) {
            Log.e("StatsActivity", "❌ Erreur toolbar", e)
        }
    }

    private fun initViews() {
        chipGroupPeriod = findViewById(R.id.chipGroupPeriod)
        tvTotalBlocked = findViewById(R.id.tvTotalBlocked)
        tvAdsCount = findViewById(R.id.tvAdsCount)
        tvTrackersCount = findViewById(R.id.tvTrackersCount)
        tvMalwareCount = findViewById(R.id.tvMalwareCount)
        tvOtherCount = findViewById(R.id.tvOtherCount)
        progressAds = findViewById(R.id.progressAds)
        progressTrackers = findViewById(R.id.progressTrackers)
        progressMalware = findViewById(R.id.progressMalware)
        progressOther = findViewById(R.id.progressOther)
        chartContainer = findViewById(R.id.chartContainer)
        chartHourContainer = findViewById(R.id.chartHourContainer)
        topDomainsContainer = findViewById(R.id.topDomainsContainer)
        tvNoData = findViewById(R.id.tvNoData)
        progressLoading = findViewById(R.id.progressLoading)
    }

    private fun setupPeriodSelector() {
        chipGroupPeriod.setOnCheckedStateChangeListener { _, checkedIds ->
            if (checkedIds.isNotEmpty()) {
                currentPeriod = when (checkedIds[0]) {
                    R.id.chipToday -> StatsPeriod.TODAY
                    R.id.chipWeek -> StatsPeriod.WEEK
                    R.id.chipMonth -> StatsPeriod.MONTH
                    R.id.chipAllTime -> StatsPeriod.ALL_TIME
                    else -> StatsPeriod.TODAY
                }
                loadStats()
            }
        }
    }

    private fun loadStats() {
        progressLoading.visibility = View.VISIBLE
        tvNoData.visibility = View.GONE

        lifecycleScope.launch {
            try {
                val stats = getStatsForPeriod(currentPeriod)
                runOnUiThread {
                    updateUI(stats)
                }
            } catch (e: Exception) {
                Log.e("StatsActivity", "❌ Erreur chargement stats", e)
                e.printStackTrace()
                runOnUiThread {
                    showNoData()
                }
            } finally {
                runOnUiThread {
                    progressLoading.visibility = View.GONE
                }
            }
        }
    }

    private suspend fun getStatsForPeriod(period: StatsPeriod): StatsData {
        val startTime = when (period) {
            StatsPeriod.TODAY -> {
                val cal = Calendar.getInstance()
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.timeInMillis
            }
            StatsPeriod.WEEK -> System.currentTimeMillis() - (7 * 24 * 60 * 60 * 1000L)
            StatsPeriod.MONTH -> System.currentTimeMillis() - (30 * 24 * 60 * 60 * 1000L)
            StatsPeriod.ALL_TIME -> 0L
        }

        Log.d("StatsActivity", "📊 Chargement stats depuis: $startTime")

        val logs = database.blockLogDao().getLogsSince(startTime)

        Log.d("StatsActivity", "📊 Logs récupérés: ${logs.size}")

        val adsBlocked = logs.count { it.type == "AD" }
        val trackersBlocked = logs.count { it.type == "TRACKER" }
        val malwareBlocked = logs.count { it.type == "MALWARE" }
        val otherBlocked = logs.size - adsBlocked - trackersBlocked - malwareBlocked

        // Top domaines
        val topDomains = logs
            .groupBy { it.domain }
            .map { DomainCount(it.key, it.value.size) }
            .sortedByDescending { it.count }
            .take(10)

        // Par jour
        val blocksPerDay = logs
            .groupBy { log ->
                val cal = Calendar.getInstance()
                cal.timeInMillis = log.timestamp
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.timeInMillis / 86400000
            }
            .map { DayCount(it.key, it.value.size) }
            .sortedBy { it.dayTimestamp }
            .takeLast(7)

        // Par heure
        val blocksPerHour = logs
            .groupBy { log ->
                val cal = Calendar.getInstance()
                cal.timeInMillis = log.timestamp
                cal.get(Calendar.HOUR_OF_DAY)
            }
            .map { HourCount(it.key, it.value.size) }
            .sortedBy { it.hour }

        return StatsData(
            totalBlocked = logs.size,
            adsBlocked = adsBlocked,
            trackersBlocked = trackersBlocked,
            malwareBlocked = malwareBlocked,
            otherBlocked = otherBlocked,
            topDomains = topDomains,
            blocksPerDay = blocksPerDay,
            blocksPerHour = blocksPerHour
        )
    }

    private fun updateUI(stats: StatsData) {
        if (stats.totalBlocked == 0) {
            showNoData()
            return
        }

        tvNoData.visibility = View.GONE

        // Total
        tvTotalBlocked.text = formatNumber(stats.totalBlocked)

        // Par catégorie
        tvAdsCount.text = formatNumber(stats.adsBlocked)
        tvTrackersCount.text = formatNumber(stats.trackersBlocked)
        tvMalwareCount.text = formatNumber(stats.malwareBlocked)
        tvOtherCount.text = formatNumber(stats.otherBlocked)

        // Progress bars (pourcentage)
        val total = stats.totalBlocked.coerceAtLeast(1)
        progressAds.progress = (stats.adsBlocked * 100 / total)
        progressTrackers.progress = (stats.trackersBlocked * 100 / total)
        progressMalware.progress = (stats.malwareBlocked * 100 / total)
        progressOther.progress = (stats.otherBlocked * 100 / total)

        // Graphiques
        updateDailyChart(stats.blocksPerDay)
        updateHourlyChart(stats.blocksPerHour)
        updateTopDomains(stats.topDomains)
    }

    private fun updateDailyChart(data: List<DayCount>) {
        chartContainer.removeAllViews()

        if (data.isEmpty()) {
            val emptyText = TextView(this).apply {
                text = "Aucune donnée"
                setTextColor(getColor(R.color.gray))
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.MATCH_PARENT
                )
            }
            chartContainer.addView(emptyText)
            return
        }

        val maxCount = data.maxOfOrNull { it.count } ?: 1
        val dateFormat = SimpleDateFormat("dd/MM", Locale.getDefault())

        data.forEach { dayData ->
            val barLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                    marginStart = 4
                    marginEnd = 4
                }
            }

            // Valeur
            val valueText = TextView(this).apply {
                text = dayData.count.toString()
                setTextColor(Color.WHITE)
                textSize = 10f
                gravity = Gravity.CENTER
            }

            // Barre
            val barHeight = (dayData.count.toFloat() / maxCount * 120).toInt().coerceAtLeast(4)
            val bar = View(this).apply {
                setBackgroundColor(getColor(R.color.green))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    barHeight.dpToPx()
                ).apply {
                    topMargin = 4
                }
            }

            // Date
            val date = Date(dayData.dayTimestamp * 86400000)
            val dateText = TextView(this).apply {
                text = dateFormat.format(date)
                setTextColor(getColor(R.color.gray))
                textSize = 9f
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = 4
                }
            }

            barLayout.addView(valueText)
            barLayout.addView(bar)
            barLayout.addView(dateText)
            chartContainer.addView(barLayout)
        }
    }

    private fun updateHourlyChart(data: List<HourCount>) {
        chartHourContainer.removeAllViews()

        if (data.isEmpty()) {
            val emptyText = TextView(this).apply {
                text = "Aucune donnée"
                setTextColor(getColor(R.color.gray))
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.MATCH_PARENT
                )
            }
            chartHourContainer.addView(emptyText)
            return
        }

        val hourMap = data.associateBy { it.hour }
        val maxCount = data.maxOfOrNull { it.count } ?: 1

        for (hour in 0..23) {
            val count = hourMap[hour]?.count ?: 0

            val barLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(36.dpToPx(), LinearLayout.LayoutParams.MATCH_PARENT).apply {
                    marginStart = 2
                    marginEnd = 2
                }
            }

            val barHeight = if (maxCount > 0) (count.toFloat() / maxCount * 80).toInt().coerceAtLeast(2) else 2
            val bar = View(this).apply {
                setBackgroundColor(if (count > 0) getColor(R.color.orange) else getColor(R.color.gray))
                alpha = if (count > 0) 1f else 0.3f
                layoutParams = LinearLayout.LayoutParams(
                    20.dpToPx(),
                    barHeight.dpToPx()
                )
            }

            val hourText = TextView(this).apply {
                text = String.format("%02dh", hour)
                setTextColor(getColor(R.color.gray))
                textSize = 8f
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = 4
                }
            }

            barLayout.addView(bar)
            barLayout.addView(hourText)
            chartHourContainer.addView(barLayout)
        }
    }

    private fun updateTopDomains(domains: List<DomainCount>) {
        topDomainsContainer.removeAllViews()

        if (domains.isEmpty()) {
            val emptyText = TextView(this).apply {
                text = "Aucune donnée"
                setTextColor(getColor(R.color.gray))
                gravity = Gravity.CENTER
                setPadding(0, 16.dpToPx(), 0, 16.dpToPx())
            }
            topDomainsContainer.addView(emptyText)
            return
        }

        domains.forEachIndexed { index, domainCount ->
            val itemLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = if (index > 0) 12 else 0
                }
            }

            val positionText = TextView(this).apply {
                text = "${index + 1}."
                setTextColor(getColor(R.color.green))
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(
                    32.dpToPx(),
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            val domainText = TextView(this).apply {
                text = domainCount.domain
                setTextColor(Color.WHITE)
                textSize = 13f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
            }

            val countText = TextView(this).apply {
                text = formatNumber(domainCount.count)
                setTextColor(getColor(R.color.orange))
                textSize = 14f
                textAlignment = View.TEXT_ALIGNMENT_TEXT_END
            }

            itemLayout.addView(positionText)
            itemLayout.addView(domainText)
            itemLayout.addView(countText)
            topDomainsContainer.addView(itemLayout)
        }
    }

    private fun showNoData() {
        tvNoData.visibility = View.VISIBLE
        tvTotalBlocked.text = "0"
        tvAdsCount.text = "0"
        tvTrackersCount.text = "0"
        tvMalwareCount.text = "0"
        tvOtherCount.text = "0"
        progressAds.progress = 0
        progressTrackers.progress = 0
        progressMalware.progress = 0
        progressOther.progress = 0
        chartContainer.removeAllViews()
        chartHourContainer.removeAllViews()
        topDomainsContainer.removeAllViews()
    }

    private fun formatNumber(number: Int): String {
        return when {
            number >= 1_000_000 -> String.format("%.1fM", number / 1_000_000.0)
            number >= 1_000 -> String.format("%.1fK", number / 1_000.0)
            else -> number.toString()
        }
    }

    private fun Int.dpToPx(): Int {
        return (this * resources.displayMetrics.density).toInt()
    }
}

// Data classes
enum class StatsPeriod {
    TODAY, WEEK, MONTH, ALL_TIME
}

data class StatsData(
    val totalBlocked: Int,
    val adsBlocked: Int,
    val trackersBlocked: Int,
    val malwareBlocked: Int,
    val otherBlocked: Int,
    val topDomains: List<DomainCount>,
    val blocksPerDay: List<DayCount>,
    val blocksPerHour: List<HourCount>
)