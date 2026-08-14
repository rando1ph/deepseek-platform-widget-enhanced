# DeepSeek 开放平台 API 调用方法总结

> 提取自 `deepseek-widget-new` 项目  
> 最后更新：2026-08-14（适配 2026-08 平台更新：时区支持 + 新用量端点 + 峰谷定价）

---

## 一、认证流程

### 1.1 登录接口

调用平台 API 前必须先登录获取 Bearer Token（**非 API Key**）。

```
POST https://platform.deepseek.com/auth-api/v0/users/login
Content-Type: application/json
User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36
x-client-platform: web
x-client-version: 1.0.0
x-app-version: 1.0.0
```

**请求体：**

```json
{
  "email": "your@email.com",
  "mobile": "",
  "password": "***",
  "area_code": "",
  "device_id": "Base64指纹（非UUID，首次从浏览器抓包获取后持久化）",
  "os": "web"
}
```

**响应结构：**

```json
{
  "code": 0,
  "data": {
    "biz_code": 0,
    "biz_msg": "",
    "biz_data": {
      "user": {
        "token": "Bearer-Token-String"
      }
    }
  }
}
```

**判断逻辑：**
- `data.biz_code == 0` → 成功，取 `data.biz_data.user.token`
- `data.biz_code == 2` → 密码错误
- 其他 → 登录失败

> ⚠️ 2026-08 实测：平台 web 接口已**不接受 API Key 作为 Bearer**（返回 40003），只能走账号登录 Token。

### 1.2 Token 生命周期

```
首次配置 → login(email, password) → 保存到 EncryptedSharedPreferences
                            ↓
每次 API 调用 → getValidToken() → 有缓存直接返回
                            ↓
             无缓存但有凭据 → 自动 re-login
                            ↓
           API 返回 401 → refreshToken() → 重新登录
```

- Token 无明确过期时间，靠 401 触发自动重登
- 凭据（邮箱+密码+Token）全部存 `EncryptedSharedPreferences`（AES-256-GCM）
- `device_id` 首次从浏览器抓包硬编码，后续持久化（非随机生成！）
- 无需 refresh token / OAuth 流程，登录即得所有权限

---

## 二、数据 API（2026-08 版）

### ⚠️ 2026-08 平台更新（重要）

1. **用量明细接口迁移**：旧的 `usage/amount?month=&year=` / `usage/cost?month=&year=`
   已被新的 **by_api_key** 端点取代（网页端已全部切换到新端点，并新增「按 API Key 分组」视图）：
   - `GET /api/v0/usage/by_api_key/amount`
   - `GET /api/v0/usage/by_api_key/cost`
2. **参数从「月/年」改为「时间范围 + 时区」**：
   - `start` / `end`：Unix 时间戳（秒），范围左闭右开
   - `tz`：时区偏移（秒），必须是 900 的倍数，范围 [-43200, 50400]（即 -12h ~ +14h）
3. **`get_user_summary` 字段调整**：`monthly_costs` / `monthly_token_usage`
   已被前端弃用（网页改用 `total_costs`、`monthly_usage`）。**不要依赖 summary 计算月度开销**，
   一律从新用量端点按月范围自行汇总。
4. **时区**：开放平台用量页支持自定义时区（跟随设备 / UTC / 自定义偏移），
   月/日统计边界按该时区计算。小组件需显式传 `tz` 并用自己的时区计算今日/本月边界。

所有数据 API 共用请求头：

```
Authorization: Bearer {login_token}
Accept: application/json
User-Agent: Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36
x-client-platform: web
```

### 2.1 用户概览 — get_user_summary

获取余额、可用 Token 估算。

```
GET https://platform.deepseek.com/api/v0/users/get_user_summary
```

**响应结构：**

```json
{
  "code": 0,
  "data": {
    "biz_code": 0,
    "biz_data": {
      "normal_wallets": [
        { "currency": "CNY", "balance": "40.41", "token_estimation": "13469388" }
      ],
      "total_available_token_estimation": "13469388",
      "current_token": 0,
      "monthly_usage": "...",
      "total_usage": 123,
      "total_costs": [ { "currency": "CNY", "amount": "79.59" } ]
    }
  }
}
```

**提取逻辑（小组件只用于余额 + 可用 Token）：**

