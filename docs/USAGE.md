# Getting Started with AutoIdeator

This guide walks you from a fresh checkout to a running dashboard with a real cycle in progress. It assumes nothing beyond comfort with a terminal.

If you just want the high-level architecture, agent roles, REST/WebSocket API, or configuration reference, see the [README](../README.md).

> **Note on tested paths.** Only the **web dashboard** has been exercised end-to-end. The headless CLI (`./gradlew run --args=...`) compiles and the flag plumbing is correct, but it has not been tested in practice. This guide uses the dashboard throughout.

## 1. Prerequisites

- **Linux** (tested on Manjaro; other distros should work). bubblewrap-based sandboxing is Linux-only, so on macOS / Windows the agents run unsandboxed.
- **Java 21+** (the orchestrator uses virtual threads and is built with `--enable-preview`).
- **Git** — almost certainly already installed.
- **bubblewrap (`bwrap`)** — recommended for sandboxing the agents' file writes. AutoIdeator detects its absence and falls back to running unsandboxed with a warning.
- **An LLM backend CLI** — see step 3.

### Installing Java 21

Easiest path is [SDKMAN](https://sdkman.io):

```bash
curl -s "https://get.sdkman.io" | bash
source "$HOME/.sdkman/bin/sdkman-init.sh"
sdk install java 21.0.4-tem
```

Verify:

```bash
java -version   # should report 21.x
```

### Installing bubblewrap

```bash
# Arch / Manjaro
sudo pacman -S bubblewrap
# Debian / Ubuntu
sudo apt install bubblewrap
# Fedora
sudo dnf install bubblewrap
```

Verify:

```bash
bwrap --version
```

If `bwrap` is missing, AutoIdeator logs a startup warning and runs every agent without write isolation. Fine for trusted goals on a throwaway working directory; not fine for anything sensitive.

## 2. Get the source and build

```bash
git clone https://github.com/akumaburn/AutoIdeator.git
cd AutoIdeator
./gradlew build
```

The first build downloads Gradle 8.5 and all dependencies; expect a few minutes. Subsequent runs are fast.

## 3. Pick and install an LLM backend

AutoIdeator drives external LLMs by spawning their CLIs (OpenCode CLI, Claude CLI) or by calling an HTTPS API (OpenRouter). Three of the four backends are tested:

| Backend | Config key | Status | Notes |
|---|---|---|---|
| OpenCode CLI | `opencode-cli` | Tested (default) | Streaming, automatic retry on stall. Works with z.ai's coding plan, OpenRouter via OpenCode, etc. |
| Claude CLI | `claude-cli` | Tested | Standard Anthropic Claude CLI, prompt via stdin |
| Custom Claude CLI | `custom-claude-cli` | Tested | Standard Claude CLI invoked with `ANTHROPIC_*` env vars overridden — for Anthropic-compatible alternative providers |
| OpenRouter | `openrouter` | **Not tested** | HTTPS API. Implemented but unexercised; expect rough edges |

### Option A — OpenCode CLI (default, recommended for first-timers)

Install per the OpenCode docs (<https://github.com/sst/opencode>). After installation:

```bash
opencode --version
```

Run `opencode auth login` (or whatever the current OpenCode auth flow is) **once outside AutoIdeator** so the credential is cached. AutoIdeator will fail or hang if OpenCode wants an interactive login.

### Option B — Claude CLI (Anthropic)

Install Anthropic's official Claude CLI per their docs, then:

```bash
claude --version
export ANTHROPIC_API_KEY=sk-ant-...
```

Switch the backend in your config (step 4):

```hocon
autoideator.llm.backend = "claude-cli"
```

### Option C — Custom Claude CLI for alternative providers

If you have an Anthropic-compatible provider (e.g. z.ai), install the standard Claude CLI and let AutoIdeator inject the override env vars. The defaults in `application.conf.example` are pre-wired for z.ai — just add your key and switch the backend:

```bash
export ANTHROPIC_API_KEY=your-z-ai-key
```

```hocon
autoideator.llm.backend = "custom-claude-cli"
```

## 4. Configure (optional)

The defaults work out of the box for OpenCode CLI. To customize:

```bash
cp src/main/resources/application.conf.example src/main/resources/application.conf
$EDITOR src/main/resources/application.conf
```

`application.conf` is gitignored — your secrets stay local. The example file is the canonical reference for every available setting; the most common edits are:

- `llm.backend` — which CLI / API to drive
- `llm.model` — model name (backend-specific)
- `orchestration.max-concurrent-coders` — how many parallel coder agents per cycle (default 5)
- `orchestration.sandbox-enabled` — turn the bubblewrap sandbox on/off
- `orchestration.idea-queue-weights` — bias the rotation toward Dreamer / Refiner / Hacker / Obsessor / Advancer / Artist

Keys for OpenRouter and Custom Claude CLI come from environment variables by default (`OPENROUTER_API_KEY`, `ANTHROPIC_API_KEY`) — you do not need to put them in the config file.

## 5. Start the dashboard

```bash
./gradlew runDashboard
```

You should see something like:

```
INFO  c.a.DashboardApplication - Starting AutoIdeator Dashboard...
INFO  c.a.DashboardApplication - Open http://localhost:7070 in your browser
```

Open that URL.

## 6. Drive a run from the dashboard

![AutoIdeator dashboard mid-run. Cycle 21, phase Implement→Coders. Three coder agents are active (the green "42" badge is the cumulative coder count for the session). Earlier phases — Synthesizer, Scorer, Skeptic, Architect — show their per-cycle status; the right column tracks the idea-queue rotation, with Maestro currently gating Artist on. Reviewer just committed a fix, QA passed, Verifier confirmed all 49 of 49 features still work, and Organizer / Cleaner / Documenter completed.](images/dashboard.png)

The dashboard's anatomy:

1. **Top bar** — current phase ("Implement → Coders" in the screenshot), `⚙️ Config` button (live config editor, tabbed by section), and run state pill (`Orchestration running` / `paused` / `idle`).
2. **Agent Workflow panel** (large center panel above) — every agent in the pipeline with its current per-cycle status. The active agent has a green outline; the **Coders** node carries a green numeric badge showing the total cumulative coders spawned this session.
3. **Project Goal field** and **Working Directory field** — what to give the orchestrator before clicking Start.
4. **Control panel** — Start / Pause / Resume / Stop.
5. **Overseer panel** — submit a free-text suggestion that takes priority over the next idea-queue rotation.
6. **System metrics, token statistics, event log, agent output sidebar** — live telemetry (off-screen in the screenshot above).

### Filling in the form

- **Project Goal** — a detailed, multi-sentence description of the *finished* project. Describe what done looks like for a user: features, behavior, outputs, UX. Do **not** include implementation steps, phases, milestones, or task lists — the agents handle planning. Clearer goals converge faster.
- **Working Directory** — an absolute path to the project directory the agents should operate in. It must already exist and be writable. The agents commit to git inside this directory, so initialize it with `git init` first if it's empty.

### Click Start

The orchestrator runs an infinite loop of cycles. Each cycle walks through the pipeline shown in the screenshot:

1. **Synthesize** *(every Nth cycle)* — find synergies in recent cycles
2. **Ideate** — pick the next idea agent from the weighted rotation (Dreamer, Refiner, Hacker, Obsessor, Advancer, or Artist when Maestro gates it on)
3. **Score** the idea on goal alignment, novelty, and feasibility (Scorer)
4. **Critique** with mitigations (Skeptic) and **strategize** alignment (Architect)
5. **Plan** (Director) and **implement** in parallel (Coders)
6. **Review & commit** (Reviewer), **build & test** (QA), **optimize slow tests** (TestOptimizer)
7. **Verify every feature still works** (Verifier — inventory → per-feature check → remediation)
8. **Refactor oversized files** every other cycle (Organizer)
9. **Clean up** temp files (Cleaner) and **update docs** (Documenter)
10. **Save a checkpoint** and start the next cycle

The screenshot above is from cycle 21 partway through implementation: the Reviewer just committed (`fix: DDGI ray loop — iterate all rays, not just…`), QA passed, Verifier confirmed 49/49 features still work, the housekeeping agents are done, and the next cycle's Coders are 3-active.

## 7. Pause, resume, stop, restart

- **Pause** — finishes the current sub-step, then waits. Safe at any time. Click Resume to continue.
- **Stop** (red button) — explicit stop. Deletes the checkpoint for that working directory; the next Start begins a fresh cycle 1.
- **Ctrl+C in the terminal** — graceful shutdown. The current cycle finishes, then a checkpoint is saved. Re-run `./gradlew runDashboard`, click Start, and it resumes from where you left off.
- **Crash / kill -9** — same as Ctrl+C from the user's perspective: the last completed cycle's checkpoint is preserved.

If a checkpoint exists when you load the dashboard, a banner offers `▶ Resume` (default) or `✦ Start Fresh` (delete and begin again).

## 8. Inspect what's happening

- **Click any agent node** — opens the agent-output modal with the full untruncated transcript for the latest run (up to 500K chars per agent), plus a per-cycle history.
- **Event log panel** — live stream of `STARTED / IN_PROGRESS / COMPLETED / FAILED / WAITING / THINKING / TOOL_USE / RETRY / PAUSED` events.
- **`logs/autoideator.log`** — full DEBUG-level orchestrator log on disk.
- **`~/.autoideator/checkpoints/<sha256>.json`** — saved cycle state, one per working directory.

## 9. Common pitfalls

- **"Working directory does not exist"** — give an absolute path that already exists and is writable. Run `mkdir -p` and `git init` in it first if it's a new project.
- **Agents wait forever** — almost always the LLM CLI is unauthenticated. Run `opencode --version` (or `claude --version`) and confirm a real session works outside AutoIdeator first.
- **OpenCode exits immediately on first run** — its first-time login flow is interactive; complete it once outside AutoIdeator.
- **Bubblewrap missing on Linux** — install it (step 1) or set `orchestration.sandbox-enabled = false`. The startup warning calls this out explicitly.
- **First cycle's Skeptic is skipped** — by design when the project has fewer than 2 commits; not a bug.
- **API key visible in `ps`** — if you supplied `--api-key`, AutoIdeator logs a security warning. Prefer `OPENROUTER_API_KEY` / `ANTHROPIC_API_KEY` env vars or the config file.

## 10. Going further

- **Architecture, full agent roles, REST endpoints, sandbox details** — [README.md](../README.md)
- **Configuration reference** — `src/main/resources/application.conf.example`
- **Issues / questions** — <https://github.com/akumaburn/AutoIdeator/issues>
