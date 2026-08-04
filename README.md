# aiwei-tools-service

为 `aiwei-ainas`、`aiweios-server` 提供统一工具能力的独立服务。

当前里程碑已经提供：

- 28 个 aiweios 静态逻辑工具的统一目录；
- `POST /api/v1/tools/{toolName}/invoke` 调用协议；
- 请求 ID、调用上下文、标准结果和标准错误；
- 可选服务 API Key 鉴权；
- 工具级超时与阻塞执行隔离；
- `mcp.time.now`、`mcp.calculator.eval`、`rail.search`、`flight.search`、
  `stock.quote`、`stock.kline`、`map.route`、`map.nearby`、`map.traffic`、
  `location.now`、`travel.departure.plan`、`travel.compare`、`weather.get`、
  `news.search`、`mcp.search.web` 和 `mcp.fetch.url` 的真实数据执行器；
- 个人助理、知识、记忆、设备命令、安全浏览器占位和 HIFIVE 音乐执行器。

28 个静态逻辑工具现在都已有执行器。原项目中的演示型工具没有继续伪造实时数据：
行程会标明实时路线需另查，知识检索限定内置文档作用域，设备控制只返回待调用方下发的命令契约。

`rail.search` 使用聚合数据火车票接口。启用前需配置：

```powershell
$env:JUHE_TRAIN_KEY='replace-with-provider-key'
```

## 本地运行

```powershell
$env:JAVA_HOME='D:\java\jdk-17'
$env:JUHE_TRAIN_KEY='replace-with-provider-key'
$env:AIWEI_TOOLS_API_KEY='replace-with-shared-secret'
mvn spring-boot:run
```

默认端口为 `8095`。

航班支持以下供应商，`FLIGHT_PROVIDER=auto` 时自动降级：

```powershell
# 飞常准
$env:VARIFLIGHT_API_KEY='replace-with-key'

# 或飞猪
$env:ALITRIP_APP_KEY='replace-with-app-key'
$env:ALITRIP_APP_SECRET='replace-with-app-secret'

# 或聚合航班
$env:JUHE_FLIGHT_KEY='replace-with-key'
$env:JUHE_FLIGHT_URL='replace-with-flight-endpoint'
```

`stock.quote` 对 A 股和港股优先使用腾讯快速实时行情（默认 2 秒超时），
美股或快速源失败时再使用可选的 `$env:JUHE_STOCK_KEY`，最后才降级到最近交易日收盘价。
`stock.kline` 独立使用公开市场日 K 数据，不阻塞实时报价。
可通过 `$env:TENCENT_STOCK_QUOTE_URL` 覆盖腾讯行情地址，
`$env:STOCK_TIMEOUT_MS` 控制回退行情与 K 线请求超时（默认 4000ms）。

地图、定位和出发建议共用高德 Web Service Key：

```powershell
$env:AMAP_WEB_SERVICE_KEY='replace-with-amap-web-service-key'
```

`location.now` 不读取服务端 Session，只使用调用方显式传入的经纬度或城市上下文。
`travel.departure.plan` 会组合路线、天气和场景预留；未来七天内的驾车请求优先使用高德未来路线预测。

新闻和通用网页搜索优先使用百度 AI 搜索，未配置密钥或请求失败时降级到 DuckDuckGo 即时答案：

```powershell
$env:BAIDU_AI_SEARCH_API_KEY='replace-with-baidu-search-key'
```

`mcp.fetch.url` 仅允许公网 HTTP/HTTPS 标准端口，拒绝本机、内网、链路本地、多播和保留地址，
并通过 `FETCH_URL_MAX_CHARS` 限制返回正文长度。

## 海搜影视分享索引

`media.share.search` 和 `media.share.validate` 通过 iDataRiver 的海搜开放 API 返回结构化网盘分享信息。
工具服务只负责公共索引适配，不保存用户收藏、下载任务或媒体库数据，也不代理网盘文件下载。

