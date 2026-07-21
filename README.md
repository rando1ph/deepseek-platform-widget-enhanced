# DeepSeek Dashboard Widget 📊 Under construction Now

> 在 Android 桌面实时查看 DeepSeek API 余额
> Real-time DeepSeek API balance on your Android home screen

---

**English** · [中文](#中文)

---

## Features

| English | 中文 |
|---------|------|
| ✅ Real-time balance in CNY/USD | ✅ 实时显示账户余额 |
| ✅ Click to refresh | ✅ 点击一键刷新 |
| ✅ Auto-refresh every 30 min | ✅ 30 分钟自动刷新 |
| ✅ Cached display (no "Hello" flicker) | ✅ 缓存余额，切应用不闪 |
| ✅ Secure API key storage (AES-256-GCM) | ✅ API Key 加密存储 |
| ✅ Dark-theme rounded UI | ✅ 暗色圆角 UI |

> ⚠️ Token usage / cost / cache data is **not shown** because DeepSeek does not provide a public API for usage statistics.

## Quick Start

1. **Download** the latest APK from [Releases](https://github.com/MCwasd/deepseek-widget-new/releases)
2. **Install** on Android 8.0+
3. **Long press** home screen → **Widgets** → find **DeepSeek Dashboard**
4. **Enter** your DeepSeek API Key
5. **Done!** Tap the widget to refresh

> 💡 Get your API Key at [platform.deepseek.com](https://platform.deepseek.com)

## API Used

- `GET https://api.deepseek.com/user/balance` — Balance inquiry (the only public billing API DeepSeek provides)

## Tech Stack

- **Language:** Kotlin
- **Min SDK:** 26 (Android 8.0)
- **Target SDK:** 34
- **Widget:** AppWidgetProvider + WorkManager
- **HTTP:** OkHttp 4.12
- **Encryption:** AndroidX EncryptedSharedPreferences
- **Build:** Gradle 8.2 + AGP 8.2.0

## Build from Source

```bash
git clone https://github.com/MCwasd/deepseek-widget-new.git
cd deepseek-widget-new
./gradlew assembleRelease
# APK: app/build/outputs/apk/release/
```

## Privacy

- API Key is stored **locally** (EncryptedSharedPreferences)
- All calls go **directly** to DeepSeek servers
- **No** third-party server or telemetry

## License

MIT

---

## 中文

### 功能介绍

手机桌面小组件，实时显示 DeepSeek API 账户余额。

- ✅ **余额** — 显示 ¥ 余额
- ✅ **点击刷新** — 点一下小组件即刷新
- ✅ **自动刷新** — 每 30 分钟自动更新
- ✅ **缓存显示** — 切回桌面不闪"Hello Widget"
- ✅ **按键加密** — API Key AES-256-GCM 本地加密存储
- ✅ **暗色圆角** — 美观的暗色风格 UI

> ⚠️ Token 消耗/费用/缓存数据**不支持**——DeepSeek 没有提供公开的用量统计 API。

### 快速开始

1. 从 [Releases](https://github.com/MCwasd/deepseek-widget-new/releases) 下载最新 APK
2. 安装到 Android 8.0+ 手机
3. 长按桌面空白处 → 添加小组件 → **DeepSeek 仪表盘**
4. 输入你的 DeepSeek API Key
5. 搞定！点击小组件即可刷新

> 💡 在 [platform.deepseek.com](https://platform.deepseek.com) 获取 API Key

### 自行编译

```bash
git clone https://github.com/MCwasd/deepseek-widget-new.git
cd deepseek-widget-new
./gradlew assembleDebug
# APK 在 app/build/outputs/apk/release/
```

### 隐私说明

- API Key **仅存手机本地**，系统级加密
- 请求 **直连 DeepSeek**，不经第三方
- **不收集** 任何信息

### 许可证

MIT