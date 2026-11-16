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
- **多协议适配**：OpenAI 兼容 / Claude (Anthropic) / Gemini (Google) / 自定义透传。
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
2. 进入**渠道**标签，点右下角 `+` 添加渠道：
   - **名称**：随便起，便于识别。
   - **协议类型**：按上游选择（不确定就先选"OpenAI 兼容"）。
   - **Base URL**：上游根地址，如 `https://api.deepseek.com/v1`。
   - **API Key**：上游密钥（也可留空，用下面"鉴权头"完全自定义）。
   - **对外模型列表**：你希望暴露给客户端的模型名，每行一个。
   - **模型名映射**（可选）：如 `gpt-4o=gpt-4o-2024-08-06`。
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
- 先用"OpenAI 兼容"类型 + 正确的 Base URL 和鉴权头测试。
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
