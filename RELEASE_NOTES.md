# Release Notes — v1.0.0

First public release of **DeepSeek Platform Widget Enhanced**, an enhanced fork of
MCwasd/deepseek-platform-widget.

---

## Highlights

### Detailed Usage widget (new)

A dense, dark home-screen dashboard for the DeepSeek open platform, built around the
*API key × time range* question instead of a single account-wide number:

- account block: current balance and lifetime cost (lifetime cost is hidden behind an eye icon
  by default)
- official **COST · REQUESTS · TOKENS** for the current filter
- a **CACHE / BILLING** breakdown: cache-hit, cache-miss and output tokens with their share of
  input tokens, plus `≈` estimated cost per component and `≈` estimated cache savings

### Full Dashboard widget (new)

The same information set at a much larger type scale, intended to fill a whole home-screen page.
Readability first: bigger type, looser section spacing, still no charts.

### API Key filtering

Cycle `All Keys → each key on your account` by tapping the API-Key area. Key names come from the
API — nothing is hardcoded. If the selected key no longer exists, the widget falls back to
All Keys instead of breaking.

### Time filtering

Tap the Time area to cycle `Today → Last 7 days → Last 30 days`, defaulting to Last 7 days.
`Today` means the current local calendar day, because usage is bucketed per day.

### Cache / Billing breakdown

Cache-hit, cache-miss and output token counts (official), their proportion of input tokens, and
`≈` estimated cost per component.

### Estimated cache savings

`≈ EST. CACHE SAVINGS` answers: *if the cache-hit tokens had been charged at the cache-miss rate
instead, how much more would they have cost?* Computed per model, using the app's
peak/off-peak price table.

### Dark-only UI

The Detailed Usage and Full Dashboard widgets are dark-only by design — they stay dark whatever
the system theme is. No light mode, no dynamic colour.

### RemoteViews layout guard

`RemoteViews` can only inflate classes annotated `@RemoteView`. A single stray `<View>` in a
widget layout makes the launcher reject the whole widget with *"An error occurred when loading
widget"* — while the APK still builds and every other test stays green.

This release adds a JVM guard test that reads the `@RemoteView` rule **directly from the
platform classes** and validates every `widget*` layout, so this class of bug cannot come back
silently.

### 36 JVM tests

```bash
./gradlew testDebugUnitTest        # 36 tests, 0 failures
```

Covering joint Time × API-Key filtering, per-key and per-model aggregation, cache-hit rate
(including the zero-input case), the `≈` estimation and cache-savings maths, unknown-model and
missing-pricing fallbacks, serialization round trips, and layout safety.

### Android package renamed

The application id is now **`dev.randolf.deepseekwidget`**, so this fork can be installed
**side by side** with the original `com.tiramisu.deepseekwidget` app.

The Kotlin/manifest package is intentionally left as `com.tiramisu.deepseekwidget` to stay close
to upstream.

---

## ⚠️ About the estimated values — please read

The DeepSeek cost API returns a single official total per *(API key × model × day)*. It does
**not** provide an official breakdown into cache-hit, cache-miss and output cost.

As a result:

- **Cache Hit / Cache Miss / Output estimated costs** and **Estimated Cache Savings** are
  **local estimates**, calculated from DeepSeek's published price list.
- They are **not** an official DeepSeek bill breakdown, and the components are **not** guaranteed
  to sum to the official total.
- Estimated values are always prefixed with **`≈`** in the UI, and `COST` / `BALANCE` /
  `TOTAL COST` are always official API values.

Estimates are computed per model. Because usage is bucketed per day, the peak/off-peak mix
within a day cannot be determined exactly, so a day is priced as a duration-weighted blend of
the peak and off-peak rates — expect a modest deviation from the official total. Models missing
from the price table are still counted in the token totals but skipped by the estimate.

---

## Notes

- Requires Android 8.0 (API 26) or newer.
- Login uses your DeepSeek open-platform **account** (email + password); API keys are not
  accepted by these endpoints.
- Credentials and token are stored locally in `EncryptedSharedPreferences` (AES-256-GCM); all
  traffic goes directly to `platform.deepseek.com`.
- Release signing material is not committed. See the *Release builds* section of the
  [README](README.md).

## License

MIT. Original work © Tiramisu-wzh. See LICENSE and NOTICE.