| 字段 | 路径 | 处理 |
|------|------|------|
| 余额 | `biz_data.normal_wallets[0].balance` | `String` → 保留两位小数 |
| 可用 Token | `biz_data.total_available_token_estimation` | `String` → `Long` |

### 2.2 用量明细（新）— usage/by_api_key/amount

按时间范围 + 时区 + 模型 + API Key 拆分 Token 消耗。

```
GET https://platform.deepseek.com/api/v0/usage/by_api_key/amount?start=1785513600&end=1788192000&tz=28800
```

**参数：**
- `start` / `end`：Unix 秒；例如「2026-08 本月」= 8月1日 00:00（时区本地）~ 9月1日 00:00
- `tz`：时区偏移秒（北京 = 28800，UTC = 0）

**响应结构：**

```json
{
  "code": 0,
  "data": {
    "biz_code": 0,
    "biz_data": {
      "start": 1785513600,
      "end": 1788192000,
      "bucket": "1d",
      "models": ["deepseek-v4-flash", "deepseek-v4-pro"],
      "series": [
        {
          "api_key": "sk-...",
          "model": "deepseek-v4-flash",
          "buckets": [
            { "time": 1785513600, "usage": {
                "PROMPT_CACHE_HIT_TOKEN": "180000000",
                "PROMPT_CACHE_MISS_TOKEN": "42644992",
                "RESPONSE_TOKEN": "50000000",
                "REQUEST": "128"
            } }
          ]
        }
      ]
    }
  }
}
```

**提取逻辑（小组件）：**

1. 遍历 `series[]`（每个 API Key × 模型一条），按 `model` 归并
2. 只统计 `time ∈ [start, end)` 的 bucket；同一次请求可同时得到
   「今日」（`time ∈ [今日00:00, 明日00:00)`）与「本月」两组
3. `usage` 键名为大写：`PROMPT_CACHE_HIT_TOKEN` / `PROMPT_CACHE_MISS_TOKEN` /
   `RESPONSE_TOKEN` / `REQUEST`；**没有** PROMPT_TOKEN（输入 = 命中 + 未命中）
4. `amount` 是实际整数 Token 数，直接 `toLongOrNull()`
5. `REQUEST` 单独归入 `requests` 字段，不计入 `totalTokens`

| type | 含义 | 归入字段 |
|------|------|----------|
| `PROMPT_CACHE_HIT_TOKEN` | 缓存命中输入 | `cacheHitTokens` |
| `PROMPT_CACHE_MISS_TOKEN` | 缓存未命中输入 | `cacheMissTokens` |
| `RESPONSE_TOKEN` | 输出 | `outputTokens` |
| `REQUEST` | API 请求次数 | `requests` |

**缓存命中率：** `cacheHitRate = (hit / (hit + miss)) * 100`，保留一位小数。

### 2.3 费用明细（新）— usage/by_api_key/cost

按时间范围 + 时区 + 模型 + API Key 拆分花费（人民币，已含峰谷计价）。

```
GET https://platform.deepseek.com/api/v0/usage/by_api_key/cost?start=1785513600&end=1788192000&tz=28800
```

**响应结构：**

```json
{
  "code": 0,
  "data": {
    "biz_code": 0,
    "biz_data": {
      "start": 1785513600,
      "end": 1788192000,
      "bucket": "1d",
      "models": ["deepseek-v4-flash", "deepseek-v4-pro"],
      "data": [
        {
          "currency": "CNY",
          "series": [
            {
              "api_key": "sk-...",
              "model": "deepseek-v4-flash",
              "buckets": [
                { "time": 1785513600, "cost": "1.85" }
              ]
            }
          ]
        }
      ]
    }
  }
}
```

**提取逻辑：**
1. `biz_data.data` 是数组，取第一个元素（CNY）
2. 遍历 `series[]` 按模型归并，累加 `buckets[].cost`
3. `cost` 是小数（CNY），**已是乘过单价（含峰谷）的计费结果**，直接累加保留两位
4. 平台按用户配置时区切分 bucket，小组件按 `time` 过滤今日/本月

### 2.4 小组件数据流（2026-08 版）

