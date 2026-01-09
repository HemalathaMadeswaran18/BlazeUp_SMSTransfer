

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke

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

                        MonthlySpendLineChartCard(points = kpis!!.monthlySeries)
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
            val label = row["Label"] ?: "Unknown"

            val amount = amountStr.toDoubleOrNull() ?: return@mapNotNull null
            val dt = runCatching { LocalDateTime.parse(dateStr.trim(), dtf) }.getOrNull()
                ?: return@mapNotNull null

            // If you want ONLY "spent", you can filter out credits here by checking Message/Label/Status.
            // Example: if (!(row["Message"]?.contains("debited", ignoreCase = true) == true)) return@mapNotNull null

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
    private fun MonthlySpendLineChartCard(points: List<MonthPoint>) {
        if (points.isEmpty()) return

        val sorted = points.sortedBy { it.yearMonth }
        val values = sorted.map { it.total }
        val maxY = (values.maxOrNull() ?: 0.0).coerceAtLeast(1.0)

        fun formatMonthShort(ym: YearMonth): String {
            val m = ym.month.name.lowercase().replaceFirstChar { it.titlecase() }
            return m.take(3) + " " + ym.year
        }

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

                // Move MaterialTheme color reads outside Canvas lambda
                val outlineColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                val primaryColor = MaterialTheme.colorScheme.primary

                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                ) {
                    val w = size.width
                    val h = size.height

                    val leftPad = 12f
                    val rightPad = 12f
                    val topPad = 12f
                    val bottomPad = 16f

                    val chartW = (w - leftPad - rightPad).coerceAtLeast(1f)
                    val chartH = (h - topPad - bottomPad).coerceAtLeast(1f)

                    val n = sorted.size
                    val stepX = if (n <= 1) 0f else chartW / (n - 1)

                    fun xFor(i: Int) = leftPad + i * stepX
                    fun yFor(v: Double): Float {
                        val ratio = (v / maxY).toFloat().coerceIn(0f, 1f)
                        return topPad + (1f - ratio) * chartH
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
                        val x = xFor(i)
                        val y = yFor(sorted[i].total)
                        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }

                    // Line
                    drawPath(
                        path = path,
                        color = primaryColor,
                        style = Stroke(width = 4f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                    )

                    // Points
                    for (i in 0 until n) {
                        val x = xFor(i)
                        val y = yFor(sorted[i].total)
                        drawCircle(
                            color = primaryColor,
                            radius = 6f,
                            center = Offset(x, y)
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