# BlazeUp SMS Transfer

An Android app that reads bank SMS messages from your device, exports them to CSV, and provides spending analytics with interactive visualizations.

## Features

### SMS Reading & Export
- **Read bank SMS** from your device inbox with permission-based access
- **Filter by sender** — pre-configured for major Indian banks (HDFC, ICICI, AXIS, SBI, Kotak, Federal, IndusInd, Canara) with the ability to add custom providers
- **Date range selection** — pick start/end dates using Material 3 date pickers (defaults to last 30 days)
- **Export to CSV** — generates a CSV file (`Sender, Date, Message`) and shares it via Android's share sheet

### Spending KPIs Dashboard
- Upload a CSV file (with columns: `Sender, Date, Amount, Status, Label, Message`) to view spending analytics
- **Key metrics displayed:**
  - Maximum spending month and amount
  - Minimum spending month and amount
  - Top spending category
- **Interactive line chart** showing monthly spending totals with configurable timeframes (3 months, 6 months, 1 year, lifetime)
- Tap a data point on the chart to drill down into that month's details

### Monthly Breakdown View
- **Total expenses** for the selected month
- **Donut chart** showing spending split by category with a color-coded legend
- **Transaction list** with individual amounts, labels, and dates

## Tech Stack

- **Language:** Kotlin
- **UI:** Jetpack Compose with Material 3
- **Min SDK:** 24 (Android 7.0)
- **Target SDK:** 34 (Android 14)
- **Build:** Gradle with Kotlin DSL and version catalogs

## Project Structure

```
app/src/main/java/com/example/firstapplication/
├── MainActivity.kt          # SMS reader, filtering, CSV export
├── KpiActivity.kt           # KPI dashboard, line chart, CSV parsing
├── MonthDetailActivity.kt   # Monthly breakdown, pie chart, transaction list
└── ui/theme/                 # Material 3 theme (Color, Theme, Type)
```

## Permissions

| Permission | Purpose |
|---|---|
| `READ_SMS` | Read bank SMS messages from the device inbox |
| `READ_PHONE_STATE` | Support telephony features |

## Getting Started

1. Clone the repository
2. Open in Android Studio
3. Build and run on a device or emulator
4. Grant SMS permission when prompted
5. Select bank senders, pick a date range, and tap **Load Messages**
6. Use **Export to CSV** to save messages, or **Upload CSV & View KPIs** to analyze a previously exported file

## CSV Format

The KPI dashboard expects a CSV with these headers:

```
Sender,Date,Amount,Status,Label,Message
```

- **Date format:** `dd-MM-yyyy HH:mm`
- **Label:** spending category (rows labeled `Personal-income` or `NON-PAYMENT` are excluded from spend calculations)
