package com.example.smsreader

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.io.BufferedReader
import java.io.InputStreamReader
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.absoluteValue

class MonthDetailActivity : ComponentActivity() {

    companion object {
        const val EXTRA_CSV_URI = "extra_csv_uri"
        const val EXTRA_YEAR_MONTH = "extra_year_month" // format YYYY-MM
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val uriStr = intent.getStringExtra(EXTRA_CSV_URI)
        val ymStr = intent.getStringExtra(EXTRA_YEAR_MONTH)

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    if (uriStr.isNullOrBlank() || ymStr.isNullOrBlank()) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Error", style = MaterialTheme.typography.titleLarge)
                            Spacer(Modifier.height(8.dp))
                            Text("Missing CSV or month.")
                        }
                    } else {
                        MonthDetailScreen(csvUri = Uri.parse(uriStr), yearMonth = YearMonth.parse(ymStr))
                    }
                }
            }
        }
    }

    private data class TxnLite(
        val dateTime: LocalDateTime,
        val amount: Double,
        val label: String
    )

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun MonthDetailScreen(csvUri: Uri, yearMonth: YearMonth) {
        val context = LocalContext.current
        var isLoading by remember { mutableStateOf(true) }
        var error by remember { mutableStateOf<String?>(null) }
        var total by remember { mutableStateOf(0.0) }
        var byLabel by remember { mutableStateOf<Map<String, Double>>(emptyMap()) }
        var txns by remember { mutableStateOf<List<TxnLite>>(emptyList()) }

        LaunchedEffect(csvUri, yearMonth) {
            isLoading = true
            error = null
            try {
                val dtf = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm", Locale.getDefault())

                val rows = readCsvForDetails(context, csvUri)
                val filtered = rows.mapNotNull { row ->
                    val dateStr = row["Date"] ?: return@mapNotNull null
                    val amountStr = row["Amount"] ?: return@mapNotNull null
                    val labelRaw = (row["Label"] ?: "Unknown").trim()

                    // Spend-only rule: exclude Personal-income and NON-PAYMENT
                    val labelNorm = labelRaw.lowercase(Locale.getDefault())
                    if (labelNorm == "personal-income" || labelNorm == "non-payment") return@mapNotNull null

                    val amount = amountStr.toDoubleOrNull() ?: return@mapNotNull null
                    val dt = runCatching { LocalDateTime.parse(dateStr.trim(), dtf) }.getOrNull() ?: return@mapNotNull null

                    if (YearMonth.from(dt) != yearMonth) return@mapNotNull null

                    TxnLite(dateTime = dt, amount = amount, label = labelRaw.ifBlank { "Unknown" })
                }

                val monthTotal = filtered.sumOf { it.amount }
                val labelTotals = filtered.groupBy { it.label }.mapValues { (_, list) -> list.sumOf { it.amount } }

                total = monthTotal
                byLabel = labelTotals
                txns = filtered.sortedByDescending { it.dateTime }
            } catch (e: Exception) {
                error = e.message ?: "Failed to load month details"
            } finally {
                isLoading = false
            }
        }

        val labelIndexMap = remember(byLabel) {
            byLabel
                .toList()
                .sortedByDescending { it.second }
                .map { it.first }
                .withIndex()
                .associate { it.value to it.index }
        }

        Scaffold(
            topBar = {
                TopAppBar(title = { Text("Month breakdown") })
            }
        ) { padding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                when {
                    isLoading -> {
                        item {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                CircularProgressIndicator()
                                Text("Loading...")
                            }
                        }
                    }
                    error != null -> {
                        item {
                            Text("Error: $error", color = MaterialTheme.colorScheme.error)
                        }
                    }
                    else -> {
                        item { MonthSummaryCard(yearMonth = yearMonth, total = total) }
                        item { SpendingPieCard(byLabel = byLabel) }
                        item {
                            Text(
                                text = "Transactions",
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                        items(txns) { t ->
                            TransactionCard(t, labelIndexMap)
                        }
                        if (txns.isEmpty()) {
                            item { Text("No transactions found for this month.") }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun MonthSummaryCard(yearMonth: YearMonth, total: Double) {
        fun monthName(ym: YearMonth): String {
            val m = ym.month.name.lowercase().replaceFirstChar { it.titlecase() }
            return "$m ${ym.year}"
        }
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(monthName(yearMonth), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Total expenses: ₹" + String.format(Locale.getDefault(), "%.2f", total),
                    style = MaterialTheme.typography.headlineSmall
                )
            }
        }
    }

    // Golden-angle based hue distribution for distinct colors
    private fun colorForLabel(label: String, index: Int): Color {
        val goldenAngle = 137.508f
        val hue = (index * goldenAngle) % 360f
        return Color.hsv(
            hue = hue,
            saturation = 0.65f,
            value = 0.9f
        )
    }

    @Composable
    private fun SpendingPieCard(byLabel: Map<String, Double>) {
        val entries = byLabel
            .toList()
            .sortedByDescending { it.second }
            .filter { it.second > 0 }

        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("Split by category", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))

                if (entries.isEmpty()) {
                    Text("No spending data for this month.")
                    return@Column
                }

                val total = entries.sumOf { it.second }.coerceAtLeast(1e-9)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Canvas(
                        modifier = Modifier
                            .size(140.dp)
                            .fillMaxHeight()
                            .align(Alignment.CenterVertically)
                            .padding(10.dp)
                    ) {
                        val strokeWidth = size.minDimension * 0.28f
                        var startAngle = -90f

                        entries.forEachIndexed { idx, (label, value) ->
                            val sweep = (value / total * 360.0).toFloat()
                            drawArc(
                                color = colorForLabel(label, idx),
                                startAngle = startAngle,
                                sweepAngle = sweep,
                                useCenter = false,
                                style = Stroke(width = strokeWidth)
                            )
                            startAngle += sweep
                        }
                    }

                    // Legend
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        entries.take(8).forEach { (label, value) ->
                            val c = colorForLabel(label, entries.indexOfFirst { it.first == label })
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .padding(0.dp)
                                ) {
                                    // simple colored square
                                }
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .padding(0.dp)
                                        .background(c)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = label,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = "₹" + String.format(Locale.getDefault(), "%.0f", value),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                        if (entries.size > 8) {
                            Text(
                                text = "+${entries.size - 8} more",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun TransactionsListCard(txns: List<TxnLite>) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("Transactions", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))

                if (txns.isEmpty()) {
                    Text("No transactions found for this month.")
                    return@Column
                }

                val palette = listOf(
                    Color(0xFF1E88E5), Color(0xFFD81B60), Color(0xFF43A047), Color(0xFFF4511E),
                    Color(0xFF8E24AA), Color(0xFF3949AB), Color(0xFF00897B), Color(0xFFFDD835)
                )
                fun colorFor(label: String): Color {
                    val idx = (label.hashCode().absoluteValue) % palette.size
                    return palette[idx]
                }

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 340.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(txns) { t ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .background(colorFor(t.label))
                            )
                            Spacer(Modifier.width(10.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = t.label,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = t.dateTime.toString().replace('T', ' '),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                            }

                            Text(
                                text = "₹" + String.format(Locale.getDefault(), "%.2f", t.amount),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }
        }
    }

    // CSV helpers (duplicated here so this activity is self-contained)
    private fun readCsvForDetails(context: android.content.Context, uri: Uri): List<Map<String, String>> {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val reader = BufferedReader(InputStreamReader(input))
            val lines = reader.readLines()
            if (lines.isEmpty()) return emptyList()

            val headers = parseCsvLineForDetails(lines.first())
            if (headers.isEmpty()) return emptyList()

            return lines.drop(1).mapNotNull { line ->
                if (line.isBlank()) return@mapNotNull null
                val cols = parseCsvLineForDetails(line)
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

    private fun parseCsvLineForDetails(line: String): List<String> {
        val result = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0

        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' -> {
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
    private fun TransactionCard(t: TxnLite, labelIndexMap: Map<String, Int>) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(
                            colorForLabel(
                                t.label,
                                labelIndexMap[t.label] ?: 0
                            )
                        )
                )
                Spacer(Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = t.label,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = t.dateTime.toString().replace('T', ' '),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }

                Spacer(Modifier.width(12.dp))

                Text(
                    text = "₹" + String.format(Locale.getDefault(), "%.2f", t.amount),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}