```powershell
$env:HAISOU_API_KEY='idr_***'
$env:HAISOU_DAILY_FREE_LIMIT='100'
$env:HAISOU_QUOTA_ZONE='UTC'
$env:HAISOU_QUOTA_FILE='D:\data\aiwei-tools-state\haisou-quota.json'
```

调用次数在请求供应商前持久化预占，失败请求也计数；服务重启后继续读取同一计数文件。
硬上限不会超过公开免费档的每日 100 次，达到上限后不再请求供应商，也不会自动重试付费类请求。
搜索结果只允许已知网盘的 HTTPS 分享域名，下载必须由设备端用户在隔离浏览器中明确确认。

状态类工具使用 `tenantId + userId` 隔离的 JSONL 存储，目录可配置：

```powershell
$env:AIWEI_TOOLS_STATE_DIR='D:\data\aiwei-tools-state'
```

`reminder.create` 保存为待投递记录；到点响铃和播报需要由第二阶段的通知工作进程消费。
`mcp.browser.operate` 当前仅支持搜索和安全只读网页提取，不提供点击、登录或表单提交。

音乐使用 HIFIVE 真实接口：

```powershell
$env:HIFIVE_APP_ID='replace-with-app-id'
$env:HIFIVE_SERVER_CODE='replace-with-server-code'
```

返回值是可播放队列，实际音频播放仍由 AINAS 或 aiweios 的设备链路完成。

## AINAS 接入

启动工具服务后，在启动 `aiwei-ainas-gateway` 前设置：

```powershell
$env:AINAS_TOOLS_SERVICE_ENABLED='true'
$env:AINAS_TOOLS_SERVICE_BASE_URL='http://127.0.0.1:8095'
$env:AINAS_TOOLS_SERVICE_API_KEY='replace-with-shared-secret'
```

AINAS 的远程工具开关默认是 `false`。未启用时不会注册 `func_rail_search`，
原有本地工具和普通对话链路不受影响。

还可以分别关闭单个远程工具：

```powershell
$env:AINAS_REMOTE_RAIL_ENABLED='false'
$env:AINAS_REMOTE_FLIGHT_ENABLED='false'
$env:AINAS_REMOTE_STOCK_QUOTE_ENABLED='false'
$env:AINAS_REMOTE_STOCK_KLINE_ENABLED='false'
$env:AINAS_REMOTE_MAP_ROUTE_ENABLED='false'
$env:AINAS_REMOTE_MAP_NEARBY_ENABLED='false'
$env:AINAS_REMOTE_MAP_TRAFFIC_ENABLED='false'
$env:AINAS_REMOTE_LOCATION_ENABLED='false'
$env:AINAS_REMOTE_DEPARTURE_PLAN_ENABLED='false'
$env:AINAS_REMOTE_TRAVEL_COMPARE_ENABLED='false'
$env:AINAS_REMOTE_FETCH_URL_ENABLED='false'
```

`AINAS_REMOTE_LOCATION_ENABLED` 默认即为 `false`，只有 AINAS 调用链能够传入设备真实经纬度后才应开启。
`AINAS_REMOTE_FETCH_URL_ENABLED` 同样默认关闭；AINAS 现有天气和联网搜索始终保持本地优先。

## 调用示例

```powershell
$body = @{
  requestId = 'req-1'
  tenantId = 'default'
  userId = 'user-1'
  sessionId = 'session-1'
  arguments = @{ expression = '20000乘以3.14减5' }
  context = @{ locale = 'zh-CN'; timezone = 'Asia/Shanghai' }
} | ConvertTo-Json -Depth 5

Invoke-RestMethod `
  -Method Post `
  -Uri 'http://127.0.0.1:8095/api/v1/tools/mcp.calculator.eval/invoke' `
  -Headers @{ 'X-Tools-Api-Key' = $env:AIWEI_TOOLS_API_KEY } `
  -ContentType 'application/json' `
  -Body $body
```
