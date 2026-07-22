# DeepSeek Dashboard Widget 📊

> Android 桌面小组件 — DeepSeek API 余额、用量、缓存命中率，一目了然

---

**English** · [中文](#中文)

---

## Features

| English | 中文 |
|---------|------|
| ✅ Account login (email+password) | ✅ DeepSeek 账号登录 |
| ✅ Balance / Today Cost / Month Cost | ✅ 余额 / 今日花费 / 本月花费 |
| ✅ Per-model Token & cost breakdown | ✅ 按模型拆分 Token 与花费 |
| ✅ Flash ⇄ Pro toggle | ✅ 点击切换 Flash / Pro 视图 |
| ✅ Cache hit rate with color | ✅ 缓存命中率 + 颜色编码 |
| ✅ Click to refresh | ✅ 点击手动刷新 |
| ✅ Auto-refresh every 30 min | ✅ 30 分钟自动刷新 |
| ✅ Cached display (no flicker) | ✅ 缓存显示，切应用不闪 |
| ✅ Auto re-login on 401 | ✅ Token 过期自动重登 |
| ✅ Encrypted credential storage | ✅ AES-256-GCM 本地加密 |
| ✅ GitHub Actions CI builds | ✅ CI 自动构建 APK |

## Screenshots
<img width="770" height="472" alt="image" src="https://github.com/user-attachments/assets/5c60f054-412c-47bd-9581-7750d9c192fa" />

### 上半区 — 账户总览
| 余额 | 今日花费 | 本月累计 |
|------|---------|---------|

### 下半区 — 模型用量明细
| Token | 缓存率 | 开销 | 请求数 | 月Token |
|-------|--------|------|--------|----------|

缓存率颜色：🟢 ≥90% · 🟡 ≥80% · 🔴 <80%

## Quick Start

1. **Download APK** from [GitHub Actions](https://github.com/MCwasd/deepseek-widget-new/actions) → latest run → Artifacts
2. **Install** on Android 8.0+
3. **Long press** home → Add widget → **DeepSeek Dashboard**
4. **Login** with DeepSeek platform email + password
5. **Done!** Tap widget to refresh; tap model area to switch Flash / Pro

## Data Sources

Uses three DeepSeek Platform API endpoints (all with Bearer Token auth):

| API | Data |
|-----|------|
| `get_user_summary` | Balance, monthly cost, monthly tokens |
| `usage/amount` | Daily token breakdown per model (input/cache/output/request) |
| `usage/cost` | Daily cost per model (CNY) |

Cache hit rate = `cache_hit / (cache_hit + cache_miss) × 100%`

## Model Support

| Model | Key |
|------|-----|
| `deepseek-v4-flash` | Flash model data |
| `deepseek-v4-pro` | Pro model data |

The widget displays one model at a time. Tap the model area to toggle.

## Tech Stack

- **Language:** Kotlin
- **Min SDK:** 26 (Android 8.0) · **Target SDK:** 34
- **Widget:** AppWidgetProvider + WorkManager
- **HTTP:** OkHttp 4.12
- **JSON:** Gson
- **Auth:** Login → Bearer Token, auto-refresh on 401
- **Encryption:** AndroidX EncryptedSharedPreferences (AES-256-GCM)
- **Build:** Gradle 8.2 + AGP 8.2.0
- **CI:** GitHub Actions (`build-apk.yml`)

## Build from Source

```bash
git clone https://github.com/MCwasd/deepseek-widget-new.git
cd deepseek-widget-new
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/
```

For debug signing, the repo includes `debug.keystore` (alias `debug`, password `android`).

For release builds, place a `keystore.properties` at project root:

```properties
storeFile=/path/to/release.keystore
storePassword=***
keyAlias=release
keyPassword=***
```

## Privacy

- Credentials stored **locally** (EncryptedSharedPreferences, AES-256-GCM)
- All API calls **directly** to DeepSeek servers (`platform.deepseek.com`)
- **No** analytics, telemetry, or third-party servers

## License

MIT — see [LICENSE](LICENSE)

---

## 中文

### 功能介绍

- ✅ **账号登录** — 邮箱 + 密码获取 Bearer Token，不再手动填 API Key
- ✅ **账户总览** — 余额、今日花费、本月累计三栏
- ✅ **模型详析** — Flash / Pro 切换查看 Token 量、缓存命中率、花费、请求次数
- ✅ **缓存命中率颜色** — ≥90% 绿 · ≥80% 黄 · <80% 红
- ✅ **缓存显示** — 刷新失败时保留上次数据，不闪屏
- ✅ **自动重登** — 401 自动用缓存密码重新登录

### 快速开始

1. 从 [Actions](https://github.com/MCwasd/deepseek-widget-new/actions) 下载 Artifact APK
2. 安装到 Android 8.0+ 手机
3. 长按桌面 → 小组件 → **DeepSeek 仪表盘**
4. 输入 DeepSeek 平台邮箱 + 密码登录
5. 点击小组件手动刷新，点击模型区域切换 Flash / Pro

### 数据来源

| 接口 | 获取数据 |
|------|---------|
| `get_user_summary` | 余额、月花费、月 Token |
| `usage/amount` | 当日模型 Token 拆分 |
| `usage/cost` | 当日模型花费 |

### 自行编译

```bash
git clone https://github.com/MCwasd/deepseek-widget-new.git
cd deepseek-widget-new
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/
```

### 隐私

密码加密存手机，请求直连 DeepSeek，无任何第三方中转。

### 许可证

MIT
