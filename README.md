# DeepSeek Dashboard Widget 📊

> Real-time DeepSeek API balance, usage, and cache hit rate — on your Android home screen

<img width="770" alt="screenshot" src="https://github.com/user-attachments/assets/5c60f054-412c-47bd-9581-7750d9c192fa" />

## Features

- ✅ **Account login** — email + password → Bearer Token, no manual API key
- ✅ **Balance · Today Cost · Month Cost** — three-panel summary at a glance
- ✅ **Per-model breakdown** — Flash / Pro split with Token, cache rate, cost, requests
- ✅ **Cache hit rate** with color coding — 🟢 ≥90% · 🟡 ≥80% · 🔴 <80%
- ✅ **Cached display** — no flicker when switching apps or refreshing
- ✅ **Auto re-login** — 401 triggers silent token refresh
- ✅ **Auto-refresh every 30 min** — or tap to refresh instantly
- ✅ **Encrypted storage** — credentials in AES-256-GCM EncryptedSharedPreferences
- ✅ **GitHub Actions** — CI builds APK on every push

## Widget Areas & Interaction

| Area | Location | Action |
|------|----------|--------|
| **Summary** (Balance · Today · Month) | Upper half | **Tap → Refresh** |
| **Model Detail** (Token · Cache · Cost · Requests) | Lower half | **Tap → Toggle Flash / Pro** |

> The two areas are separated by a horizontal divider. Tap the top to pull fresh data from DeepSeek; tap the bottom to switch between Flash and Pro model stats.

## Quick Start

1. **Download APK** from [GitHub Actions](https://github.com/MCwasd/deepseek-widget-new/actions) → latest run → Artifacts
2. **Install** on Android 8.0+
3. **Long press** home → Add widget → **DeepSeek Dashboard**
4. **Login** with your DeepSeek platform email + password
5. Tap the widget and it's ready to go

## Data Sources

Three DeepSeek Platform API endpoints, all authenticated via Bearer Token:

| API | Returns |
|-----|---------|
| `get_user_summary` | Balance, monthly cost, monthly tokens |
| `usage/amount` | Daily token breakdown by model (input / cache / output / requests) |
| `usage/cost` | Daily cost by model (CNY) |

Cache hit rate = `cache_hit / (cache_hit + cache_miss) × 100%`

## Tech Stack

- **Language:** Kotlin · **Min SDK:** 26 · **Target SDK:** 34
- **Widget:** AppWidgetProvider + WorkManager
- **HTTP:** OkHttp 4.12 · **JSON:** Gson
- **Auth:** Login → Bearer Token, auto-refresh on 401
- **Encryption:** AndroidX EncryptedSharedPreferences (AES-256-GCM)
- **Build:** Gradle 8.2 + AGP 8.2.0 · **CI:** GitHub Actions

## Build from Source

```bash
git clone https://github.com/MCwasd/deepseek-widget-new.git
cd deepseek-widget-new
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/
```

Debug keystore included (`debug.keystore`, alias `debug`, password `android`).

For release builds, add `keystore.properties` at project root:

```properties
storeFile=/path/to/release.keystore
storePassword=***
keyAlias=release
keyPassword=***
```

## Privacy

- Credentials stored **locally** (EncryptedSharedPreferences, AES-256-GCM)
- All API calls go **directly** to `platform.deepseek.com`
- **No** analytics, telemetry, or third-party servers

## License

MIT — see [LICENSE](LICENSE)

---

# 中文

## 功能介绍

- ✅ **账号登录** — 邮箱 + 密码获取 Bearer Token，无需手动填写 API Key
- ✅ **三栏总览** — 余额 / 今日花费 / 本月累计
- ✅ **模型详析** — Flash / Pro 切换，显示 Token 量、缓存命中率、花费、请求数
- ✅ **缓存命中率颜色** — ≥90% 绿 · ≥80% 黄 · <80% 红
- ✅ **缓存显示** — 切应用不闪，刷新失败保留上次数据
- ✅ **自动重登** — 401 过期自动用缓存密码重新登录
- ✅ **每 30 分钟自动刷新** — 也可点击手动刷新
- ✅ **AES-256-GCM 加密存储** — 账号密码仅存手机本地
- ✅ **CI 自动构建** — 推送即自动编译 APK

## 交互区域说明

| 区域 | 位置 | 操作 |
|------|------|------|
| **上半区** — 余额 / 今日 / 本月 | 分割线以上 | **点击 → 刷新数据** |
| **下半区** — Token / 缓存率 / 开销 / 请求 | 分割线以下 | **点击 → 切换 Flash / Pro 模型** |

## 快速开始

1. 从 [GitHub Actions](https://github.com/MCwasd/deepseek-widget-new/actions) 下载最新 Artifact APK
2. 安装到 Android 8.0+ 手机
3. 长按桌面 → 小组件 → **DeepSeek 仪表盘**
4. 输入 DeepSeek 平台邮箱 + 密码登录
5. 点击小组件即可使用

## 数据来源

三个 DeepSeek 平台 API，均通过 Bearer Token 认证：

| 接口 | 获取数据 |
|------|---------|
| `get_user_summary` | 余额、月花费、月 Token |
| `usage/amount` | 当日按模型拆分的 Token 明细 |
| `usage/cost` | 当日按模型拆分花费 |

## 自行编译

```bash
git clone https://github.com/MCwasd/deepseek-widget-new.git
cd deepseek-widget-new
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/
```

## 隐私说明

密码加密存手机，请求直连 DeepSeek 服务器，无任何第三方中转。

## 许可证

MIT