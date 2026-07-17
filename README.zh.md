# AI-Git-Bot

[![License: MIT](https://img.shields.io/github/license/tmseidel/ai-git-bot)](LICENSE)
[![Docker Pulls](https://img.shields.io/docker/pulls/tmseidel/ai-git-bot)](https://hub.docker.com/r/tmseidel/ai-git-bot)
[![GitHub release](https://img.shields.io/github/v/release/tmseidel/ai-git-bot)](https://github.com/tmseidel/ai-git-bot/releases)
[![GitHub stars](https://img.shields.io/github/stars/tmseidel/ai-git-bot)](https://github.com/tmseidel/ai-git-bot/stargazers)
[![GitHub issues](https://img.shields.io/github/issues/tmseidel/ai-git-bot)](https://github.com/tmseidel/ai-git-bot/issues)

🌐 语言版本：**English** · **中文** · [한국어](README.ko.md) · [日本語](README.ja.md)

> **面向 Git 仓库的自托管 AI 工作流自动化平台。**

- 🔍 审查拉取请求
- 🧪 生成测试
- ✏️ 改进 issue
- 🤖 将 issue 转化为拉取请求
- 🎬 创建和运行 E2E 测试
- 📝 让文档与代码保持同步
- 🌍 让翻译在各语言文件间保持同步
- 💬 在代码审查中回答问题

💡 已经在使用 GitHub Copilot？

> **太好了。**<br>
> Copilot 帮助开发者更快编写代码。AI-Git-Bot 帮助团队自动化审查、测试、issue 和拉取请求工作流。<br>
> 许多团队会同时使用这两个工具。

## 🔌 任意 AI 提供商与任意 Git 平台的组合

| AI 提供商 | Git 平台 |
|---|---|
| **Anthropic**（Claude） | **Gitea**（自托管） |
| **OpenAI**（+ OpenAI 兼容 API） | **GitHub** / **GitHub Enterprise** |
| **Google AI / Gemini** | **GitLab**（gitlab.com 与自管理） |
| **Ollama**（本地 LLM） | **Bitbucket Cloud** |
| **llama.cpp**（本地 GGUF 模型） | |

与大多数 AI 编码工具不同，AI-Git-Bot 不绑定于特定的 Git 平台或 AI 提供商。

**完全支持自托管。您的代码可以保留在基础设施内。**

<p align="center">
  <img src="doc/images/dashboard_ai_git_bot.PNG" alt="AI-Git-Bot 控制台" width="800"/>
</p>

---

## 为什么这个项目存在

每个工程团队都有一些公认应该发生的事情：

- 拉取请求应该被仔细审查
- Bug 应该添加回归测试
- Issue 应该有验收标准
- 文档应该与代码保持同步更新
- 预览环境应该被清理
- 小的维护工单最终应该被实现

没有人不同意这些想法。问题是这些任务：
* 不舒服。
* 重复性劳动。
* 难以确定优先级。
* 当截止日期临近时容易被推迟。

AI-Git-Bot 的存在是为了将这些工程杂务转化为在你的 Git 平台内自动执行的、可重复的工作流。

* 无需新的开发流程。
* 无需迁移项目。
* 无需供应商锁定。

通过自动化实现更好的工程纪律。

---

## AI-Git-Bot 为谁而设？

### 🏢 运行 Gitea，缺少现代化的 AI 工具链？

许多团队选择 Gitea 是因为他们希望掌控自己的源代码和基础设施。

然而，大多数 AI 产品主要关注 GitHub。

AI-Git-Bot 将以下内容直接带到 Gitea：

- AI 代码审查
- AI 生成测试
- AI Issue 编写
- AI 编码工作流
- AI 文档维护
- 交互式 PR 讨论

使用 OpenAI、Claude、Gemini 或本地 Ollama 模型，无需更改开发者的现有工作流。

👉 思考：**「Gitea 的 Copilot 风格工作流自动化。」**

---

### 🔒 需要 AI 但不能将源代码发送到外部服务？

许多组织由于合规性、隐私或合同要求，无法使用仅云端的 AI 产品。

AI-Git-Bot 支持：

- Ollama
- llama.cpp
- 自托管 Git 平台
- 私有网络
- 与提供商无关的架构

源代码、提示词、凭证和模型始终在你的控制之下。

👉 思考：**「不将仓库交给 SaaS 供应商的 AI 工作流自动化。」**

---

### 🚀 维护仓库的工程开销过大？

每个仓库都会积累工程杂务：

- 审查
- 测试
- 文档
- 验收标准
- 后续修复

AI-Git-Bot 将这些活动转化为你团队已经在产生的事件触发的工作流：

- 拉取请求打开
- 拉取请求重新打开
- 新提交推送 (每个 bot 可选择启用)
- 请求审查者
- Issue 被分配
- `@bot` 被提及

👉 思考：**「那个永远不会忘记无聊但重要工作的 AI 队友。」**

---

## 实际效果演示

AI-Git-Bot 存在于你的开发者已经工作的地方：

- GitHub
- GitHub Enterprise
- Gitea
- GitLab
- Bitbucket Cloud

1. 给它分配一个审查。
2. 给它分配一个 issue。
3. 在评论中提及它。
4. Bot 直接在 Git 平台内响应。

无需额外仪表板。

无需浏览器扩展。

无需 Slack bot 来管理。

> 🎥 **观看 PR 工作流实际运行：** [AI-Git-Bot — YouTube 上的 PR workflow walkthrough](https://www.youtube.com/watch?v=MjFmZHGIO-w)

---

## 截图

### 拉取请求审查

AI-Git-Bot 审查拉取请求，并在 diff 上留下可操作的内联反馈。

<details>
<summary>📸 截图：跨平台的审查、对话和编码代理</summary>

**Gitea：** <img src="doc/screenshots/gitea/screenshot_initial_code_review.png" alt="Gitea Code Review" width="600"/>

**GitHub：** <img src="doc/screenshots/github/github_code_review_with_comment.png" alt="GitHub Code Review" width="600"/>

**GitLab：** <img src="doc/screenshots/gitlab/gitlab-pull-request-with-code-review.png" alt="GitLab Code Review" width="600"/>

**Bitbucket：** <img src="doc/screenshots/bitbucket/bitbucket-code-review.png" alt="Bitbucket Code Review" width="600"/>

**编码代理（GitHub）：** <img src="doc/screenshots/github/github_issue_agent_code_implementation.png" alt="GitHub Agent" width="600"/>

</details>

---

### 交互式讨论

在拉取请求讨论的任何地方提及 bot。

```text
@bot 请解释为什么这个实现可能会失败
```

Bot 直接在线程中回答，并保持对话上下文。

<details>
<summary>📸 截图：Gitea 内联评论</summary>

**Gitea：** <img src="doc/screenshots/gitea/screenshot_code_review_with_inline_comment.png" alt="Gitea Inline Comments" width="600"/>
</details>

---

### E2E 测试生成

将一个 PR 分配给 bot，它可以为变更生成 Playwright 测试套件，部署预览环境，在预览上运行测试，并将结果发布回 PR。

<details>
<summary>📸 截图：PR 中的 E2E 测试生成</summary>

**GitLab：** <img src="doc/screenshots/pr-workflow/gitea-pr-with-e2e-test-run.png" alt="E2E Tests in a Pull-Request" width="600"/>
</details>

---

### 编码代理

将一个 issue 分配给编码 bot，它可以代表你创建一个实现拉取请求。

<details>
<summary>📸 截图：Issue 实现代理</summary>

**GitLab：** <img src="doc/screenshots/gitlab/gitlab_issue_agent_code_implementation.png" alt="Coding Agent in Gitlab" width="600"/>
</details>

---

## ✨ 它能做什么？

| 工作流 | 触发 | 结果 |
|-----------|----------|------|
| **[PR 审查](doc/PR_WORKFLOWS_REVIEW.md)** | PR 打开或审查重新请求 | 审查评论和发现 |
| **[交互式问答](doc/PR_WORKFLOWS_REVIEW.md)** | PR 评论中的 `@bot` 提及 | 上下文感知对话 |
| **[Issue → 代码](doc/CODING_AGENT.md)** | Issue 分配给编码 bot | 拉取请求 |
| **[Issue → 改进](doc/WRITER_AGENT.md)** | Issue 分配给写作 bot | 带验收标准的结构化 issue |
| **[单元测试生成](doc/PR_WORKFLOWS_UNIT_TEST.md)** | PR 打开或命令触发 | 提交到分支的生成测试 |
| **[全栈 QA](doc/PR_WORKFLOWS_E2E.md)** | PR 打开 | 在预览环境中执行的 Playwright 套件 |
| **[README 同步](doc/PR_WORKFLOWS_README_SYNC.md)** | PR 打开或命令触发 | 文档随代码变更同步更新 |
| **[i18n 覆盖](doc/PR_WORKFLOWS_I18N_COVERAGE.md)** | PR 打开或命令触发 | 在各语言文件间起草缺失的翻译 |
| **[PR 重新审查](doc/PR_WORKFLOWS_REVIEW.md)** | 强制推送或审查请求 | 更新的分析 |
| **[工作流自动化](doc/PR_WORKFLOWS.md)** | Git 事件 | 工程杂务自动化 |

---

## AI-Git-Bot 的不同之处

许多 AI 开发工具专注于帮助开发者编写代码。

AI-Git-Bot 专注于帮助团队更一致地交付软件。

不只是回答：

> "我们如何更快地写代码？"

AI-Git-Bot 试图回答：

> "我们如何确保重要的工程工作不被跳过？"

例如：

- 审查每个拉取请求
- 添加回归测试
- 维护 E2E 覆盖
- 让文档与代码保持同步
- 让翻译在各语言文件间保持同步
- 提高 issue 质量
- 验证部署
- 自动化重复工程任务

---

## 为什么不直接用 Copilot？

GitHub Copilot 非常出色。

事实上，许多团队会一起使用这两个工具。

一个现实的工作流是这样的：

```text
开发者用 Copilot 写代码
           ↓
      拉取请求打开
           ↓
   AI-Git-Bot 审查它
           ↓
   AI-Git-Bot 生成测试
           ↓
 AI-Git-Bot 更新文档
           ↓
 AI-Git-Bot 验证部署
           ↓
      结果发布
```

Copilot 帮助开发者更快编写代码。

AI-Git-Bot 帮助团队自动化代码周围的工作。

这些目标互补。

---

## 当前工作流

### 🔍 拉取请求审查

自动审查拉取请求并提供：

- 摘要发现
- 内联评论
- 建议改进
- 后续讨论

---

### 🤖 Issue → 拉取请求

将编码 bot 分配给一个 issue。

Bot 执行：

1. 读取 issue
2. 克隆仓库
3. 实现变更
4. 运行项目验证
5. 打开拉取请求

---

### ✏️ Issue 改进

将写作 bot 分配给一个 issue。

Bot 将粗略的需求转化为结构化的工程工作项：

- 背景
- 需求
- 验收标准
- 实现说明

---

### 🧪 单元测试生成

基于拉取请求变更自动生成白盒单元测试。

测试在提交前可以使用项目自身的工具进行验证。

---

### 🎬 全栈 QA

全栈 QA 工作流可以：

1. 生成 Playwright 测试
2. 部署预览环境
3. 执行套件
4. 将结果发布回拉取请求
5. PR 关闭时清理资源

---

### 📝 README 同步

让项目文档与拉取请求变更的代码保持一致。

该工作流会检测 PR 是否使 README 或其他 Markdown 文档变得不准确或过时，
然后在配置的范围内更新、新增或删除受影响的文档文件，并发布一条简短摘要。
仅支持 Markdown，所有被修改的文件都限定在你配置的文档模式范围内。在 PR
打开时或通过 `@bot regenerate-readme <指示>` 触发。

---

### 🌍 i18n 覆盖

当拉取请求变更面向用户的字符串时，让各语言文件之间的翻译保持同步。

该工作流会将每个语言文件与可配置的基准语言进行比较，当某个翻译缺少基准
语言定义的键（新增或变更的字符串），或仍保留基准语言已删除的键时，会按
语言起草缺失的翻译并移除过时的键。同时支持 `messages_*.properties` 和
`i18n/*.json` 文件；所有被修改的文件都限定在你配置的模式范围内。在 PR
打开时或通过 `@bot regenerate-i18n <指示>` 触发。

---

## 快速开始

使用 Docker Compose 在本地运行 AI-Git-Bot。

```bash
git clone https://github.com/tmseidel/ai-git-bot.git
cd ai-git-bot
docker compose up --build -d
```

然后：

1. 打开 `http://localhost:8080`
2. 创建管理员账号
3. 创建 AI 集成
4. 创建 Git 集成
5. 创建 Bot
6. 配置 webhook
7. 完成

---

## 选择你的路径

### 👀 只是在评估项目？

从这里开始：

- **[The Pitch](doc/pitch/PITCH.md)**
- **[架构概览](doc/ARCHITECTURE.md)**

---

### 🏢 正在运行 Gitea？

从这里开始：

- **[Gitea 设置指南](doc/GITEA_SETUP.md)**
- **[快速开始](doc/USING_THE_BOT.md)**

---

### 🔒 寻找自托管 AI？

从这里开始：

- **[部署指南](doc/DEPLOYMENT.md)**
- **[Ollama 集成指南](doc/OLLAMA.md)**（或使用 OpenAI 兼容 API 的 vLLM）

---

### 🤖 准备好自动化工作流？

从这里开始：

- **[用户指南](doc/USER_GUIDE.md)**
- **[工作流文档](doc/PR_WORKFLOWS.md)**

---

### 🧑‍💻 想要贡献？

从这里开始：

- **[本地开发指南](doc/LOCAL_DEVELOPMENT.md)**
- **[架构文档](doc/ARCHITECTURE.md)**

---

## 📚 文档

文档在 **[Documentation Hub](doc/README.md)** 中按受众组织：

| 您是… | 从这里开始 |
|---|---|
| 👤 **用户** — bot 已配置好，只需使用 Git 平台 | [使用 Bot](doc/USING_THE_BOT.md) |
| 🛠️ **管理员** — 负责配置软件、bot 和工作流 | [部署](doc/DEPLOYMENT.md) · [管理指南](doc/USER_GUIDE.md) |
| 🧪 **测试者** — 想安全地试用功能 | [测试指南](doc/TESTING_GUIDE.md) |
| 💻 **开发者** — 处理代码 | [本地开发](doc/LOCAL_DEVELOPMENT.md) · [架构](doc/ARCHITECTURE.md) |

---

## 项目成熟度

### 生产就绪

* GitHub
* GitHub Enterprise
* Gitea

### 🧪 欢迎社区反馈

* GitLab
* Bitbucket Cloud

### 实验性工作流

⚠️ 全栈 QA / E2E 自动化

项目附带广泛的系统测试和示例环境，使验证和故障排除更容易。

Bug 报告始终欢迎。

---

## 技术亮点

- 🔒 AES-256-GCM 密钥加密
- 🤖 多提供商 AI 支持
- 🏢 多平台 Git 支持
- 🧠 本地 LLM 支持
- 🔌 MCP 集成
- 🧪 系统测试工作流
- 🐳 Docker 优先部署
- 🌍 端到端自托管

---

## 社区

* ⭐ 超过 100 个 GitHub stars
* 🚀 超过 15 个版本发布
* 🐳 Docker 镜像可用
* 🌍 用户遍布 GitHub、Gitea、GitLab 和 Bitbucket

## 开始

```bash
docker pull tmseidel/ai-git-bot:latest
```

---

## 总结

AI-Git-Bot 不是另一个编码助手。

它是软件交付工作流的自托管自动化层。

如果你的团队已经知道什么是好的工程实践——但难以持续执行——AI-Git-Bot 正是为此而构建。

连接一个 bot。

让杂务自动处理。

🚀 Happy shipping.

## 许可证

[MIT](LICENSE)
