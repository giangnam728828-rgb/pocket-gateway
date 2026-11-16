# 口袋网关 PocketGateway

> 一个运行在**安卓手机**上的通用 AI 模型 API 网关。把多个平台的模型统一包装成 **OpenAI 兼容 API**，任何支持自定义 `base_url` 的客户端都能直接接入。

## 它解决什么问题

你手上有多个模型服务的访问方式（官方 API、自建服务、公司内部网关、各种中转……），它们的地址、鉴权、协议各不相同。上层应用想切换模型时，得逐个改配置。

口袋网关在这中间插了一层：

```
你的应用 (OpenAI SDK / ChatGPT Next Web / LobeChat / ...)
        │  OpenAI 兼容 API
        ▼
   口袋网关 (跑在手机上)
        │  协议适配 + 模型映射 + 负载均衡 + 故障转移
        ▼
  渠道 A / 渠道 B / 渠道 C ...
```

上层应用只需要对接**一个地址**，模型怎么路由、走哪个渠道，全在 App 里配置。

## 核心特性

- **渠道可插拔**：任何提供 HTTP API 的平台都能通过配置接入，无需改代码。
- **官方 API 预设**：内置智谱、DeepSeek、Kimi、通义、豆包、Claude、Gemini 等主流平台的官方 API 配置，一键填充。
- **多协议适配**：OpenAI 兼容 / Claude (Anthropic) / Gemini (Google) / 自定义透传。
- **自定义请求头**：任意 Header、标准客户端 UA 模板，支持 `{key}` 占位符，便于对接自建/自有服务。
- **OpenAI 兼容出口**：`/v1/chat/completions`、`/v1/models`、`/v1/embeddings`，支持流式 SSE。
- **模型名映射**：对外暴露统一模型名，内部映射到各渠道的真实模型名。
- **轮询 / 随机 / 优先级**路由，按权重分配，自动故障转移。
- **鉴权**：可设置访问令牌，防止局域网内他人滥用。
- **后台常驻**：前台服务 + WakeLock + WifiLock，锁屏后依然可用。
- **局域网访问**：生成 API 地址二维码，扫码即可在其它设备配置。
- **配置导入导出**：JSON 一键备份迁移。

## 适配的协议

| 类型 | 说明 | 请求/响应转换 |
|---|---|---|
| **OpenAI 兼容** | OpenAI、DeepSeek、Moonshot、通义、各类中转站、本地 vLLM/Ollama 的 OpenAI 端点 | 字节级透传，兼容性最好 |
| **Claude** | Anthropic Messages API (`/v1/messages`) | OpenAI ↔ Claude 双向转换，含流式增量 |
| **Gemini** | Google `generateContent` API | OpenAI ↔ Gemini 双向转换，含流式增量 |
| **自定义透传** | 任意私有网关 / Workers 中转 | 仅模型映射 + 自定义请求头，请求体原样透传 |

> 适配度设计：以 OpenAI 格式为"中间语言"。新增平台时，若它兼容 OpenAI 协议，直接选"OpenAI 兼容"类型即可；若协议特殊但请求体接近 OpenAI，用"自定义透传"配合请求头覆盖；若是 Claude/Gemini 这类标准但不同的协议，用内置适配器。

## 快速开始

1. 安装 APK 并打开。
2. 进入**渠道**标签，点右下角 `+` 添加渠道。**最省事的方式**：在最上方的**官方 API 预设**下拉里选一个平台（如"智谱 BigModel"、"DeepSeek"），地址、鉴权头、常见模型名会自动填好，你只需补上自己的 **API Key** 即可。
   - 若列表里没有你的平台，再手动填：**协议类型**按上游选（不确定选"OpenAI 兼容"）、**Base URL**（如 `https://api.deepseek.com/v1`）、**API Key**、**对外模型列表**（每行一个）。
   - **模型名映射**（可选）：如 `gpt-4o=gpt-4o-2024-08-06`。
   - **额外请求头** / **请求头模板**：对接自建或自有服务时，可加任意 Header 或套用标准客户端 UA 模板；支持 `{key}` 占位符，Cookie 也可在此自行填写。
3. 回到**总览**标签，点击**启动网关**。
4. 复制或扫码获取 API 地址（形如 `http://192.168.1.10:8317/v1`）。
5. 在任意 OpenAI 客户端中把 `base_url` 设为该地址，`api_key` 设为访问令牌（若设置）或任意值。

### 调用示例

```bash
curl http://<手机IP>:8317/v1/chat/completions \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <访问令牌>" \
  -d '{
    "model": "gpt-4o",
    "messages": [{"role": "user", "content": "你好"}],
    "stream": false
  }'
```

Python：

```python
from openai import OpenAI

client = OpenAI(
    base_url="http://<手机IP>:8317/v1",
    api_key="<访问令牌>",   # 未设令牌时填任意值
)
resp = client.chat.completions.create(
    model="gpt-4o",
    messages=[{"role": "user", "content": "你好"}],
)
print(resp.choices[0].message.content)
```