```
WidgetUpdateWorker.doWork()
    ├── DeepSeekPricing.ensureFresh()   ← 官方定价页（>6h 重抓，失败用默认值）
    ├── DeepSeekApiClient(token, tz).fetchAll()
    │       ├── fetchSummary()          → 余额 + 可用 Token
    │       └── fetchMonthUsage()
    │               ├── by_api_key/amount?start&end&tz → 本月/今日 Token（Flash/Pro 分拆）
    │               └── by_api_key/cost?start&end&tz   → 本月/今日费用（Flash/Pro 分拆）
    │                   月度开销 = 全模型 cost 汇总（不再依赖 summary.monthly_costs）
    └── DeepSeekWidget.updateWidgets(data) → RemoteViews 渲染 + 价格状态行
```

---

## 三、峰谷定价与自动更新

- **来源**：官方定价页 `https://api-docs.deepseek.com/zh-cn/quick_start/pricing/`（Docusaurus SSR，可直接解析）
- **生效**：2026-08-17 00:00（北京时间）起；高峰时段 9:00-12:00、14:00-18:00（其余闲时，闲时为高峰一半）
- **价格**（元 / 百万 tokens）：

| 模型 | 时段 | 输入(缓存命中) | 输入(缓存未命中) | 输出 |
|------|------|------|------|------|
| deepseek-v4-flash | 闲时 | 0.05 | 1.5 | 4.5 |
| deepseek-v4-flash | 高峰 | 0.10 | 3.0 | 9.0 |
| deepseek-v4-pro | 闲时 | 0.15 | 4.5 | 13.5 |
| deepseek-v4-pro | 高峰 | 0.30 | 9.0 | 27.0 |

- **自动更新**：小组件每次刷新检查定价缓存（TTL 6h），抓取官方页解析价格表、
  生效日期与高峰时段，官方改价后自动跟随，无需发版
- **小组件状态行**：峰谷生效后显示 `Flash 高峰 ¥9/M` / `Pro 闲时 ¥13.5/M`（当前模型输出价）；
  公告未生效时显示 `8-17起 峰谷定价`；抓取失败回退内置默认值（与公告一致）
- **时区**：小组件配置页可选「跟随设备 / 北京时间 / UTC」，影响今日/本月统计边界

---

## 四、WidgetDisplayData 完整字段

```kotlin
data class WidgetDisplayData(
    val isAvailable: Boolean,       // 数据是否可用
    val balance: String,            // "40.41"
    val totalAvailableTokens: Long, // 13469388
    val todayCost: String,          // "2.15"（总今日花费）
    val monthlyCost: String,        // "79.59"（由新 cost 端点汇总）
    val monthlyTokens: Long,        // 289148830（由新 amount 端点汇总）
    val flashData: ModelData,       // Flash 模型专属数据
    val proData: ModelData,         // Pro 模型专属数据
    val updatedAt: Long,            // System.currentTimeMillis()
    val error: String?              // 错误信息（非空=刷新失败）
)

data class ModelData(
    val totalTokens: Long,          // Token 总量（不含 REQUEST）
    val cacheHitRate: String,       // "91.3" 或 "--"
    val cost: String,               // "1.85"
    val requests: Long              // API 请求次数
)
```

**格式化辅助方法：**

| 方法 | 示例输出 |
|------|---------|
| `formattedBalance` | `¥40.41` |
| `formattedTodayCost` | `¥2.15` |
| `formattedMonthCost` | `¥79.59` |
| `formattedMonthTokens` | `289.1M` |
| `formattedAvailableTokens` | `13.5M` |
| `formattedUpdatedTime` | `14:30` |

---

## 五、踩坑记录

