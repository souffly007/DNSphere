package fr.bonobo.dnsphere.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.os.Bundle
import android.text.TextUtils
import android.util.AttributeSet
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
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

    // Donut chart inséré dynamiquement dans la card catégories
    private var donutChart: DonutChartView? = null

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
            finish()
        }
    }

    private fun setupToolbar() {
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
    }

    private fun initViews() {
        chipGroupPeriod     = findViewById(R.id.chipGroupPeriod)
        tvTotalBlocked      = findViewById(R.id.tvTotalBlocked)
        tvAdsCount          = findViewById(R.id.tvAdsCount)
        tvTrackersCount     = findViewById(R.id.tvTrackersCount)
        tvMalwareCount      = findViewById(R.id.tvMalwareCount)
        tvOtherCount        = findViewById(R.id.tvOtherCount)
        progressAds         = findViewById(R.id.progressAds)
        progressTrackers    = findViewById(R.id.progressTrackers)
        progressMalware     = findViewById(R.id.progressMalware)
        progressOther       = findViewById(R.id.progressOther)
        chartContainer      = findViewById(R.id.chartContainer)
        chartHourContainer  = findViewById(R.id.chartHourContainer)
        topDomainsContainer = findViewById(R.id.topDomainsContainer)
        tvNoData            = findViewById(R.id.tvNoData)
        progressLoading     = findViewById(R.id.progressLoading)
    }

    private fun setupPeriodSelector() {
        chipGroupPeriod.setOnCheckedStateChangeListener { _, checkedIds ->
            if (checkedIds.isNotEmpty()) {
                currentPeriod = when (checkedIds[0]) {
                    R.id.chipToday   -> StatsPeriod.TODAY
                    R.id.chipWeek    -> StatsPeriod.WEEK
                    R.id.chipMonth   -> StatsPeriod.MONTH
                    R.id.chipAllTime -> StatsPeriod.ALL_TIME
                    else             -> StatsPeriod.TODAY
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
                runOnUiThread { updateUI(stats) }
            } catch (e: Exception) {
                Log.e("StatsActivity", "❌ Erreur chargement stats", e)
                runOnUiThread { showNoData() }
            } finally {
                runOnUiThread { progressLoading.visibility = View.GONE }
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
            StatsPeriod.WEEK    -> System.currentTimeMillis() - (7 * 24 * 60 * 60 * 1000L)
            StatsPeriod.MONTH   -> System.currentTimeMillis() - (30 * 24 * 60 * 60 * 1000L)
            StatsPeriod.ALL_TIME -> 0L
        }

        val logs = database.blockLogDao().getLogsSince(startTime)

        val adsBlocked      = logs.count { it.type == "AD" }
        val trackersBlocked = logs.count { it.type == "TRACKER" }
        val malwareBlocked  = logs.count { it.type == "MALWARE" }
        val otherBlocked    = logs.size - adsBlocked - trackersBlocked - malwareBlocked

        val topDomains = logs
            .groupBy { it.domain }
            .map { DomainCount(it.key, it.value.size) }
            .sortedByDescending { it.count }
            .take(10)

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

        val blocksPerHour = logs
            .groupBy { log ->
                val cal = Calendar.getInstance()
                cal.timeInMillis = log.timestamp
                cal.get(Calendar.HOUR_OF_DAY)
            }
            .map { HourCount(it.key, it.value.size) }
            .sortedBy { it.hour }

        return StatsData(
            totalBlocked    = logs.size,
            adsBlocked      = adsBlocked,
            trackersBlocked = trackersBlocked,
            malwareBlocked  = malwareBlocked,
            otherBlocked    = otherBlocked,
            topDomains      = topDomains,
            blocksPerDay    = blocksPerDay,
            blocksPerHour   = blocksPerHour
        )
    }

    // =========================================================================
    // UI
    // =========================================================================

    private fun updateUI(stats: StatsData) {
        if (stats.totalBlocked == 0) { showNoData(); return }

        tvNoData.visibility = View.GONE

        // Chiffres animés
        animateCount(tvTotalBlocked, stats.totalBlocked, colorRes = R.color.green)
        animateCount(tvAdsCount,      stats.adsBlocked,      colorRes = R.color.green)
        animateCount(tvTrackersCount, stats.trackersBlocked, colorRes = R.color.orange)
        animateCount(tvMalwareCount,  stats.malwareBlocked,  colorRes = R.color.red)
        animateCount(tvOtherCount,    stats.otherBlocked,    colorRes = R.color.gray)

        // Progress bars animées
        val total = stats.totalBlocked.coerceAtLeast(1)
        animateProgress(progressAds,      stats.adsBlocked * 100 / total)
        animateProgress(progressTrackers, stats.trackersBlocked * 100 / total)
        animateProgress(progressMalware,  stats.malwareBlocked * 100 / total)
        animateProgress(progressOther,    stats.otherBlocked * 100 / total)

        // Donut chart
        updateDonutChart(stats)

        // Graphiques
        updateDailyChart(stats.blocksPerDay)
        updateHourlyChart(stats.blocksPerHour)
        updateTopDomains(stats.topDomains, total)
    }

    // =========================================================================
    // DONUT CHART
    // =========================================================================

    private fun updateDonutChart(stats: StatsData) {
        // Cherche le parent de progressAds pour y insérer le donut
        val categoryCard = progressAds.parent?.parent as? LinearLayout ?: return

        if (donutChart == null) {
            donutChart = DonutChartView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    200.dpToPx()
                ).apply { bottomMargin = 8.dpToPx() }
            }
            // Insérer au début de la card catégories
            categoryCard.addView(donutChart, 0)
        }

        donutChart?.setData(
            listOf(
                DonutSegment("Pubs",     stats.adsBlocked,      Color.parseColor("#4CAF50")),
                DonutSegment("Trackers", stats.trackersBlocked, Color.parseColor("#FF9800")),
                DonutSegment("Malware",  stats.malwareBlocked,  Color.parseColor("#F44336")),
                DonutSegment("Autres",   stats.otherBlocked,    Color.parseColor("#607D8B"))
            ),
            stats.totalBlocked
        )
    }

    // =========================================================================
    // GRAPHIQUE JOURNALIER
    // =========================================================================

    private fun updateDailyChart(data: List<DayCount>) {
        chartContainer.removeAllViews()

        if (data.isEmpty()) {
            chartContainer.addView(emptyLabel("Aucune donnée"))
            return
        }

        val maxCount  = data.maxOfOrNull { it.count } ?: 1
        val dateFormat = SimpleDateFormat("dd/MM", Locale.getDefault())
        val green     = Color.parseColor("#4CAF50")

        data.forEach { dayData ->
            val barLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity     = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.MATCH_PARENT, 1f
                ).apply { marginStart = 4; marginEnd = 4 }
            }

            // Valeur
            val valueText = TextView(this).apply {
                text      = formatNumber(dayData.count)
                textSize  = 9f
                setTextColor(Color.WHITE)
                gravity   = Gravity.CENTER
            }

            // Barre arrondie Canvas
            val barHeight = (dayData.count.toFloat() / maxCount * 120).toInt().coerceAtLeast(4)
            val bar = RoundedBarView(this, green, barHeight.dpToPx()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    barHeight.dpToPx()
                ).apply { topMargin = 4 }
                alpha = 0f
            }

            // Date
            val date     = Date(dayData.dayTimestamp * 86400000)
            val dateText = TextView(this).apply {
                text     = dateFormat.format(date)
                textSize = 9f
                setTextColor(Color.parseColor("#888888"))
                gravity  = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = 4 }
            }

            barLayout.addView(valueText)
            barLayout.addView(bar)
            barLayout.addView(dateText)
            chartContainer.addView(barLayout)

            // Animation d'apparition décalée
            bar.animate()
                .alpha(1f)
                .setDuration(400)
                .setStartDelay((data.indexOf(dayData) * 60).toLong())
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    // =========================================================================
    // GRAPHIQUE HORAIRE
    // =========================================================================

    private fun updateHourlyChart(data: List<HourCount>) {
        chartHourContainer.removeAllViews()

        if (data.isEmpty()) {
            chartHourContainer.addView(emptyLabel("Aucune donnée"))
            return
        }

        val hourMap  = data.associateBy { it.hour }
        val maxCount = data.maxOfOrNull { it.count } ?: 1

        for (hour in 0..23) {
            val count = hourMap[hour]?.count ?: 0

            val barLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity     = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(36.dpToPx(), LinearLayout.LayoutParams.MATCH_PARENT).apply {
                    marginStart = 2; marginEnd = 2
                }
            }

            // Couleur par intensité : bleu froid → orange chaud
            val intensity = if (maxCount > 0) count.toFloat() / maxCount else 0f
            val color = interpolateColor(
                Color.parseColor("#1565C0"), // bleu froid
                Color.parseColor("#FF6F00"), // orange chaud
                intensity
            )

            val barHeight = if (maxCount > 0) (intensity * 80).toInt().coerceAtLeast(if (count > 0) 3 else 0) else 0
            val bar = RoundedBarView(this, color, barHeight.dpToPx()).apply {
                layoutParams = LinearLayout.LayoutParams(20.dpToPx(), barHeight.coerceAtLeast(3).dpToPx())
                alpha = if (count > 0) 1f else 0.2f
            }

            val hourText = TextView(this).apply {
                text     = String.format("%02dh", hour)
                textSize = 8f
                setTextColor(Color.parseColor("#888888"))
                gravity  = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = 4 }
            }

            barLayout.addView(bar)
            barLayout.addView(hourText)
            chartHourContainer.addView(barLayout)
        }
    }

    // =========================================================================
    // TOP DOMAINES
    // =========================================================================

    private fun updateTopDomains(domains: List<DomainCount>, total: Int) {
        topDomainsContainer.removeAllViews()

        if (domains.isEmpty()) {
            topDomainsContainer.addView(emptyLabel("Aucune donnée"))
            return
        }

        val maxCount = domains.firstOrNull()?.count ?: 1

        domains.forEachIndexed { index, domainCount ->
            val wrapper = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { if (index > 0) topMargin = 14.dpToPx() }
            }

            // Ligne rank + domaine + count
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity     = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            val rankColor = when (index) {
                0    -> Color.parseColor("#FFD700") // or
                1    -> Color.parseColor("#C0C0C0") // argent
                2    -> Color.parseColor("#CD7F32") // bronze
                else -> Color.parseColor("#4CAF50")
            }

            val posText = TextView(this).apply {
                text      = "${index + 1}."
                textSize  = 13f
                setTextColor(rankColor)
                layoutParams = LinearLayout.LayoutParams(28.dpToPx(), LinearLayout.LayoutParams.WRAP_CONTENT)
            }

            val domainText = TextView(this).apply {
                text      = domainCount.domain
                textSize  = 13f
                setTextColor(Color.WHITE)
                maxLines  = 1
                ellipsize = TextUtils.TruncateAt.MIDDLE
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val countText = TextView(this).apply {
                text      = formatNumber(domainCount.count)
                textSize  = 13f
                setTextColor(Color.parseColor("#FF9800"))
                textAlignment = View.TEXT_ALIGNMENT_TEXT_END
            }

            row.addView(posText)
            row.addView(domainText)
            row.addView(countText)

            // Barre de progression horizontale sous le domaine
            val progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max      = 100
                progress = 0
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    5.dpToPx()
                ).apply { topMargin = 4.dpToPx() }
                progressTintList = android.content.res.ColorStateList.valueOf(rankColor)
                progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#333333"))
            }

            wrapper.addView(row)
            wrapper.addView(progressBar)
            topDomainsContainer.addView(wrapper)

            // Animer la barre
            val targetProgress = (domainCount.count * 100 / maxCount.coerceAtLeast(1))
            animateProgress(progressBar, targetProgress, delay = index * 80L)
        }
    }

    // =========================================================================
    // ANIMATIONS
    // =========================================================================

    private fun animateCount(tv: TextView, target: Int, colorRes: Int, duration: Long = 600) {
        val animator = ValueAnimator.ofInt(0, target)
        animator.duration = duration
        animator.interpolator = DecelerateInterpolator()
        animator.addUpdateListener {
            tv.text = formatNumber(it.animatedValue as Int)
        }
        animator.start()
    }

    private fun animateProgress(bar: ProgressBar, target: Int, delay: Long = 0) {
        val animator = ValueAnimator.ofInt(0, target)
        animator.duration = 600
        animator.startDelay = delay
        animator.interpolator = DecelerateInterpolator()
        animator.addUpdateListener { bar.progress = it.animatedValue as Int }
        animator.start()
    }

    // =========================================================================
    // HELPERS
    // =========================================================================

    private fun showNoData() {
        tvNoData.visibility = View.VISIBLE
        listOf(tvTotalBlocked, tvAdsCount, tvTrackersCount, tvMalwareCount, tvOtherCount)
            .forEach { it.text = "0" }
        listOf(progressAds, progressTrackers, progressMalware, progressOther)
            .forEach { it.progress = 0 }
        chartContainer.removeAllViews()
        chartHourContainer.removeAllViews()
        topDomainsContainer.removeAllViews()
        donutChart?.setData(emptyList(), 0)
    }

    private fun emptyLabel(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(Color.parseColor("#888888"))
        gravity   = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.MATCH_PARENT
        )
    }

    private fun formatNumber(number: Int): String = when {
        number >= 1_000_000 -> String.format("%.1fM", number / 1_000_000.0)
        number >= 1_000     -> String.format("%.1fK", number / 1_000.0)
        else                -> number.toString()
    }

    private fun interpolateColor(start: Int, end: Int, fraction: Float): Int {
        val f = fraction.coerceIn(0f, 1f)
        val a = (Color.alpha(start) + f * (Color.alpha(end) - Color.alpha(start))).toInt()
        val r = (Color.red(start)   + f * (Color.red(end)   - Color.red(start))).toInt()
        val g = (Color.green(start) + f * (Color.green(end) - Color.green(start))).toInt()
        val b = (Color.blue(start)  + f * (Color.blue(end)  - Color.blue(start))).toInt()
        return Color.argb(a, r, g, b)
    }

    private fun Int.dpToPx(): Int = (this * resources.displayMetrics.density).toInt()
}

