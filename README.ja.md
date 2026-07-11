# AI-Git-Bot

[![License: MIT](https://img.shields.io/github/license/tmseidel/ai-git-bot)](LICENSE)
[![Docker Pulls](https://img.shields.io/docker/pulls/tmseidel/ai-git-bot)](https://hub.docker.com/r/tmseidel/ai-git-bot)
[![GitHub release](https://img.shields.io/github/v/release/tmseidel/ai-git-bot)](https://github.com/tmseidel/ai-git-bot/releases)
[![GitHub stars](https://img.shields.io/github/stars/tmseidel/ai-git-bot)](https://github.com/tmseidel/ai-git-bot/stargazers)
[![GitHub issues](https://img.shields.io/github/issues/tmseidel/ai-git-bot)](https://github.com/tmseidel/ai-git-bot/issues)

🌐 言語選択：**English** · [中文](README.zh.md) · [한국어](README.ko.md) · **日本語**

> **Git リポジトリのためのセルフホスト型 AI ワークフロー自動化プラットフォーム。**

- 🔍 プルリクエストをレビュー
- 🧪 テストを生成
- ✏️ イシューを改善
- 🤖 イシューからプルリクエストを作成
- 🎬 E2E テストの作成と実行
- 💬 コードレビュー内で質問に回答

💡 GitHub Copilot をすでに使っていますか？

> **素晴らしい。**<br>
> Copilot は開発者のコード記述を高速化します。AI-Git-Bot はチームがレビュー、テスト、イシュー、プルリクエストワークフローを自動化するのに役立ちます。<br>
> 多くのチームは両方を併用しています。

## 🔌 任意の AI プロバイダと任意の Git プラットフォームの組み合わせ

|| AI プロバイダ | Git プラットフォーム |
||---|---|
|| **Anthropic** (Claude) | **Gitea** (セルフホスト) |
|| **OpenAI** (+ OpenAI 互換 API) | **GitHub** / **GitHub Enterprise** |
|| **Google AI / Gemini** | **GitLab** (gitlab.com & セルフマネージド) |
|| **Ollama** (ローカル LLM) | **Bitbucket Cloud** |
|| **llama.cpp** (ローカル GGUF モデル) | |

ほとんどの AI コーディングツールとは異なり、AI-Git-Bot は特定の Git プラットフォームや AI プロバイダに縛られません。

**完全にセルフホスト可能。あなたのコードはインフラ内に留まります。**

<p align="center">
  <img src="doc/images/dashboard_ai_git_bot.PNG" alt="AI-Git-Bot ダッシュボード" width="800"/>
</p>

---

## なぜこのプロジェクトが存在するのか

すべてのエンジニアリングチームには、起こるべきだと誰もが知っていることがあります：

- プルリクエストは慎重にレビューされるべき
- バグには回帰テストを追加するべき
- イシューには受け入れ基準があるべき
- プレビュー環境はクリーンアップされるべき
- 小さな保守チケットはいつか実装されるべき

これらのアイデアに反対する人はいません。問題は、それらが：
* 快適ではないことです。
* 繰り返し作業です。
* 優先順位をつけにくいことです。
* 期限が近づくと簡単に後回しにされてしまうことです。

AI-Git-Bot は、これらのエンジニアリングの雑務をあなたの Git プラットフォーム内で自動的に繰り返せるワークフローに変えるために存在しています。

* 新しい開発プロセスは不要。
* 移行プロジェクトは不要。
* ベンダーロックインはなし。

自動化によるより良いエンジニアリング衛生環境を実現します。

---

## AI-Git-Bot は誰のためのもの？

### 🏢 Gitea を運用していて、最新の AI ツールリングが不足している？

多くのチームは、ソースコードとインフラストラクチャの所有権を求めるために Gitea を選択しています。

しかし、ほとんどの AI 製品は主に GitHub に焦点を当てています。

AI-Git-Bot は以下を Gitea に直接もたらします：

- AI コードレビュー
- AI 生成テスト
- AI イシュー作成
- AI コーディングワークフロー
- 対話型 PR ディスカッション

直接 Gitea に届けます。

OpenAI、Claude、Gemini、またはローカルの Ollama モデルを使用しながら、開発者の既存のワークフローを変更せずに済みます。

👉 考え方：**「Gitea 用の Copilot スタイル ワークフロー自動化。」**

---

### 🔒 AI を使いたいけれど、ソースコードを外部サービスに送信できない？

多くの組織は、コンプライアンス、プライバシー、または契約上の要件により、クラウド専用の AI 製品を使用できません。

AI-Git-Bot は以下をサポートします：

- Ollama
- llama.cpp
- セルフホスト型 Git プラットフォーム
- プライベートネットワーク
- プロバイダー非依存アーキテクチャ

ソースコード、プロンプト、資格情報、モデルはすべてあなたの管理下に保たれます。

👉 考え方：**「リポジトリを SaaS ベンダーに手渡さず、AI ワークフロー自動化を。」**

---

### 🚀 多くのエンジニアリングオーバーヘッドでリポジトリを維持している？

すべてのリポジトリにはエンジニアリングの雑務が蓄積します：

- レビュー
- テスト
- ドキュメント
- 受け入れ基準
- 継続的な修正

AI-Git-Bot は、チームがすでに生成しているイベントでトリガーされる繰り返し可能なワークフローに変えます：

- プルリクエストがオープン
- レビュアーが依頼
- イシューが割り当て
- `@bot` がメンション

👉 考え方：**「退屈だが重要な作業を絶対に忘れない、AI チームメイト。」**

---

## アクションで見る

AI-Git-Bot は開発者がすでに作業している場所に組み込まれています：

- GitHub
- GitHub Enterprise
- Gitea
- GitLab
- Bitbucket Cloud

1. レビューを割り当てる
2. イシューを委任する
3. コメントでメンションする
4. Bot はあなたの Git プラットフォーム内で直接反応する

追加のダッシュボードは不要。

ブラウザ拡張機能は不要。

Slack ボットの管理も不要。

> 🎥 **PR ワークフローが実際に動く様子を見る:** [AI-Git-Bot — PR workflow walkthrough on YouTube](https://www.youtube.com/watch?v=MjFmZHGIO-w)

---

## スクリーンショット

### プルリクエストレビュー

AI-Git-Bot はプルリクエストをレビューし、差分上に直接的なアクション可能なフィードバックを残します。

<details>
<summary>📸 スクリーンショット: プラットフォーム全体のレビュー、会話、コーディングエージェント</summary>

**Gitea:** <img src="doc/screenshots/gitea/screenshot_initial_code_review.png" alt="Gitea Code Review" width="600"/>

**GitHub:** <img src="doc/screenshots/github/github_code_review_with_comment.png" alt="GitHub Code Review" width="600"/>

**GitLab:** <img src="doc/screenshots/gitlab/gitlab-pull-request-with-code-review.png" alt="GitLab Code Review" width="600"/>

**Bitbucket:** <img src="doc/screenshots/bitbucket/bitbucket-code-review.png" alt="Bitbucket Code Review" width="600"/>

**コーディングエージェント (GitHub):** <img src="doc/screenshots/github/github_issue_agent_code_implementation.png" alt="GitHub Agent" width="600"/>

</details>

---

### インタラクティブなディスカッション

プルリクエストの議論のどこでも bot をメンションできます。

```text
@bot この実装がなぜ失敗する可能性があるか説明して
```

Bot はスレッド内で直接回答し、会話のコンテキストを維持します。

<details>
<summary>📸 スクリーンショット: Gitea のインラインコメント</summary>

**Gitea:** <img src="doc/screenshots/gitea/screenshot_code_review_with_inline_comment.png" alt="Gitea Inline Comments" width="600"/>
</details>

---

### E2E テスト生成

PR を bot に割り当てると、変更に対する Playwright テストスイートを生成し、プレビュー環境をデプロイし、テストを実行して結果を PR に投稿できます。

<details>
<summary>📸 スクリーンショット: PR における E2E テスト生成</summary>

**GitLab:** <img src="doc/screenshots/pr-workflow/gitea-pr-with-e2e-test-run.png" alt="E2E Tests in a Pull-Request" width="600"/>
</details>

---

### コーディングエージェント

イシューをコーディングボットに割り当てると、変更を実装するプルリクエストを作成できます。

<details>
<summary>📸 スクリーンショット: イシュー実装エージェント</summary>

**GitLab:** <img src="doc/screenshots/gitlab/gitlab_issue_agent_code_implementation.png" alt="Coding Agent in Gitlab" width="600"/>
</details>

---

## ✨ 何ができるのか

|| ワークフロー | トリガー | 結果 |
||-----------|----------|------|
|| **PR レビュー** | PR オープンまたはレビュー再依頼 | レビューコメントと調査結果 |
|| **インタラクティブ Q&A** | PR コメントでの `@bot` メンション | コンテキスト対応会話 |
|| **イシュー → コード** | イシューをコーディングボットに割り当て | プルリクエスト |
|| **イシュー → 改善** | イシューをライターボットに割り当て | 受け入れ基準付き構造化イシュー |
|| **ユニットテスト生成** | PR オープンまたはコマンド | ブランチにコミットされた生成テスト |
|| **フルスタック QA** | PR オープン | プレビュー環境で実行された Playwright スイート |
|| **PR 再レビュー** | フォースプッシュまたはレビュー依頼 | 更新された分析 |
|| **ワークフロー自動化** | Git イベント | エンジニアリング作業の自動化 |

---

## AI-Git-Bot が違う理由

多くの AI 開発ツールは、開発者のコード作成支援に焦点を当てています。

AI-Git-Bot は、チームが一貫性を持ってソフトウェアを納品できるよう支援することに焦点を当てています。

単に以下だけを考えるのではなく：

> 「どうやってコードを早く書けるようにするか？」

AI-Git-Bot は以下を考える取り組みをしています：

> 「どうやって重要なエンジニアリング作業がスキップされないようにするか？」

具体例は以下の通りです：

- すべてのプルリクエストをレビュー
- 回帰テストを追加
- E2E カバレッジを維持
- イシュー品質を向上
- デプロイメントを検証
- 繰り返しエンジニアリングタスクを自動化

---

## なぜ Copilot だけではないのか

GitHub Copilot は優れています。

実際、多くのチームは両方を併用しています。

現実的なワークフローは以下のようになります：

```text
開発者が Copilot でコード作成
           ↓
      プルリクエストオープン
           ↓
   AI-Git-Bot がレビュー
           ↓
   AI-Git-Bot がテスト生成
           ↓
 AI-Git-Bot がデプロイメント検証
           ↓
      結果の共有
```

Copilot は開発者のコード作成を高速化します。

AI-Git-Bot はコードを取り囲む作業の自動化をチームに提供します。

これらの目的は互いに補完し合います。

---

## 現在のワークフロー

### 🔍 プルリクエストレビュー

プルリクエストを自動レビューし、以下を提供：

- 要約フィードバック
- インラインコメント
- 改善提案
- 継続的なディスカッション

---

### 🤖 イシュー → プルリクエスト

コーディングボットをイシューに割り当て。

Bot は以下を実行：

1. イシューを読む
2. リポジトリをクローン
3. 変更を実装
4. プロジェクト検証を実行
5. プルリクエストをオープン

---

### ✏️ イシュー改善

ライターボットをイシューに割り当て。

Bot はざっとした要件を構造化されたエンジニアリング作業項目に変換：

- 背景
- 要件
- 受け入れ基準
- 実装ノート

---

### 🧪 ユニットテスト生成

プルリクエストの変更に基づいてホワイトボックスユニットテストを自動生成。

テストはコミット前にプロジェクト自体のツールで検証可能。

---

### 🎬 フルスタック QA

フルスタック QA ワークフローは以下を実行：

1. Playwright テストを生成
2. プレビュー環境をデプロイ
3. スイートを実行
4. 結果をプルリクエストに公開
5. PR 閉じ時にリソースをクリーンアップ

---

## クイックスタート

Docker Compose で AI-Git-Bot をローカルに実行。

```bash
git clone https://github.com/tmseidel/ai-git-bot.git
cd ai-git-bot
docker compose up --build -d
```

その後：

1. `http://localhost:8080` を開く
2. 管理者アカウントを作成
3. AI 統合を作成
4. Git 統合を作成
5. Bot を作成
6. webhook を設定
7. 完了

---

## パスの選択

### 👀 プロジェクトを評価中？

まずは：

- **[The Pitch](doc/pitch/PITCH.md)**
- **[アーキテクチャ概要](doc/ARCHITECTURE.md)**

---

### 🏢 Gitea を運用中？

まずは：

- **[Gitea セットアップガイド](doc/GITEA_SETUP.md)**
- **[クイックスタート](doc/USING_THE_BOT.md)**

---

### 🔒 セルフホスト AI を探している？

まずは：

- **[デプロイメントガイド](doc/DEPLOYMENT.md)**
- **[Ollama 統合ガイド](doc/OLLAMA.md)** (または OpenAI 互換 API 付き vLLM)

---

### 🤖 ワークフローの自動化に進む？

まずは：

- **[ユーザガイド](doc/USER_GUIDE.md)**
- **[ワークフロードキュメント](doc/PR_WORKFLOWS.md)**

---

### 🧑‍💻 コントリビュートしたい？

まずは：

- **[ローカル開発ガイド](doc/LOCAL_DEVELOPMENT.md)**
- **[アーキテクチャドキュメント](doc/ARCHITECTURE.md)**

---

## 📚 ドキュメント

ドキュメントは **[Documentation Hub](doc/README.md)** で対象読者別に整理されています：

|| あなたは… | ここから始めてください |
||---|---|
|| 👤 **ユーザー** — bot はすでに設定済み、Git プラットフォームを使用 | [Bot の使用](doc/USING_THE_BOT.md) |
|| 🛠️ **管理者** — ソフトウェア、bot、ワークフローを設定 | [デプロイメント](doc/DEPLOYMENT.md) · [管理ガイド](doc/USER_GUIDE.md) |
|| 🧪 **テスター** — 機能を安全に試したい | [テストガイド](doc/TESTING_GUIDE.md) |
|| 💻 **開発者** — コードを扱う | [ローカル開発](doc/LOCAL_DEVELOPMENT.md) · [アーキテクチャ](doc/ARCHITECTURE.md) |

---

## プロジェクトの成熟度

### 本番対応

* GitHub
* GitHub Enterprise
* Gitea

### 🧪 コミュニティフィードバック歓迎

* GitLab
* Bitbucket Cloud

### 実験的ワークフロー

⚠️ フルスタック QA / E2E 自動化

プロジェクトには検証とトラブルシューティングを容易にするための広範なシステムテストとサンプル環境が同梱されています。

バグ報告はいつでも歓迎です。

---

## 技術的特徴

- 🔒 AES-256-GCM シークレット暗号化
- 🤖 マルチプロバイダ AI サポート
- 🏢 マルチプラットフォーム Git サポート
- 🧠 ローカル LLM サポート
- 🔌 MCP 統合
- 🧪 システムテスト済みワークフロー
- 🐳 Docker ファーストデプロイメント
- 🌍 エンドツーエンド セルフホスト可能

---

## コミュニティ

⭐ 100 以上の GitHub スター
🚀 15 以上のリリース
🐳 Docker イメージ利用可能
🌍 GitHub、Gitea、GitLab、Bitbucket のユーザー

## 始め方

```bash
docker pull tmseidel/ai-git-bot:latest
```

---

## 結論

AI-Git-Bot は別のコーディングアシスタントではありません。

ソフトウェア配信ワークフローのためのセルフホスト型自動化レイヤーです。

あなたのチームが既に優れたエンジニアリングプラクティスを知っているが、一貫して実行することに苦労しているなら — AI-Git-Bot はまさにその問題のために作られました。

bot を一つつなげ。

雑務は任せましょう。

🚀 Happy shipping.

## ライセンス

[MIT](LICENSE)