| # | 问题 | 原因 | 解决 |
|---|------|------|------|
| 1 | `device_id` 用 `UUID.randomUUID()` | 平台校验 fingerprint 格式，UUID 被拒绝 | 从浏览器抓包硬编码 Base64 fingerprint，持久化复用 |
| 2 | `area_code` 设为 `"86"` | 平台默认空字符串 | 改为 `""` |
| 3 | 登录成功取 `response.code` 判断 | 嵌套响应，需要看 `data.biz_code` | 判断 `data.biz_code == 0` |
| 4 | `usage/amount` 数值乘以 1000000 | 返回的就是实际整数 Token 数 | 直接 `toLong()`，不乘 |
| 5 | cost API 金额单位搞错 | 返回的是 CNY 小数 | 直接累加，保留两位 |
| 6 | `R8/ProGuard` 混淆导致库炸 | Tink / OkHttp 被混淆 | `isMinifyEnabled = false` |
| 7 | `RemoteViews` 用 `layout_weight` | 必须放在横向 `LinearLayout` 子项中，不能放 `FrameLayout` | 横向 `LinearLayout` + `layout_weight="1"`，保留 `minHeight=140dp`/`minResizeHeight=80dp` |
| 8 | 配置页用 `AppCompatActivity` | 主题冲突 | 改用普通 `Activity` |
| 9 | 协程在 Worker 中使用 | 不需要异步 | 全部 OkHttp 同步调用 |
| 10 | Widget 更新时闪白 | `onUpdate` 直接请求网络 | 先显示缓存数据，再异步拉取 |
| 11 | 用量 API 日期对不上 | 旧版用 UTC 匹配 `days[].date` | 新端点直接传 `start/end/tz`，不再本地拼日期 |
| 12 | `REQUEST` 类型混入 Token 总数 | amount API 返回 REQUEST 类型（请求次数） | 单独统计为 `requests` 字段，不计入 totalTokens |
| 13 | **2026-08 本月开销失效** | 平台迁移到 `usage/by_api_key/*` 端点（`start/end/tz` 参数）；`summary.monthly_costs` 弃用 | 改用新端点按 `[月初, 下月初)` 范围汇总；月度开销/月Token 全部自算 |
| 14 | **平台 API Key 作 Bearer 失效** | 平台 web 接口 2026-08 起拒绝 API Key（40003） | 只能走账号登录 Token |
| 15 | **价格表解析错位** | 页面里 `deepseek-v4-flash` 先出现在模型表 | 用 `lastIndexOf` 取价格表那一处 |
| 16 | Token 总量虚高（旧版） | 旧端点 PROMPT_TOKEN = HIT+MISS，旧代码把输入算了两次 | 新端点无 PROMPT_TOKEN，输入 = HIT + MISS 计一次 |

---

## 六、数据缓存序列化

Widget 切换 Flash/Pro 时无需重新请求 API，直接从缓存恢复：

```kotlin
// 序列化：14 个字段用 | 拼接存 SharedPreferences
private fun serialize(d: WidgetDisplayData) = listOf(
    d.balance, d.todayCost, d.monthlyCost, d.monthlyTokens,
    d.updatedAt,
    d.flashData.totalTokens, d.flashData.cacheHitRate, d.flashData.cost, d.flashData.requests,
    d.proData.totalTokens, d.proData.cacheHitRate, d.proData.cost, d.proData.requests,
    d.error ?: ""
).joinToString("|")
```

**缓存策略：**
- 每次 API 成功 → `putString(KEY_CACHED_DATA, serialize(data))`
- 切换模型 → `deserialize(prefs.getString(KEY_CACHED_DATA, null))` → 改 `model_pro` flag → 重新 render
- 刷新失败 → 保留旧缓存，仅更新 error 字段
- 缓存命中率颜色编码：≥90% 绿色 `#198754`，≥80% 黄色 `#E09F00`，<80% 红色 `#D93025`
- 定价缓存：`pricing_cache`（JSON，含 `fetchedAt`），TTL 6h，抓取失败保留旧值/默认值

## 七、关键代码片段

### 调用入口

```kotlin
// 获取 token（仅平台账号 Token；API Key 已被平台拒绝）
val token = DeepSeekWidget.getTokenFromAccount(context) ?: return

// 确保定价缓存新鲜（失败静默）
DeepSeekPricing.ensureFresh(context)

// 拉取数据（时区由配置决定：设备/北京/UTC）
val tz = DeepSeekWidget.getUsageTimeZone(context)
val client = DeepSeekApiClient(token, tz)
val data = client.fetchAll()

// 渲染到 Widget（状态行显示当前峰谷价格）
DeepSeekWidget.updateWidgets(context, data)
```

### Token 刷新

```kotlin
// 401 自动重登
fun refreshToken(): String? {
    val email = loadEmail() ?: return null
    val password = loadPassword() ?: return null
    return try { login(email, password) } catch (_: Exception) { null }
}
```
