# AI-Git-Bot

[![License: MIT](https://img.shields.io/github/license/tmseidel/ai-git-bot)](LICENSE)
[![Docker Pulls](https://img.shields.io/docker/pulls/tmseidel/ai-git-bot)](https://hub.docker.com/r/tmseidel/ai-git-bot)
[![GitHub release](https://img.shields.io/github/v/release/tmseidel/ai-git-bot)](https://github.com/tmseidel/ai-git-bot/releases)
[![GitHub stars](https://img.shields.io/github/stars/tmseidel/ai-git-bot)](https://github.com/tmseidel/ai-git-bot/stargazers)
[![GitHub issues](https://img.shields.io/github/issues/tmseidel/ai-git-bot)](https://github.com/tmseidel/ai-git-bot/issues)

🌐 언어 선택: **English** · [中文](README.zh.md) · **한국어** · [日本語](README.ja.md)

> **Git 저장소를 위한 셀프호스트형 AI 워크플로우 자동화 플랫폼.**

- 🔍 풀 리퀘스트를 리뷰
- 🧪 테스트 생성
- ✏️ 이슈 개선
- 🤖 이슈를 풀 리퀘스트로 변환
- 🎬 E2E 테스트 생성 및 실행
- 📝 문서를 코드와 동기화
- 🌍 로케일 파일 간 번역 동기화
- 💬 코드 리뷰 내에서 질문 답변

💡 GitHub Copilot 을 이미 사용 중이신가요?

> **좋습니다.**<br>
> Copilot 은 개발자의 코드 작성 속도를 높입니다. AI-Git-Bot 은 팀이 리뷰, 테스트, 이슈, 풀 리퀘스트 워크플로우를 자동화하는 데 도움을 줍니다.<br>
> 많은 팀은 두 도구를 함께 사용합니다.

## 🔌 어떤 AI 제공자든 어떤 Git 플랫폼과 조합

| AI 제공자 | Git 플랫폼 |
|---|---|
| **Anthropic** (Claude) | **Gitea** (셀프호스트) |
| **OpenAI** (+ OpenAI 호환 API) | **GitHub** / **GitHub Enterprise** |
| **Google AI / Gemini** | **GitLab** (gitlab.com & 셀프매니지드) |
| **Ollama** (로컬 LLM) | **Bitbucket Cloud** |
| **llama.cpp** (로컬 GGUF 모델) | |

대부분의 AI 코딩 도구와 달리, AI-Git-Bot 은 특정 Git 플랫폼이나 AI 제공자에 묶이지 않습니다.

**완전한 셀프호스트 지원. 코드는 인프라 내에 머뭅니다.**

<p align="center">
  <img src="doc/images/dashboard_ai_git_bot.PNG" alt="AI-Git-Bot 대시보드" width="800"/>
</p>

---

## 이 프로젝트는 왜 존재하나요

모든 엔지니어링 팀에는 당연히 일어나야 할 일들이 있습니다:

- 풀 리퀘스트는 신중하게 리뷰되어야 합니다
- 버그에는 회귀 테스트가 추가되어야 합니다
- 이슈에는 수용 기준이 있어야 합니다
- 문서는 코드와 동기화되어 최신 상태여야 합니다
- 프리뷰 환경은 정리되어야 합니다
- 작은 유지보수 티켓은 결국 구현되어야 합니다

누구도 이런 아이디어에 반대하지 않습니다. 문제는 다음과 같습니다:
* 불편하다는 점입니다.
* 반복적이라는 점입니다.
* 우선순위를 매기기 어렵다는 점입니다.
* 마감일이 임박하면 쉽게 미루어진다는 점입니다.

AI-Git-Bot 은 이러한 엔지니어링 잡무를 Git 플랫폼 내에서 자동으로 반복되는 워크플로우로 전환하기 위해 존재합니다.

* 새로운 개발 프로세스 없음.
* 마이그레이션 프로젝트 없음.
* 벤더 잠금 없음.

자동화를 통한 더 나은 엔지니어링 위생.

---

## AI-Git-Bot 은 누구를 위한 것인가요

### 🏢 Gitea 를 운영 중이고 최신 AI 도구링이 부족하신가요?

많은 팀이 소스 코드와 인프라에 대한 소유권을 원하기 때문에 Gitea 를 선택합니다.

불행히도 대부분의 AI 제품은 주로 GitHub 에 초점을 맞추고 있습니다.

AI-Git-Bot 은 다음을 Gitea 에 직접 제공합니다:

- AI 코드 리뷰
- AI 생성 테스트
- AI 이슈 작성
- AI 코딩 워크플로우
- AI 문서 유지관리
- 대화형 PR 토론

OpenAI, Claude, Gemini 또는 로컬 Ollama 모델을 사용하면서 개발자의 기존 워크플로우를 변경할 필요가 없습니다.

👉 개념: **「Gitea 를 위한 Copilot 스타일 워크플로우 자동화.」**

---

### 🔒 AI 는 사용하고 싶지만 코드를 외부 서비스에 보낼 수 없으신가요?

많은 조직은 규정 준수, 개인 정보 보호 또는 계약상 요구 사항으로 인해 클라우드 전용 AI 제품을 사용할 수 없습니다.

AI-Git-Bot 은 다음을 지원합니다:

- Ollama
- llama.cpp
- 셀프호스트 Git 플랫폼
- 프라이빗 네트워크
- 프로바이더 비종속 아키텍처

소스 코드, 프롬프트, 자격 증명 및 모델은 모두 귀하의 통제 하에 남습니다.

👉 개념: **「리포지토리를 SaaS 벤더에 넘기지 않고 AI 워크플로우 자동화.」**

---

### 🚀 너무 많은 엔지니어링 오버헤드로 저장소를 유지하고 있나요?

모든 저장소에는 엔지니어링 잡무가 쌓입니다:

- 리뷰
- 테스트
- 문서화
- 수용 기준
- 후속 수정

AI-Git-Bot 은 팀이 이미 생성하고 있는 이벤트로 트리거되는 반복 가능한 워크플로우로 이러한 작업을 전환합니다:

- 풀 리퀘스트 열림
- 리뷰어 요청
- 이슈 할당
- `@bot` 멘션

👉 개념: **「지루하지만 중요한 업무를 절대 잊지 않는 AI 팀메이트.」**

---

## 작동 방식 보기

AI-Git-Bot 은 개발자가 이미 작업하는 곳에 자리합니다:

- GitHub
- GitHub Enterprise
- Gitea
- GitLab
- Bitbucket Cloud

1. 리뷰를 할당합니다.
2. 이슈를 위임합니다.
3. 댓글에서 멘션합니다.
4. 봇이 Git 플랫폼 내에서 직접 반응합니다.

추가 대시보드 없음.

브라우저 확장 기능 없음.

Slack 봇 관리도 없음.

> 🎥 **PR 워크플로우가 실제로 동작하는 모습을 보세요:** [AI-Git-Bot — PR workflow walkthrough on YouTube](https://www.youtube.com/watch?v=MjFmZHGIO-w)

---

## 스크린샷

### 풀 리퀘스트 리뷰

AI-Git-Bot 은 풀 리퀘스트를 리뷰하고 변경 사항에 직접 실행 가능한 피드백을 남깁니다.

<details>
<summary>📸 스크린샷: 플랫폼 전반의 리뷰, 대화, 코드 에이전트</summary>

**Gitea:** <img src="doc/screenshots/gitea/screenshot_initial_code_review.png" alt="Gitea Code Review" width="600"/>

**GitHub:** <img src="doc/screenshots/github/github_code_review_with_comment.png" alt="GitHub Code Review" width="600"/>

**GitLab:** <img src="doc/screenshots/gitlab/gitlab-pull-request-with-code-review.png" alt="GitLab Code Review" width="600"/>

**Bitbucket:** <img src="doc/screenshots/bitbucket/bitbucket-code-review.png" alt="Bitbucket Code Review" width="600"/>

**코드 에이전트 (GitHub):** <img src="doc/screenshots/github/github_issue_agent_code_implementation.png" alt="GitHub Agent" width="600"/>

</details>

---

### 대화형 토론

풀 리퀘스트 토론의 어디에서든 봇을 멘션할 수 있습니다.

```text
@bot 이 구현이 왜 실패할 수 있는지 설명해 주세요
```

봇은 스레드 내에서 직접 답변하고 대화 컨텍스트를 유지합니다.

<details>
<summary>📸 스크린샷: Gitea 인라인 댓글</summary>

**Gitea:** <img src="doc/screenshots/gitea/screenshot_code_review_with_inline_comment.png" alt="Gitea Inline Comments" width="600"/>
</details>

---

### E2E 테스트 생성

PR 을 봇에 할당하면 변경 사항에 대한 Playwright 테스트 스위트를 생성하고 프리뷰 환경을 배포하며 테스트를 실행한 후 결과를 PR 에 게시할 수 있습니다.

<details>
<summary>📸 스크린샷: PR 의 E2E 테스트 생성</summary>

**GitLab:** <img src="doc/screenshots/pr-workflow/gitea-pr-with-e2e-test-run.png" alt="E2E Tests in a Pull-Request" width="600"/>
</details>

---

### 코드 에이전트

이슈를 코드 에이전트 봇에 할당하면 변경 사항을 구현하는 풀 리퀘스트를 생성할 수 있습니다.

<details>
<summary>📸 스크린샷: 이슈 구현 에이전트</summary>

**GitLab:** <img src="doc/screenshots/gitlab/gitlab_issue_agent_code_implementation.png" alt="Coding Agent in Gitlab" width="600"/>
</details>

---

## ✨ 무엇을 할 수 있나요

| 워크플로우 | 트리거 | 결과 |
|-----------|----------|------|
| **[PR 리뷰](doc/PR_WORKFLOWS_REVIEW.md)** | PR 열림 또는 리뷰 재요청 | 리뷰 코멘트 및 결과 |
| **[대화형 Q&A](doc/PR_WORKFLOWS_REVIEW.md)** | PR 댓글에서 `@bot` 멘션 | 컨텍스트 인식 대화 |
| **[이슈 → 코드](doc/CODING_AGENT.md)** | 이슈를 코드 봇에 할당 | 풀 리퀘스트 |
| **[이슈 → 개선](doc/WRITER_AGENT.md)** | 이슈를 작성 봇에 할당 | 수용 기준이 있는 구조화된 이슈 |
| **[유닛 테스트 생성](doc/PR_WORKFLOWS_UNIT_TEST.md)** | PR 열림 또는 명령 트리거 | 브랜치에 커밋된 생성 테스트 |
| **[풀스택 QA](doc/PR_WORKFLOWS_E2E.md)** | PR 열림 | 프리뷰 환경에서 실행된 Playwright 스위트 |
| **[README 동기화](doc/PR_WORKFLOWS_README_SYNC.md)** | PR 열림 또는 명령 트리거 | 코드 변경에 맞춰 업데이트된 문서 |
| **[i18n 커버리지](doc/PR_WORKFLOWS_I18N_COVERAGE.md)** | PR 열림 또는 명령 트리거 | 로케일 파일 전반에 걸쳐 누락된 번역 초안 작성 |
| **[PR 재리뷰](doc/PR_WORKFLOWS_REVIEW.md)** | 강제 푸시 또는 리뷰 요청 | 업데이트된 분석 |
| **[워크플로우 자동화](doc/PR_WORKFLOWS.md)** | Git 이벤트 | 엔지니어링 잡무 자동화 |

---

## AI-Git-Bot 이 다른 이유

많은 AI 개발 도구는 개발자의 코드 작성 지원에 초점을 맞추고 있습니다.

AI-Git-Bot 은 팀이 일관성 있게 소프트웨어를 출하할 수 있도록 지원하는데 초점을 맞춥니다.

다음만 생각하는 것이 아니라:

> "어떻게 코드를 더 빨리 쓸 수 있을까?"

AI-Git-Bot 은 다음을 고민합니다:

> "중요한 엔지니어링 작업이 건너뛰어지지 않도록 어떻게 할까?"

구체적인 예시는 다음과 같습니다:

- 모든 풀 리퀘스트 리뷰
- 회귀 테스트 추가
- E2E 커버리지 유지
- 문서를 코드와 동기화 유지
- 로케일 파일 간 번역 동기화 유지
- 이슈 품질 개선
- 배포 검증
- 반복 엔지니어링 작업 자동화

---

## 왜 Copilot 만 사용하지 않나요

GitHub Copilot 은 훌륭합니다.

사실 많은 팀은 두 도구를 함께 사용합니다.

현실적인 워크플로우는 다음과 같습니다:

```text
개발자가 Copilot 로 코드 작성
           ↓
      풀 리퀘스트 열림
           ↓
   AI-Git-Bot 이 리뷰
           ↓
   AI-Git-Bot 이 테스트 생성
           ↓
 AI-Git-Bot 이 문서 업데이트
           ↓
 AI-Git-Bot 이 배포 검증
           ↓
      결과 게시
```

Copilot 은 개발자의 코드 작성 속도를 높입니다.

AI-Git-Bot 은 코드 주변 작업을 자동화합니다.

이 목표들은 서로 보완합니다.

---

## 현재 워크플로우

### 🔍 풀 리퀘스트 리뷰

풀 리퀘스트를 자동으로 리뷰하고 다음을 제공:

- 요약 결과
- 인라인 코멘트
- 개선 제안
- 후속 토론

---

### 🤖 이슈 → 풀 리퀘스트

코드 봇을 이슈에 할당.

봇은 다음을 실행:

1. 이슈 읽기
2. 저장소 복제
3. 변경 사항 구현
4. 프로젝트 검증 실행
5. 풀 리퀘스트 열기

---

### ✏️ 이슈 개선

작성 봇을 이슈에 할당.

봇은 간략한 요구사항을 구조화된 엔지니어링 작업 항목으로 변환:

- 배경
- 요구사항
- 수용 기준
- 구현 노트

---

### 🧪 유닛 테스트 생성

풀 리퀘스트 변경 사항을 기반으로 화이트박스 유닛 테스트를 자동으로 생성.

커밋 전에 프로젝트 자체 도구로 테스트를 검증할 수 있습니다.

---

### 🎬 풀스택 QA

풀스택 QA 워크플로우는 다음을 실행:

1. Playwright 테스트 생성
2. 프리뷰 환경 배포
3. 스위트 실행
4. 결과를 풀 리퀘스트에 게시
5. PR 닫을 때 리소스 정리

---

### 📝 README 동기화

풀 리퀘스트가 변경하는 코드에 프로젝트 문서를 맞춰 유지합니다.

이 워크플로우는 PR 로 인해 README 나 기타 Markdown 문서가 부정확하거나
오래되었는지 감지한 다음, 설정된 범위 내에서 해당 문서 파일을 업데이트・추가・
삭제하고 짧은 요약을 게시합니다. Markdown 전용이며, 변경되는 모든 파일은
설정한 문서 패턴 범위 안에 유지됩니다. PR 열림 시 또는
`@bot regenerate-readme <지시>` 로 실행됩니다.

---

### 🌍 i18n 커버리지

풀 리퀘스트가 사용자 대상 문자열을 변경할 때 로케일 파일 전반에 걸쳐
번역을 동기화합니다.

이 워크플로우는 모든 로케일 파일을 설정 가능한 기준 로케일과 비교하여,
번역에 기준 로케일이 정의한 키(추가 또는 변경된 문자열)가 누락되었거나
기준 로케일이 삭제한 키가 여전히 남아 있는 경우, 로케일별로 누락된 번역의
초안을 작성하고 오래된 키를 제거합니다. `messages_*.properties` 와
`i18n/*.json` 파일을 모두 지원하며, 변경되는 모든 파일은 설정한 패턴 범위
안에 유지됩니다. PR 열림 시 또는 `@bot regenerate-i18n <지시>` 로
실행됩니다.

---

## 빠른 시작

Docker Compose 를 사용하여 로컬에서 AI-Git-Bot 실행.

```bash
git clone https://github.com/tmseidel/ai-git-bot.git
cd ai-git-bot
docker compose up --build -d
```

이후:

1. `http://localhost:8080` 열기
2. 관리자 계정 생성
3. AI 통합 생성
4. Git 통합 생성
5. 봇 생성
6. webhook 설정
7. 완료

---

## 경로 선택

### 👀 프로젝트 평가 중이신가요?

시작할 곳:

- **[The Pitch](doc/pitch/PITCH.md)**
- **[아키텍처 개요](doc/ARCHITECTURE.md)**

---

### 🏢 Gitea 를 운영 중이신가요?

시작할 곳:

- **[Gitea 설정 가이드](doc/GITEA_SETUP.md)**
- **[빠른 시작](doc/USING_THE_BOT.md)**

---

### 🔒 셀프호스트 AI 를 찾고 계신가요?

시작할 곳:

- **[배포 가이드](doc/DEPLOYMENT.md)**
- **[Ollama 통합 가이드](doc/OLLAMA.md)** (또는 OpenAI 호환 API 와 함께 vLLM)

---

### 🤖 워크플로우 자동화를 진행할 준비가 되셨나요?

시작할 곳:

- **[사용자 가이드](doc/USER_GUIDE.md)**
- **[워크플로우 문서](doc/PR_WORKFLOWS.md)**

---

### 🧑‍💻 기여하고 싶으신가요?

시작할 곳:

- **[로컬 개발 가이드](doc/LOCAL_DEVELOPMENT.md)**
- **[아키텍처 문서](doc/ARCHITECTURE.md)**

---

## 📚 문서

문서는 **[Documentation Hub](doc/README.md)** 에서 대상 독자별로 정리되어 있습니다:

| 당신은… | 여기서 시작하세요 |
|---|---|
| 👤 **사용자** — 봇이 이미 설정되어 있고 Git 플랫폼 사용 | [봇 사용](doc/USING_THE_BOT.md) |
| 🛠️ **관리자** — 소프트웨어, 봇, 워크플로우 설정 | [배포](doc/DEPLOYMENT.md) · [관리 가이드](doc/USER_GUIDE.md) |
| 🧪 **테스터** — 기능을 안전하게 시험 | [테스트 가이드](doc/TESTING_GUIDE.md) |
| 💻 **개발자** — 코드로 작업 | [로컬 개발](doc/LOCAL_DEVELOPMENT.md) · [아키텍처](doc/ARCHITECTURE.md) |

---

## 프로젝트 성숙도

### 프로덕션 지원

* GitHub
* GitHub Enterprise
* Gitea

### 🧪 커뮤니티 피드백 환영

* GitLab
* Bitbucket Cloud

### 실험적 워크플로우

⚠️ 풀스택 QA / E2E 자동화

프로젝트에는 검증과 문제 해결을 용이하게 하는 광범위한 시스템 테스트와 샘플 환경이 포함됩니다.

버그 리포트는 언제나 환영입니다.

---

## 기술적 특징

- 🔒 AES-256-GCM 시크릿 암호화
- 🤖 멀티프로바이더 AI 지원
- 🏢 멀티플랫폼 Git 지원
- 🧠 로컬 LLM 지원
- 🔌 MCP 통합
- 🧪 시스템 테스트 완료 워크플로우
- 🐳 Docker 퍼스트 배포
- 🌍 엔드투엔드 셀프호스트 지원

---

## 커뮤니티

* ⭐ >100 GitHub 스타
* 🚀 >15 릴리스
* 🐳 Docker 이미지 이용 가능
* 🌍 GitHub, Gitea, GitLab, Bitbucket 사용자

## 시작하기

```bash
docker pull tmseidel/ai-git-bot:latest
```

---

## 결론

AI-Git-Bot 은 또 다른 코딩 어시스턴트가 아닙니다.

소프트웨어 출시 워크플로우를 위한 셀프호스트형 자동화 레이어입니다.

여러분의 팀이 이미 우수한 엔지니어링 관습을 알고 있지만 일관되게 실행하는 데 어려움을 겪고 있다면 — AI-Git-Bot 은 바로 그 문제를 위해 만들어졌습니다.

봇 하나를 연결하세요.

잡무는 스스로 처리되도록 하세요.

🚀 Happy shipping.

## 라이선스

[MIT](LICENSE)
