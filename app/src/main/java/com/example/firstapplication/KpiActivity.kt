

package com.example.smsreader

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import kotlin.math.pow
import android.content.Intent
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import kotlin.math.absoluteValue
import androidx.compose.foundation.background

class KpiActivity : ComponentActivity() {

    companion object {
        const val EXTRA_CSV_URI = "extra_csv_uri"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val uriStr = intent.getStringExtra(EXTRA_CSV_URI)

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    if (uriStr.isNullOrBlank()) {
                        ErrorScreen("No CSV selected.")
                    } else {
                        KpiScreen(csvUri = Uri.parse(uriStr))
                    }
                }
            }
        }
    }

    private data class MonthPoint(
        val yearMonth: YearMonth,
        val total: Double
    )

    private enum class Timeframe(val label: String, val months: Int?) {
        LAST_3("Last 3 Months", 3),
        LAST_6("Last 6 Months", 6),
        LAST_YEAR("Last Year", 12),
        LIFETIME("Lifetime", null)
    }

    @Composable
    private fun ErrorScreen(msg: String) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Text("Error", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text(msg)
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun KpiScreen(csvUri: Uri) {
        var isLoading by remember { mutableStateOf(true) }
        var error by remember { mutableStateOf<String?>(null) }
        var kpis by remember { mutableStateOf<KpiResult?>(null) }

        LaunchedEffect(csvUri) {
            isLoading = true
            error = null
            kpis = null
            try {
                val result = computeKpisFromCsv(csvUri)
                kpis = result
            } catch (e: Exception) {
                error = e.message ?: "Failed to read CSV"
            } finally {
                isLoading = false
            }
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Spending KPIs") }
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                when {
                    isLoading -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            CircularProgressIndicator()
                            Text("Computing KPIs...")
                        }
                    }
                    error != null -> {
                        Text("Error: $error", color = MaterialTheme.colorScheme.error)
                    }
                    kpis != null -> {
                        KpiCard(
                            title = "Max spent in a month",
                            value = "₹${kpis!!.maxMonthlySpendAmount} (${kpis!!.maxMonthlySpendMonth})",
                            icon = Icons.Filled.BarChart
                        )

                        KpiCard(
                            title = "Least spending month",
                            value = "${kpis!!.minMonthlySpendMonth} (₹${kpis!!.minMonthlySpendAmount})",
                            icon = Icons.Filled.CalendarMonth
                        )

                        KpiCard(
                            title = "Top spending category",
                            value = "${kpis!!.topCategoryName} (₹${kpis!!.topCategoryAmount})",
                            icon = Icons.Filled.Category
                        )

                        MonthlySpendLineChartCard(
                            csvUri = csvUri,
                            points = kpis!!.monthlySeries
                        )
                    }
                }
            }
        }
    }

    @Composable

    private fun KpiCard(
        title: String,
        value: String,
        icon: ImageVector
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Row(
                modifier = Modifier
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp)
                )

                Spacer(modifier = Modifier.width(16.dp))

                Column {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = value,
                        style = MaterialTheme.typography.headlineSmall
                    )
                }
            }
        }
    }

    // ---------------- KPI logic ----------------

    private data class Txn(
        val sender: String,
        val dateTime: LocalDateTime,
        val amount: Double,
        val label: String
    )

    private data class KpiResult(
        val maxMonthlySpendMonth: String,
        val maxMonthlySpendAmount: String,
        val minMonthlySpendMonth: String,
        val minMonthlySpendAmount: String,
        val topCategoryName: String,
        val topCategoryAmount: String,
        val monthlySeries: List<MonthPoint>
    )

    private suspend fun computeKpisFromCsv(uri: Uri): KpiResult = withContext(Dispatchers.IO) {
        // Your sample uses: "04-01-2026 20:52"
        val dtf = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm", Locale.getDefault())

        val txns = readCsv(uri).mapNotNull { row ->
            // Expected headers: Sender, Date, Amount, Status, Label, Message
            val sender = row["Sender"] ?: return@mapNotNull null
            val dateStr = row["Date"] ?: return@mapNotNull null
            val amountStr = row["Amount"] ?: return@mapNotNull null
            val labelRaw = row["Label"] ?: "Unknown"
            val label = labelRaw.trim()

            // Count as spend only if Label is NOT Personal-income or NON-PAYMENT
            val labelNorm = label.lowercase(Locale.getDefault())
            if (labelNorm == "personal-income" || labelNorm == "non-payment") {
                return@mapNotNull null
            }

            val amount = amountStr.toDoubleOrNull() ?: return@mapNotNull null
            val dt = runCatching { LocalDateTime.parse(dateStr.trim(), dtf) }.getOrNull()
                ?: return@mapNotNull null

            Txn(sender = sender, dateTime = dt, amount = amount, label = label)
        }

        if (txns.isEmpty()) throw IllegalStateException("No valid rows found in CSV.")

        // Month totals
        val monthTotals = txns
            .groupBy { YearMonth.from(it.dateTime) }
            .mapValues { (_, list) -> list.sumOf { it.amount } }

        val maxMonth = monthTotals.maxByOrNull { it.value }
            ?: throw IllegalStateException("No month totals computed.")
        val minMonth = monthTotals.minByOrNull { it.value }
            ?: throw IllegalStateException("No month totals computed.")

        val monthlySeries = monthTotals
            .toList()
            .sortedBy { it.first }
            .map { (ym, total) -> MonthPoint(ym, total) }

        // Category totals (Label)
        val categoryTotals = txns
            .groupBy { it.label.ifBlank { "Unknown" } }
            .mapValues { (_, list) -> list.sumOf { it.amount } }

        val topCategory = categoryTotals.maxByOrNull { it.value }
            ?: throw IllegalStateException("No category totals computed.")

        fun formatMonth(ym: YearMonth): String =
            ym.month.name.lowercase().replaceFirstChar { it.titlecase() } + " " + ym.year

        fun money(x: Double): String = String.format(Locale.getDefault(), "%.2f", x)

        KpiResult(
            maxMonthlySpendMonth = formatMonth(maxMonth.key),
            maxMonthlySpendAmount = money(maxMonth.value),

            minMonthlySpendMonth = formatMonth(minMonth.key),
            minMonthlySpendAmount = money(minMonth.value),

            topCategoryName = topCategory.key,
            topCategoryAmount = money(topCategory.value),
            monthlySeries = monthlySeries
        )
    }

    /**
     * Minimal CSV reader that supports quoted values with commas.
     * Returns a list of maps: header -> cell.
     */
    private fun readCsv(uri: Uri): List<Map<String, String>> {
        contentResolver.openInputStream(uri)?.use { input ->
            val reader = BufferedReader(InputStreamReader(input))
            val lines = reader.readLines()
            if (lines.isEmpty()) return emptyList()

            val headers = parseCsvLine(lines.first())
            if (headers.isEmpty()) return emptyList()

            return lines.drop(1).mapNotNull { line ->
                if (line.isBlank()) return@mapNotNull null
                val cols = parseCsvLine(line)
                val map = mutableMapOf<String, String>()
                for (i in headers.indices) {
                    val key = headers[i]
                    val value = cols.getOrNull(i) ?: ""
                    map[key] = value
                }
                map
            }
        }
        return emptyList()
    }

    private fun parseCsvLine(line: String): List<String> {
        val result = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0

        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' -> {
                    // Handle escaped quotes ""
                    if (inQuotes && i + 1 < line.length && line[i + 1] == '"') {
                        sb.append('"')
                        i++
                    } else {
                        inQuotes = !inQuotes
                    }
                }
                c == ',' && !inQuotes -> {
                    result.add(sb.toString())
                    sb.clear()
                }
                else -> sb.append(c)
            }
            i++
        }
        result.add(sb.toString())
        return result
    }
    @Composable
    private fun MonthlySpendLineChartCard(csvUri: Uri, points: List<MonthPoint>) {
        if (points.isEmpty()) return
        // Full series (sorted)
        val allSorted = points.sortedBy { it.yearMonth }

        // Timeframe dropdown state
        var timeframe by remember { mutableStateOf(Timeframe.LAST_6) }
        var menuExpanded by remember { mutableStateOf(false) }

        // Filter series based on timeframe
        val sorted = remember(allSorted, timeframe) {
            val m = timeframe.months
            if (m == null) allSorted else allSorted.takeLast(m)
        }

        val values = sorted.map { it.total }
        if (sorted.isEmpty()) return

        val rawMin = values.minOrNull() ?: 0.0
        val rawMax = values.maxOrNull() ?: 0.0

        // "Nice" axis scaling (adds padding and rounds ticks)
        fun niceNum(range: Double, round: Boolean): Double {
            if (range <= 0.0) return 1.0
            val exponent = kotlin.math.floor(kotlin.math.log10(range))
            val fraction = range / 10.0.pow(exponent)
            val niceFraction = if (round) {
                when {
                    fraction < 1.5 -> 1.0
                    fraction < 3.0 -> 2.0
                    fraction < 7.0 -> 5.0
                    else -> 10.0
                }
            } else {
                when {
                    fraction <= 1.0 -> 1.0
                    fraction <= 2.0 -> 2.0
                    fraction <= 5.0 -> 5.0
                    else -> 10.0
                }
            }
            return niceFraction * 10.0.pow(exponent)
        }

        // Tick count: prefer 6 lines (0, 5k, 10k, 15k, 20k, 25k style) for readability
        val preferredTicks = 6
        val range = (rawMax - rawMin).coerceAtLeast(1.0)
        val niceRange = niceNum(range, round = false)
        val step = niceNum(niceRange / (preferredTicks - 1), round = true)
        val axisMin = kotlin.math.floor(rawMin / step) * step
        val axisMax = kotlin.math.ceil(rawMax / step) * step
        val axisSpan = (axisMax - axisMin).coerceAtLeast(step)

        fun formatMoneyCompact(v: Double): String {
            val abs = kotlin.math.abs(v)
            return when {
                abs >= 1000000 -> "₹" + String.format(Locale.getDefault(), "%.1fM", v / 1000000.0)
                abs >= 1000 -> "₹" + String.format(Locale.getDefault(), "%.0fk", v / 1000.0)
                else -> "₹" + String.format(Locale.getDefault(), "%.0f", v)
            }
        }

        fun formatMonthShort(ym: YearMonth): String {
            val m = ym.month.name.lowercase().replaceFirstChar { it.titlecase() }
            return m.take(3) + " " + ym.year
        }

        val context = LocalContext.current

        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Total spent over months",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Monthly totals",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )

                Spacer(Modifier.height(12.dp))

                // Timeframe dropdown
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Timeframe",
                        style = MaterialTheme.typography.bodyMedium
                    )

                    Box {
                        OutlinedButton(
                            onClick = { menuExpanded = true },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Text(timeframe.label)
                            Spacer(Modifier.width(6.dp))
                            Icon(
                                imageVector = Icons.Filled.ArrowDropDown,
                                contentDescription = null
                            )
                        }

                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false }
                        ) {
                            Timeframe.entries.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(option.label) },
                                    onClick = {
                                        timeframe = option
                                        menuExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                // Move MaterialTheme color reads outside Canvas lambda
                val outlineColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                val primaryColor = MaterialTheme.colorScheme.primary
                val labelArgb = MaterialTheme.colorScheme.onSurface.toArgb()

                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .pointerInput(sorted) {
                            detectTapGestures { tap ->
                                // Map tap to nearest plotted point
                                // We'll compute pts in the draw block and reuse the same math here
                                val w = size.width
                                val h = size.height

                                val leftPad = 56f
                                val rightPad = 12f
                                val topPad = 12f
                                val bottomPad = 16f

                                val chartW = (w - leftPad - rightPad).coerceAtLeast(1f)
                                val chartH = (h - topPad - bottomPad).coerceAtLeast(1f)

                                val n = sorted.size
                                if (n == 0) return@detectTapGestures

                                val stepX = if (n <= 1) 0f else chartW / (n - 1)
                                fun xFor(i: Int) = leftPad + i * stepX
                                fun yFor(v: Double): Float {
                                    val ratio = ((v - axisMin) / axisSpan).toFloat().coerceIn(0f, 1f)
                                    return topPad + (1f - ratio) * chartH
                                }

                                var bestI = -1
                                var bestD2 = Float.MAX_VALUE
                                for (i in 0 until n) {
                                    val px = xFor(i)
                                    val py = yFor(sorted[i].total)
                                    val dx = tap.x - px
                                    val dy = tap.y - py
                                    val d2 = dx * dx + dy * dy
                                    if (d2 < bestD2) {
                                        bestD2 = d2
                                        bestI = i
                                    }
                                }

                                // Only treat as a click if within a reasonable radius (~24dp)
                                val radiusPx = 48f
                                if (bestI >= 0 && bestD2 <= radiusPx * radiusPx) {
                                    val ym = sorted[bestI].yearMonth
                                    val intent = Intent(context, MonthDetailActivity::class.java).apply {
                                        putExtra(MonthDetailActivity.EXTRA_CSV_URI, csvUri.toString())
                                        putExtra(MonthDetailActivity.EXTRA_YEAR_MONTH, ym.toString()) // YearMonth.toString(): YYYY-MM
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(intent)
                                }
                            }
                        }
                ) {
                    val w = size.width
                    val h = size.height

                    val leftPad = 56f
                    val rightPad = 12f
                    val topPad = 12f
                    val bottomPad = 16f

                    val chartW = (w - leftPad - rightPad).coerceAtLeast(1f)
                    val chartH = (h - topPad - bottomPad).coerceAtLeast(1f)

                    val n = sorted.size
                    val stepX = if (n <= 1) 0f else chartW / (n - 1)

                    fun xFor(i: Int) = leftPad + i * stepX
                    fun yFor(v: Double): Float {
                        val ratio = ((v - axisMin) / axisSpan).toFloat().coerceIn(0f, 1f)
                        return topPad + (1f - ratio) * chartH
                    }
                    val pts = (0 until n).map { i ->
                        Offset(xFor(i), yFor(sorted[i].total))
                    }

                    // Baseline axis (subtle)
                    drawLine(
                        color = outlineColor,
                        start = Offset(leftPad, topPad + chartH),
                        end = Offset(leftPad + chartW, topPad + chartH),
                        strokeWidth = 2f
                    )

                    val path = Path()
                    for (i in 0 until n) {
                        val p = pts[i]
                        if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                    }

                    // Line
                    drawPath(
                        path = path,
                        color = primaryColor,
                        style = Stroke(width = 5f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                    )
                    // Y-axis gridlines + labels
                    val axisPaint = android.graphics.Paint().apply {
                        isAntiAlias = true
                        textSize = 24f
                        color = labelArgb
                        typeface = android.graphics.Typeface.DEFAULT
                    }

                    val tickCount = kotlin.math.min(
                        preferredTicks,
                        ((axisSpan / step).toInt() + 1).coerceAtLeast(2)
                    )

                    for (t in 0 until tickCount) {
                        val v = if (t == tickCount - 1) axisMax else axisMin + t * step
                        val y = yFor(v)

                        // Gridline
                        drawLine(
                            color = outlineColor.copy(alpha = 0.25f),
                            start = Offset(leftPad, y),
                            end = Offset(leftPad + chartW, y),
                            strokeWidth = 1.5f
                        )

                        // Tick label (left of chart)
                        val label = formatMoneyCompact(v)
                        drawIntoCanvas { canvas ->
                            val textWidth = axisPaint.measureText(label)
                            canvas.nativeCanvas.drawText(
                                label,
                                (leftPad - 10f - textWidth).coerceAtLeast(0f),
                                (y - 4f).coerceIn(0f, size.height),
                                axisPaint
                            )
                        }
                    }

                    // Points (no amount text next to dots)
                    for (i in 0 until n) {
                        val p = pts[i]
                        drawCircle(
                            color = primaryColor,
                            radius = 8f,
                            center = p
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))

                // Simple range labels (avoids cluttering axis)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = formatMonthShort(sorted.first().yearMonth),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Text(
                        text = formatMonthShort(sorted.last().yearMonth),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }
        }
    }

}

