# DeepSeek 开放平台 API 调用方法总结

> 提取自 `deepseek-mobile-desktop-widget` 项目  
> 最后更新：2026-07-22

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

**关键点：**
- Token 无明确过期时间，靠 401 触发自动重登
- 凭据（邮箱+密码+Token）全部存 `EncryptedSharedPreferences`（AES-256-GCM）
- `device_id` 首次从浏览器抓包硬编码，后续持久化（非随机生成！）
- 无需 refresh token / OAuth 流程，登录即得所有权限

---

## 二、数据 API（共 3 个）

所有数据 API 共用统一的请求头：

```
Authorization: Bearer {login_token}
Accept: application/json
User-Agent: Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36
x-client-platform: web
```

### 2.1 用户概览 — get_user_summary

获取余额、月度费用、月度 Token 总数。

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
      "monthly_costs": [
        { "currency": "CNY", "amount": "79.59" }
      ],
      "monthly_token_usage": "289148830",
      "total_available_token_estimation": "13469388"
    }
  }
}
```

**提取逻辑：**

| 字段 | 路径 | 处理 |
|------|------|------|
| 余额 | `biz_data.normal_wallets[0].balance` | `String` → 保留两位小数 |
| 月花费 | `biz_data.monthly_costs[0].amount` | `String` → 保留两位小数 |
| 月 Token | `biz_data.monthly_token_usage` | `String` → `Long`（实际整数） |
| 可用 Token | `biz_data.total_available_token_estimation` | `String` → `Long` |

### 2.2 Token 用量明细 — usage/amount

按日 + 按模型拆分 Token 消耗。

```
GET https://platform.deepseek.com/api/v0/usage/amount?month=07&year=2026
```

**响应结构：**

```json
{
  "code": 0,
  "data": {
    "biz_code": 0,
    "biz_data": {
      "total": [
        {
          "model": "deepseek-v4-flash",
          "usage": [
            { "type": "PROMPT_TOKEN",             "amount": "222644992" },
            { "type": "PROMPT_CACHE_HIT_TOKEN",   "amount": "180000000" },
            { "type": "PROMPT_CACHE_MISS_TOKEN",  "amount": "42644992" },
            { "type": "RESPONSE_TOKEN",            "amount": "50000000" },
            { "type": "REQUEST",                   "amount": "128" }
          ]
        }
      ],
      "days": [
        {
          "date": "2026-07-21",
          "data": [ /* 同上结构，按模型分组 */ ]
        }
      ]
    }
  }
}
```

**提取逻辑：**

1. 找到 `days[]` 中 `date == 今天` 的条目
2. 遍历该天的 `data[]`（按模型），汇总各 `type` 的 `amount`
3. `amount` 是**实际整数** Token 数（不是百万单位），直接 `toLongOrNull()`

| type | 含义 | 归入字段 |
|------|------|----------|
| `PROMPT_TOKEN` | 输入 Token | `inputTokens` |
| `PROMPT_CACHE_HIT_TOKEN` | 缓存命中 | `cacheHitTokens` |
| `PROMPT_CACHE_MISS_TOKEN` | 缓存未命中 | `cacheMissTokens` |
| `RESPONSE_TOKEN` | 输出 Token | `outputTokens` |

**缓存命中率计算：**

```kotlin
cacheHitRate = (cacheHitTokens / (cacheHitTokens + cacheMissTokens)) * 100
// 保留一位小数，如 "91.3"
```

### 2.3 费用明细 — usage/cost

按日 + 按模型拆分花费（人民币）。

```
GET https://platform.deepseek.com/api/v0/usage/cost?month=07&year=2026
```

**响应结构：**

```json
{
  "code": 0,
  "data": {
    "biz_code": 0,
    "biz_data": [
      {
        "currency": "CNY",
        "total": [
          {
            "model": "deepseek-v4-flash",
            "usage": [
              { "type": "PROMPT_TOKEN",             "amount": "0.06" },
              { "type": "PROMPT_CACHE_HIT_TOKEN",   "amount": "0.01" },
              { "type": "PROMPT_CACHE_MISS_TOKEN",  "amount": "0.03" },
              { "type": "RESPONSE_TOKEN",            "amount": "0.02" }
            ]
          }
        ],
        "days": [
          {
            "date": "2026-07-21",
            "data": [ /* 同上结构 */ ]
          }
        ]
      }
    ]
  }
}
```

**提取逻辑：**

1. `biz_data` 是数组，取第一个元素
2. 找到 `days[]` 中 `date == 今天`
3. 遍历 `data[]`（按模型），只累加**实际计费项**：
   - `PROMPT_CACHE_MISS_TOKEN` — 缓存未命中才计费
   - `RESPONSE_TOKEN` — 输出计费
4. `amount` 是**小数**（CNY），累加后合并为当日总花费

**计费规则（DeepSeek）：**
- `PROMPT_CACHE_HIT_TOKEN` → ¥0.1/M tokens
- `PROMPT_CACHE_MISS_TOKEN` → ¥1.0/M tokens（原始输入价）
- `RESPONSE_TOKEN` → ¥4.0/M tokens（输出价）

cost API 返回的 amount 已经是乘以单价后的结果，直接累加即可。

---

## 三、数据流总览

```
DeepSeekAccountManager.login(email, password)
        │
        ▼  Bearer Token (EncryptedSharedPreferences)
        │
WidgetUpdateWorker.doWork()
        │
        ├── DeepSeekApiClient(token).fetchAll()
        │       │
        │       ├── fetchSummary()     → 余额 + 月花费 + 月 Token
        │       ├── fetchTodayUsage()
        │       │       ├── usage/amount  → Token 明细 (Flash/Pro 分拆)
        │       │       └── usage/cost    → 费用明细 (Flash/Pro 分拆)
        │       │
        │       └── 合并 → WidgetDisplayData
        │
        └── DeepSeekWidget.updateWidgets(data)
                │
                └── RemoteViews → 渲染到桌面
```

---

## 四、WidgetDisplayData 完整字段

```kotlin
data class WidgetDisplayData(
    val isAvailable: Boolean,       // 数据是否可用
    val balance: String,            // "40.41"
    val totalAvailableTokens: Long, // 13469388
    val todayCost: String,          // "2.15"（总今日花费）
    val monthlyCost: String,        // "79.59"
    val monthlyTokens: Long,        // 289148830
    val flashData: ModelData,       // Flash 模型专属数据
    val proData: ModelData,         // Pro 模型专属数据
    val updatedAt: Long,            // System.currentTimeMillis()
    val error: String?              // 错误信息（非空=刷新失败）
)

data class ModelData(
    val totalTokens: Long,          // Token 总量
    val cacheHitRate: String,       // "91.3" 或 "--"
    val cost: String                // "1.85"
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
| 7 | `RemoteViews` 用 `layout_weight` | AppWidget 不支持 | 全部 `wrap_content` + `margin` |
| 8 | 配置页用 `AppCompatActivity` | 主题冲突 | 改用普通 `Activity` |
| 9 | 协程在 Worker 中使用 | 不需要异步 | 全部 OkHttp 同步调用 |
| 10 | Widget 更新时闪白 | `onUpdate` 直接请求网络 | 先显示缓存数据，再异步拉取 |

---

## 六、关键代码片段

### 调用入口

```kotlin
// 获取 token（优先平台 Token，回退 API Key）
val token = DeepSeekWidget.getTokenFromAccount(context)
    ?: DeepSeekWidget.getApiKey(context)

// 拉取数据
val client = DeepSeekApiClient(token)
val data = client.fetchAll()

// 渲染到 Widget
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
