# Aria

[English](README.md)

AI 辅助长篇小说写作，Android 应用。

问题很直接：聊天式 AI 写作会遗忘早期指令、偏离大纲，作品越长越拉跨。把十万字塞进一个 prompt 根本不是解法——只会烧 API 预算。

Aria 的做法：每个代理只拿到它需要的上下文。大纲代理看构思，场景代理看场景梗概、相关设定条目和上一场景的结尾。不多不少。

## 工作流

```
小说构思
  → 大纲代理生成全书大纲
  → 你编辑大纲
  → 章节梗概代理拆分场景
  → 你编辑场景计划
  → 场景代理写正文
  → 审阅代理对照梗概检查
  → 你接受、编辑或重试
  → 连贯性代理提取事实写入设定集
  → 下一场景使用过滤后的设定集上下文
```

每一层都可编辑。重新生成需要确认。你的编辑永远优先。

## 已实现

- **书库** — 创建小说，设定类型/构思/主题
- **大纲** — 生成、编辑、保存全书大纲
- **草稿** — 章节梗概、场景拆分、流式场景正文、生成队列
- **设定集** — 角色、地点、时间线、世界规则、主题。用户写的条目在合并时保留。冲突会标出来让你解决。
- **审阅** — 评分、问题、修复建议。接受、带反馈重试或标记通过。未通过的审阅不会更新设定集。
- **历史** — 版本快照、恢复/删除、token 用量和费用追踪
- **导出** — Markdown、纯文本或 EPUB，通过 Android 分享
- **设置** — 服务商（Anthropic/兼容 OpenAI 的接口）、API 密钥、Base URL、每个代理的模型选择。密钥静态加密。

## 架构

```
:core:model   纯 Kotlin 领域模型，无 Android 依赖
:core:llm     与服务商无关的 LLM API 类型和客户端
:core:prompt  提示词模板和输出解析
:core:agent   代理包装器、设定集过滤/合并、流水线
:data         Room + DataStore，仓库层
:app          Compose UI + ViewModel
```

### 代理

| 代理 | 职责 |
|---|---|
| OutlineAgent | 全书大纲和章节目录 |
| ChapterSynopsisAgent | 带目标和过渡的场景拆分 |
| SceneExpansionAgent | 流式生成场景正文 |
| ReviewAgent | 对照梗概检查合规性 |
| ContinuityAgent | 提取事实写入设定集 |
| RollingSummaryAgent | 每章连贯性摘要 |

### LLM 服务商

- Anthropic Messages API
- OpenAI Chat Completions API（及兼容接口）
- 每个代理独立选择模型

## 技术栈

Kotlin · Jetpack Compose · Material 3 · Room · DataStore · OkHttp · JUnit 5 · MockK

无 DI 框架。手动 `AppContainer`。

## 构建

```bash
./gradlew :app:assembleDebug        # 调试 APK
./gradlew build                     # 完整构建 + 测试
./gradlew :core:agent:test          # 针对性测试
```

Android SDK 35 · JDK 17 · Kotlin 2.1.21

## 当前状态

核心流水线端到端可用。编辑、审阅/重试、流式生成、导出、版本历史和生成队列均已实现。

正在做：移动端 UI 打磨、进程死亡安全的后台生成重试。

## 许可

专有软件。保留所有权利。