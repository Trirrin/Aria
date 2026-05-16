# Aria (咏叹)

[English](README.md)

一款 AI 辅助长篇小说写作的 Android 应用——不把写作流程变成脆弱的聊天记录。

咏叹是一个结构化写作工作台，围绕有界上下文窗口、层次化写作代理、用户可编辑的故事工件和持久的《小说设定集》构建。

## 为什么是咏叹

基于聊天的 AI 写作会遗忘早期指令、偏离大纲，并且随着作品变长而退化。把十万字塞进一个 prompt 不是解决方案——那是预算灾难。

咏叹走了另一条路：每个代理只接收它需要的上下文。大纲代理看到构思，场景代理看到场景梗概、相关的设定条目和上一场景结尾。不多不少。

## 核心工作流

```
小说构思
  → 大纲代理生成全书大纲
  → 你审阅并编辑大纲
  → 章节梗概代理创建场景拆分
  → 你审阅并编辑章节计划
  → 场景扩展代理撰写正文
  → 审阅代理检查是否符合梗概
  → 你接受、编辑或重试
  → 连贯性代理提取事实写入小说设定集
  → 下一场景使用过滤后的设定集上下文
```

每一层都可编辑。每次重新生成都需确认。你的文字永远优先。

## 功能

**书库** — 创建小说，设定类型/构思/主题，管理你的书架。

**大纲** — 生成包含前提、主要情节点、角色弧和章节简述的全书大纲。自由编辑并保存。

**草稿** — 选择章节和场景。生成并编辑章节梗概和场景拆分。实时流式生成场景正文到编辑器。按章节或从任意场景到末尾排队生成。

**设定集** — 维护持久化的《小说设定集》，包含角色、地点、时间线事件、世界规则和主题。用户创作的设定条目在合并时始终优先于提取的事实。可视化解决冲突。

**审阅** — 每次生成输出都会获得带评分、问题和建议修复的结构化审阅。接受、带反馈重试、手动编辑或标记为已批准。未通过的审阅会阻止更新设定集。

**历史** — 大纲、梗概、场景正文和设定集的版本快照。恢复或删除历史版本。按服务商、模型和代理追踪 token 用量和费用。

**导出** — 通过 Android 分享意图导出为 Markdown、纯文本或 EPUB。

**设置** — 配置服务商（Anthropic 或兼容 OpenAI 的接口）、API 密钥、Base URL 以及每个代理的模型选择。API 密钥静态加密存储。

## 架构

```
:core:model   纯 Kotlin 领域模型——无 Android 依赖
:core:llm     与服务商无关的 LLM API 类型和客户端
:core:prompt  提示词模板和输出解析
:core:agent   代理包装器、设定集过滤、设定集合并、流水线编排
:data         Room 持久化、DataStore 设置、仓库层
:app          Compose UI 和 Android ViewModel
```

### 代理流水线

| 代理 | 职责 |
|---|---|
| OutlineAgent | 全书大纲和章节目录 |
| ChapterSynopsisAgent | 带目标和过渡的场景拆分 |
| SceneExpansionAgent | 流式生成场景正文 |
| ReviewAgent | 对照梗概检查合规性，带评分和修复建议 |
| ContinuityAgent | 为小说设定集提取事实 |
| RollingSummaryAgent | 每章的连贯性摘要 |

### LLM 服务商

- **Anthropic** Messages API
- **OpenAI** Chat Completions API（及兼容 OpenAI 的服务商）
- 每个代理独立选择模型

## 技术栈

| 层级 | 技术 |
|---|---|
| UI | Kotlin + Jetpack Compose + Material 3 |
| 持久化 | Room + DataStore Preferences |
| HTTP | OkHttp |
| 依赖注入 | 手动 `AppContainer`（无框架开销） |
| 测试 | JUnit 5 / JUnit 4, MockK, Turbine, MockWebServer, Robolectric |

## 构建

```bash
# 调试版 APK
./gradlew :app:assembleDebug

# 完整构建（所有模块 + 测试）
./gradlew build

# 针对性测试
./gradlew :core:agent:test
./gradlew :core:llm:test
./gradlew :core:prompt:test
```

环境要求：Android SDK 35，JDK 17，Kotlin 2.1.21。

## 项目状态

核心写作流水线已完整可用：大纲 → 梗概 → 场景正文 → 审阅 → 设定集。人工编辑、审阅/重试、流式生成、导出、版本历史和生成队列均已实现。

当前重点：
- 移动端 UI 深度打磨
- 进程死亡安全的生成任务后台重试/恢复

详见 [`Plan.md`](Plan.md) 了解完整路线图。

## 许可

专有软件。保留所有权利。
