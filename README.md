# DeepSeek Platform Widget Enhanced

Home-screen widgets for the **DeepSeek open platform** — balance, spend, and cache/billing
analytics at a glance, filterable by **API key** and **time range**.

> **Unofficial / not affiliated with DeepSeek.** This is a community-built client that talks to
> the DeepSeek open platform using *your own* account. "DeepSeek" appears only to describe what
> the app connects to. Use at your own discretion.

---

## Attribution

This project is an **enhanced fork** of **MCwasd/deepseek-platform-widget**.

- Original project: <https://github.com/MCwasd/deepseek-platform-widget>

Original project and this derivative are distributed under the **MIT License**. The original
copyright notice is preserved unchanged in [`LICENSE`](LICENSE); a description of what this fork
changes lives in [`NOTICE`](NOTICE).

---

## The three widgets

| Widget | Recommended size | Description |
|---|---|---|
| **Compact** | ~4×2 | The upstream three-panel summary: balance · today · this month, with a per-model detail row |
| **Detailed Usage** | 4×5 | Dense dark dashboard: account block, Time / API-Key filters, official cost·requests·tokens, and a CACHE / BILLING breakdown |
| **Full Dashboard** | 4×6, resizable to fill a page | The same information as Detailed Usage at a much larger type scale, for reading at arm's length |

All three can be placed at the same time. Each widget keeps its **own** Time range, API-Key
selection and Total-Cost visibility — they never affect each other.

---

## What it shows

**Account level** — independent of any filter

- **BALANCE** — topped-up balance
- **TOTAL COST** — lifetime spend. **Hidden by default**; tap the eye icon to reveal or hide it.

**Filtered by Time + API Key**

- **COST** · **REQUESTS** · **TOKENS** — official API values
- **TIME** — tap to cycle `Today → Last 7 days → Last 30 days`
  (`Today` is the current local calendar day, because the API buckets usage per day.)
- **API KEY** — tap to cycle `All Keys → each key on your account`. Key names come from the API;
  none are hardcoded. If the selected key disappears, the widget falls back to All Keys.

**CACHE / BILLING**

- **CACHE HIT** · **CACHE MISS** · **OUTPUT** token counts — official
- cache hit / miss share of input tokens
- `≈` estimated cost per component
- `≈` **EST. CACHE SAVINGS**

---

## About the `≈` values — please read

DeepSeek's usage API returns token counts already split into cache-hit, cache-miss and output.
The **cost** API, however, returns only a single official total per *(API key × model × day)* —
it does **not** break that total down by component.

Therefore:

- Anything labelled `BALANCE`, `TOTAL COST` or `COST` is an **official API value**.
- Anything prefixed with **`≈`** is a **local estimate** derived from DeepSeek's published price
  list. It is *not* an official bill breakdown, and the components will not necessarily add up
  to the official total.

**EST. CACHE SAVINGS** answers a counterfactual question: *if the cache-hit tokens had instead
been charged at the cache-miss rate, how much more would they have cost?* It is an intuition
aid, not a refund figure.

Estimates are computed **per model**, reusing the app's peak/off-peak price table, and days
before peak/valley pricing took effect use the legacy flat price. Because usage buckets are
daily, the peak/off-peak mix *within* a day cannot be known exactly, so a day is charged as a
duration-weighted blend — expect a modest deviation from the official total. Models that are not
in the price table are still counted in the token totals, but are skipped by the estimate.

---

## Dark-only by design

The **Detailed Usage** and **Full Dashboard** widgets are dark-only: they stay dark regardless
of the system theme. There is no light mode, no "follow system" and no dynamic colour.

---

## Login

The widgets read your usage through a normal **DeepSeek open-platform account login** (email +
password) — not an API key, which the platform does not accept for these endpoints.

- Credentials and the session token are stored locally in `EncryptedSharedPreferences`
  (AES-256-GCM).
- All requests go directly to `platform.deepseek.com`. There is no intermediate server, no
  analytics and no telemetry.

---

## Install

1. Download the APK from the **Releases** page of this repository.
2. Install it (requires Android 8.0 / API 26 or newer).
3. Long-press the home screen → **Widgets** → choose **Compact**, **Detailed Usage** or
   **Full Dashboard**.
4. Enter your DeepSeek platform email and password in the configuration screen. The widget then
   loads your data and refreshes automatically every 30 minutes.

---

## Build from source

Requires **JDK 17** and the **Android SDK** (platform 34, build-tools 34.0.0).

```bash
git clone https://github.com/rando1ph/deepseek-platform-widget-enhanced.git
cd deepseek-platform-widget-enhanced
./gradlew assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
```

Point the build at your SDK through `ANDROID_HOME`, or create `local.properties`:

```properties
sdk.dir=/absolute/path/to/Android/sdk
```

`local.properties` is git-ignored.

### Release builds

Release signing material is **never committed**, and there is **no fallback**: the build will
not silently sign a release with the debug key. Create `keystore.properties` at the project
root:

```properties
storeFile=/absolute/path/to/release.jks
storePassword=****
keyAlias=****
keyPassword=****
```

Then:

```bash
./gradlew assembleRelease
```

If `keystore.properties` is missing (or the keystore file cannot be found), `assembleRelease`
fails with an explanatory message. `assembleDebug` is unaffected.

`keystore.properties`, `*.jks` and `*.keystore` are all git-ignored — see [`.gitignore`](.gitignore).

---

## Package identity

This fork ships as **`dev.randolf.deepseekwidget`**, so it installs **alongside** the original
`com.tiramisu.deepseekwidget` app without conflict.

The Kotlin/manifest package is deliberately left as `com.tiramisu.deepseekwidget` so the fork
stays close to upstream and easy to rebase.

---

## Tests

```bash
./gradlew testDebugUnitTest
```

The JVM suite covers the pure logic: joint Time × API-Key filtering, per-key and per-model
aggregation, cache-hit rate, the `≈` estimation and cache-savings maths, serialization round
trips, and the `bucket` field's tolerance of both string and numeric values.

It also contains a **RemoteViews layout guard**. `RemoteViews` can only inflate classes
annotated `@RemoteView`, so a single stray `<View>` in a widget layout makes the launcher fail
with *"An error occurred when loading widget"* while the APK still builds and every other test
stays green. The guard reads the `@RemoteView` rule directly from the platform classes and
checks every `widget*` layout in the module.

---

## Data sources

| Endpoint | Used for |
|---|---|
| `POST /auth-api/v0/users/login` | account login → bearer token |
| `GET /api/v0/users/get_user_summary` | balance + lifetime cost |
| `GET /api/v0/usage/by_api_key/amount` | per-key / per-model / per-day token counts |
| `GET /api/v0/usage/by_api_key/cost` | per-key / per-model / per-day official cost |

Response field names and pitfalls are documented in
[`deepseek-api-reference.md`](deepseek-api-reference.md).

---

## License

**MIT** — see [`LICENSE`](LICENSE).

- Original work © Tiramisu-wzh (and contributors to the upstream project).
- This fork's changes are described in [`NOTICE`](NOTICE).