// =============================================================================
// VUE : BARRE ARRONDIE
// =============================================================================

class RoundedBarView @JvmOverloads constructor(
    context: Context,
    private val barColor: Int = Color.parseColor("#4CAF50"),
    private val targetHeight: Int = 0,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = barColor }

    override fun onDraw(canvas: Canvas) {
        val rect = RectF(0f, 0f, width.toFloat(), height.toFloat())
        val radius = width / 2f
        canvas.drawRoundRect(rect, radius, radius, paint)
    }
}

// =============================================================================
// VUE : DONUT CHART
// =============================================================================

data class DonutSegment(val label: String, val value: Int, val color: Int)

class DonutChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var segments: List<DonutSegment> = emptyList()
    private var total: Int = 0
    private var animatedSweep = 0f

    private val paintArc  = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val paintText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color    = Color.WHITE
        textSize = 32f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val paintSub = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color    = Color.parseColor("#888888")
        textSize = 22f
        textAlign = Paint.Align.CENTER
    }
    private val paintLegend = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 24f }
    private val paintDot    = Paint(Paint.ANTI_ALIAS_FLAG)

    fun setData(segs: List<DonutSegment>, tot: Int) {
        segments = segs.filter { it.value > 0 }
        total    = tot
        // Animation de dessin
        val animator = ValueAnimator.ofFloat(0f, 360f)
        animator.duration = 800
        animator.interpolator = DecelerateInterpolator()
        animator.addUpdateListener {
            animatedSweep = it.animatedValue as Float
            invalidate()
        }
        animator.start()
    }

    override fun onDraw(canvas: Canvas) {
        if (segments.isEmpty() || total == 0) return

        val cx        = width / 2f
        val cy        = height / 2f
        val radius    = (minOf(cx, cy) * 0.72f)
        val thickness = radius * 0.38f
        val strokeW   = thickness
        val rectF     = RectF(cx - radius, cy - radius, cx + radius, cy + radius)

        paintArc.strokeWidth = strokeW
        paintArc.strokeCap   = Paint.Cap.BUTT

        // Fond gris
        paintArc.color = Color.parseColor("#2A2A2A")
        paintArc.style = Paint.Style.STROKE
        canvas.drawCircle(cx, cy, radius, paintArc)

        // Segments
        var startAngle = -90f
        segments.forEach { seg ->
            val sweep = (seg.value.toFloat() / total * 360f).coerceAtMost(animatedSweep - (startAngle + 90f))
            if (sweep <= 0f) return@forEach
            paintArc.color = seg.color
            canvas.drawArc(rectF, startAngle, sweep.coerceAtMost(seg.value.toFloat() / total * 360f), false, paintArc)
            startAngle += seg.value.toFloat() / total * 360f
        }

        // Texte central
        val totalText = formatNumber(total)
        canvas.drawText(totalText, cx, cy + 10f, paintText)
        canvas.drawText("bloqués", cx, cy + 38f, paintSub)

        // Légende sous le donut
        val legendY   = cy + radius + strokeW / 2 + 36f
        val itemWidth = width / segments.size.coerceAtLeast(1).toFloat()

        segments.forEachIndexed { i, seg ->
            val lx = i * itemWidth + itemWidth / 2
            // Point coloré
            paintDot.color = seg.color
            canvas.drawCircle(lx - 24f, legendY, 8f, paintDot)
            // Label
            paintLegend.color = Color.parseColor("#CCCCCC")
            canvas.drawText(seg.label, lx + 10f, legendY + 8f, paintLegend)
        }
    }

    private fun formatNumber(n: Int): String = when {
        n >= 1_000_000 -> String.format("%.1fM", n / 1_000_000.0)
        n >= 1_000     -> String.format("%.1fK", n / 1_000.0)
        else           -> n.toString()
    }
}

// Data classes
enum class StatsPeriod { TODAY, WEEK, MONTH, ALL_TIME }

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