## API 端点

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/` | 服务信息（名称、版本、端口、渠道数） |
| GET | `/health` | 健康检查，返回 `ok` |
| GET | `/v1/models` | 聚合所有渠道的模型列表 |
| POST | `/v1/chat/completions` | 聊天补全（OpenAI 兼容） |
| POST | `/v1/embeddings` | 向量嵌入（转发给 OpenAI 兼容渠道） |

## 自定义透传渠道（适配非 OpenAI 格式的私有接口）

选「自定义透传」类型，可对接**你自己有权访问**、但请求/响应格式与 OpenAI 不同的服务（如自有网站/APP 内部接口）。三个核心配置：

**1. 请求体模板**（留空则原样透传 OpenAI 格式）
用变量把 OpenAI 请求映射成目标格式，支持：
- `{{model}}` 模型名（已做模型名映射）
- `{{messages}}` 完整 messages 数组 JSON
- `{{key}}` API Key
- `{{stream}}` true/false

示例（目标接口只要 `model` + `prompt` + `key`）：

```json
{"model":"{{model}}","prompt":{{messages}},"key":"{{key}}"}
```

**2. 响应内容路径**（留空则原样透传）
从目标响应 JSON 中按路径提取文本，自动组装成标准 OpenAI 响应。支持点号和数组下标：
- `data.text`
- `data[0].text`
- `choices[0].message.content`

**3. 请求方法 / 查询参数**
可改成 GET / PUT / PATCH 等；查询参数每行一条 `key=value`，拼到请求 URL。

**4. 签名配置（对接带签名校验的 APP / 网页接口）**

签名是**渠道的持久配置**，保存后一直生效，不需要每次重新生成或重建渠道。四个字段：

| 字段 | 说明 |
|---|---|
| 签名头名称 | 如 `X-Sign`、`sign`、`X-Signature`；留空则不签名 |
| 签名算法 | `md5` / `sha1` / `sha256` / `hmac-md5` / `hmac-sha1` / `hmac-sha256` |
| 签名内容模板 | 待签名字符串，支持 `{{timestamp}}` `{{nonce}}` `{{body}}` `{{model}}` `{{key}}` |
| 签名密钥 | HMAC 类算法必填；md5/sha 类可留空 |

示例（常见 APP 接口签名方式）：

```
签名头名称: X-Sign
签名算法:   hmac-sha256
签名内容模板: app_key={{key}}&timestamp={{timestamp}}&nonce={{nonce}}&data={{body}}
签名密钥:   你的 appSecret
```

每次请求会自动生成新的 `{{timestamp}}` / `{{nonce}}`，对模板插值后计算签名并注入请求头。

> 仅用于对接**你自己有权访问**的服务。本 App 不内置、也不提供任何第三方私有接口的逆向、签名破解或风控绕过能力。

## 常见问题

**手机和电脑连不上？**
- 确认在同一 Wi-Fi；手机"设置"里已开启"允许局域网访问"。
- 部分路由器开启了 AP 隔离，需关闭。
- 关掉手机热点/VPN 可能造成网段不一致。

**锁屏后网关失效？**
- App 使用前台服务 + WakeLock + WifiLock。请允许后台运行、忽略电池优化。
- 国产系统（MIUI/EMUI 等）需手动把 App 加入"后台高保活"白名单。

**端口被占用？**
- 在设置里换一个端口（如 8318），重启网关。

**某个平台接不上？**
- 先看"官方 API 预设"里有没有该平台，有则一键填充后用你自己的官方 API Key 测试。
- 内置预设只收录**提供公开 API** 的平台。若某服务没有公开 API，本 App 不提供对其私有接口的逆向或破解；如你**本人有权访问**该服务、且了解其请求格式，可用「自定义透传」+ 请求体模板 / 响应映射自行适配（见上文）。
- 手动配置时用"OpenAI 兼容"类型 + 正确的 Base URL 和鉴权头测试。
- Claude 注意鉴权头为 `x-api-key: {key}`，且需要 `anthropic-version`（选 Claude 类型会自动带）。
- Gemini 的 key 走 URL 查询参数，选 Gemini 类型即可。
- 在"日志"标签查看上游返回的状态码和错误信息。

## 构建

项目使用 Gradle + Android Gradle Plugin，需 JDK 17 与 Android SDK 35。

```bash
./gradlew assembleDebug      # 生成 app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease    # 生成未签名 release APK
```

推送到 GitHub 后，`.github/workflows/build.yml` 会自动构建并在 Actions 产物中提供 APK；打 `v*` 标签会自动创建 Release。

## 合规声明

本 App 是一个**通用的、渠道由使用者自行配置的 API 网关框架**，本身不内置任何第三方平台的账号、凭证或接口破解逻辑。使用者应确保：

- 有权访问所配置的每一个上游服务；
- 遵守各平台的服务条款与使用政策；
- 不用于绕过付费、盗用他人凭证或任何违法用途。

作者不对使用者的任何滥用行为承担责任。
