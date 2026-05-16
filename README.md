# selfhealing — Agentic Operations Gateway

A self-healing microservices agent that reasons over telemetry, deploy history, and logs via the Model Context Protocol (MCP), and remediates production incidents behind an RBAC / ACL / OAuth / human-approval security envelope.

The agent runs a *detect → correlate → hypothesize → remediate → verify* loop over four MCP servers (Prometheus, Logs, Deploy, Notification). An advisor pipeline (evidence tracker, confidence scorer, refusal policy) decides whether to remediate, refuse, or escalate, and emits a structured `Verdict` JSON. Five runnable scenarios exercise the full system — MCP gateway, advisor pipeline, confidence scoring, refusal policy, RBAC, per-tool ACLs, and the OAuth-gated human-in-the-loop.

## Requirements

- Java 21 (records + pattern matching + sealed types)
- Maven 3.9+
- Docker + [kind](https://kind.sigs.k8s.io/) 0.31+ — only for the full real-stack mode (`selfhealing.k8s.enabled=true` and `selfhealing.observability.enabled=true`)
- Anthropic API key — only for LLM planner mode (`selfhealing.planner.mode=llm`)
- Slack bot + app tokens — only for Slack interactive human-approval mode (`selfhealing.human-gate.mode=slack`)

## Setup

```bash
cp .env.example .env
# fill in any keys you need (ANTHROPIC_API_KEY, SLACK_* — all optional for deterministic mode)
```

`spring-dotenv` loads `.env` into the Spring environment at startup, so `${ANTHROPIC_API_KEY}` in `application.yml` resolves transparently. `.env` is gitignored.

## Quick start

```bash
# Compile
mvn -q -DskipTests compile

# Run all 5 scenarios (deterministic planner, in-process MCP, fake backends).
mvn -q -DskipTests spring-boot:run -Dspring-boot.run.arguments="--all"

# Or one scenario at a time:
mvn -q -DskipTests spring-boot:run -Dspring-boot.run.arguments="--scenario happy-path"
```

Scenario slugs: `happy-path`, `refusal`, `rbac-denial`, `acl-denial`, `oauth-gate`.

## Scenarios

| Scenario | What it shows | Result |
|---|---|---|
| `happy-path` | End-to-end remediation loop | `REMEDIATE` verdict at confidence ≥ 0.80, action block with OAuth-gated rollback |
| `refusal` | Incomplete telemetry (`log.search` times out) | `REFUSE` verdict with `missing_signals` populated |
| `rbac-denial` | Triage role attempts a write tool | `RbacDeniedException` before any backend is touched |
| `acl-denial` | Principal scoped to `search` attempts `auth` | RBAC denial: principal not authorized for scope `auth` |
| `oauth-gate` | Audience-scoped approval token | Four attempts: no token, wrong audience, expired, valid → reject, reject, reject, accept |

## Run modes

The agent has five independent toggles. Each defaults to "demo-friendly" (no external dependencies). Flip any combination to exercise the real stack.

| Toggle | Default | Real-mode flag |
|---|---|---|
| **Planner** | `deterministic` (hardcoded sequence per scenario) | `--selfhealing.planner.mode=llm` (Anthropic Claude Haiku 4.5) |
| **MCP transport** | `in-process` (direct Java calls) | `--spring.profiles.active=mcp-http` (JSON-RPC 2.0 over Streamable HTTP at `localhost:8765/sse`) |
| **Deploy backend** | `DeployFakeBackend` (in-memory) | `--selfhealing.k8s.enabled=true` (kind cluster via fabric8) |
| **Telemetry** | `PrometheusFakeBackend` + `LogFakeBackend` | `--selfhealing.observability.enabled=true` (real Prometheus 2.55 + Loki 3.1.1 over HTTP) |
| **OAuth** | HMAC tokens | `--selfhealing.oauth.enabled=true` (RS256 JWT via Spring Security `oauth2-jose` + in-memory JWKSource) |
| **Human gate** | `auto` (auto-approve every write) | `--selfhealing.human-gate.mode=slack` (Bolt Socket Mode, Block Kit Approve/Deny) |
| **Refusal hand-off** | none (verdict to stdout) | `--selfhealing.refusal.handoff.slack.enabled=true` (Block Kit hand-off message to `SLACK_WEBHOOK_URL` channel, no buttons; pings `<!here>` by default) |

### Common combinations

```bash
# Default — fast, no external deps, deterministic planner, fake backends, in-process MCP.
mvn -q spring-boot:run -Dspring-boot.run.arguments="--all"

# Real LLM planner (Anthropic Claude Haiku 4.5), still fake backends.
mvn -q spring-boot:run -Dspring-boot.run.arguments="--selfhealing.planner.mode=llm --scenario happy-path"

# Real MCP wire protocol (JSON-RPC 2.0 over HTTP/SSE). Verdicts are byte-identical to default mode.
mvn -q spring-boot:run -Dspring-boot.run.arguments="--spring.profiles.active=mcp-http --scenario happy-path"

# Real LLM + real Kubernetes + real Prometheus + real Loki + real OAuth JWT.
# Requires `make kind-bootstrap` first (see below).
mvn -q spring-boot:run -Dspring-boot.run.arguments="--selfhealing.planner.mode=llm \
    --selfhealing.k8s.enabled=true \
    --selfhealing.observability.enabled=true \
    --selfhealing.oauth.enabled=true \
    --scenario happy-path"

# Slack interactive human gate (Bolt Socket Mode — no public callback URL needed).
mvn -q spring-boot:run -Dspring-boot.run.arguments="--selfhealing.human-gate.mode=slack --scenario happy-path"
```

The Makefile wraps the most common combos:

```bash
make run            # all scenarios, deterministic, fake backends
make run-llm        # happy-path, LLM planner
make run-llm-k8s    # happy-path, LLM + real K8s + real observability
```

## Full real-stack: bring up the kind cluster

Required only for `--selfhealing.k8s.enabled=true` and/or `--selfhealing.observability.enabled=true`.

```bash
make target-image    # docker build selfhealing-target:v1 + selfhealing-target:v2
make kind-bootstrap  # create cluster, load images, install Prom/Loki/Promtail, deploy v1 then patch to v2
```

`kind-bootstrap` is the one-shot equivalent of: `kind-up`, `target-load`, `kind-observability`, `kind-deploy`, `kind-deploy-v2`. After it returns the cluster has search-api at v2 (spike mode on), Prometheus at `localhost:9090`, Loki at `localhost:3100`. Now run `make run-llm-k8s` and the agent will see the real p99 spike, query Loki for upstream errors, roll back to v1 via fabric8 (`kubectl rollout undo` equivalent), and confirm the recovery on a follow-up Prometheus query.

Tear down with `make kind-down`.

## Build, format, verify

```bash
mvn spotless:apply       # format (Google Java Format, AOSP style)
mvn spotless:check       # enforced on every build
mvn -DskipTests verify   # full build
mvn -DskipTests compile  # quick compile
```

## Benchmarking

```bash
scripts/bench.sh [N]    # default N=3 — runs N happy-path scenarios against the full real stack,
                        # captures LLM round-trips, tokens, latency, end-to-end wall-clock, and
                        # writes per-run logs to bench-results/run-<i>.log (JWTs auto-redacted).
```

## Architecture

The agent (`agent/Agent`) runs a planner that emits `McpToolInvocation` commands. Each invocation goes through `AbstractMcpServer`, which applies the same security envelope every time: RBAC → per-tool ACL → OAuth approval-token check (write only) → execute. Successful responses join a per-incident `EvidenceLedger`. After the loop, the `AdvisorPipeline` runs the chain (`EvidenceTracker` → `ConfidenceScorer` → `RefusalPolicy`) and produces a structured `Verdict` JSON containing the decision, evidence, missing signals, confidence, threshold, action, and next steps. Write tools require a fresh, audience-scoped approval token minted by the human-approval gate; without it the verdict flips to `REFUSE`.

In `mcp-http` mode, the agent's `RemoteMcpRegistry` connects to an in-process Spring AI MCP server endpoint at `localhost:8765/sse`, calls `tools/list` once to discover the catalogue, then dispatches each `tools/call` as JSON-RPC 2.0 over Streamable HTTP. The `McpServerAdapter` registers every `McpServer` bean's tools on that endpoint so the existing security envelope runs unchanged on the server side. Identity, scope, and approval token ride inside the `tools/call` arguments under the reserved `_envelope` key.

### Design patterns

| Pattern | Where |
|---|---|
| Chain of Responsibility | `AdvisorChain`, `AdvisorPipeline` |
| Template Method | `AbstractMcpServer.invoke` — RBAC → ACL → OAuth → execute |
| Strategy | `ConfidenceStrategy`, `Planner` (deterministic vs LLM) |
| Specification | `RefusalRule` (`ThresholdRule`, `CompletenessRule`) |
| Repository | `EvidenceLedger` |
| Builder | `VerdictBuilder` (decision-specific invariants) |
| Adapter | `*McpServer` over `*FakeBackend` / `KubernetesDeployBackend`; `McpServerAdapter` over Spring AI MCP runtime |
| Command | `McpToolInvocation` |
| Registry / Facade | `McpRegistry` / `McpRegistryImpl` (in-process), `RemoteMcpRegistry` (HTTP) |
| Decorator | Advisors decorating the agent loop |
| Guard | `RbacGuard`, `AclGuard` |
| Mediator | `HumanApprovalGate` (auto + Slack implementations) |

### Layout

```
src/main/java/com/sachingupta/selfhealing/
├── SelfHealingApplication        # Spring Boot entry point
├── verdict/                      # Verdict, Action, EvidenceRef, McpResponse, VerdictBuilder
├── mcp/api/                      # McpServer, McpTool, McpRegistry, McpToolInvocation
├── mcp/infrastructure/           # AbstractMcpServer (template), McpRegistryImpl
├── mcp/transport/                # McpServerAdapter, RemoteMcpRegistry, RemoteMcpServer, McpRequestEnvelope
├── mcp/servers/                  # Prometheus, Log, Deploy, Notification (fake + real backends)
├── advisor/api/                  # Advisor, AdvisorChain, ReasoningContext
├── advisor/pipeline/             # AdvisorPipeline (chain) + AdvisorChainImpl
├── advisor/evidence/             # EvidenceLedger (Repository) + EvidenceTracker (Advisor)
├── advisor/confidence/           # ConfidenceStrategy + CompletenessConsistencyStrategy + ConfidenceScorer
├── advisor/refusal/              # RefusalRule + ThresholdRule + CompletenessRule + RefusalPolicy
├── security/identity/            # AgentIdentity, Role
├── security/rbac/                # RbacGuard
├── security/acl/                 # ToolAcl, AclGuard, PrincipalAclResolver, InMemoryPrincipalAclResolver
├── security/oauth/               # ApprovalToken, TokenIssuer/Verifier (HMAC + JWT), OAuthJwtConfig
├── agent/                        # Agent, Planner, PlannedStep, ReasoningPhase, ChatClient
├── agent/humangate/              # HumanApprovalGate (auto + Slack), HumanApprovalRequest/Outcome, SlackBlockKit
├── agent/llm/                    # LlmPlanner, LlmPlannerFactory, ToolCatalogue, LedgerRenderer, PromptTemplates
└── scenarios/                    # Scenario, ScenarioRunner, 5 scenario implementations
```

## Configuration reference

All settings live in `src/main/resources/application.yml`; every value can be overridden on the CLI via `--<key>=<value>`.

| Property | Default | Effect |
|---|---|---|
| `selfhealing.refusal.threshold` | `0.80` | Confidence below this flips the verdict to `REFUSE`. |
| `selfhealing.refusal.handoff.slack.enabled` | `false` | Post a Block Kit hand-off to `SLACK_WEBHOOK_URL` on every refuse verdict. |
| `selfhealing.refusal.handoff.slack.mention` | `<!here>` | Slack mention prefix on the hand-off message; blank to disable. |
| `selfhealing.planner.mode` | `deterministic` | `llm` enables the Anthropic-backed `LlmPlanner`. |
| `selfhealing.agent.max-steps` | `12` | Hard cap on MCP invocations per run (bounds runaway planners). |
| `selfhealing.mcp.mode` | `in-process` | Flipped to `http` by the `mcp-http` profile. |
| `selfhealing.mcp.http-port` | `8765` | Port the embedded Tomcat binds to in `mcp-http` profile. |
| `selfhealing.k8s.enabled` | `false` | `true` swaps `DeployFakeBackend` for `KubernetesDeployBackend` (fabric8). |
| `selfhealing.k8s.namespace` | `selfhealing` | Kubernetes namespace the agent operates on. |
| `selfhealing.k8s.scope-deployment-suffix` | `-api` | Scope `search` + suffix → deployment `search-api`. |
| `selfhealing.observability.enabled` | `false` | `true` swaps fake telemetry for real Prometheus + Loki HTTP backends. |
| `selfhealing.prometheus.url` | `http://localhost:9090` | Real Prometheus base URL. |
| `selfhealing.loki.url` | `http://localhost:3100` | Real Loki base URL. |
| `selfhealing.oauth.enabled` | `false` | `true` swaps HMAC token issuer/verifier for RS256 JWT (Nimbus). |
| `selfhealing.human-gate.mode` | `auto` | `slack` swaps `AutoApprovalGate` for `SlackApprovalGate` (Block Kit Approve/Deny). |
| `selfhealing.human-gate.timeout-seconds` | `120` | Seconds to wait for a Slack reviewer before refusing the write. |

### Environment variables

| Variable | Used when | Source |
|---|---|---|
| `ANTHROPIC_API_KEY` | `selfhealing.planner.mode=llm` | `.env` |
| `SLACK_WEBHOOK_URL` | Notification MCP — outbound Slack post. Optional; absent → console log only. | `.env` |
| `SLACK_BOT_TOKEN` | `selfhealing.human-gate.mode=slack` | `.env` |
| `SLACK_APP_TOKEN` | `selfhealing.human-gate.mode=slack` | `.env` |
| `SLACK_APPROVAL_CHANNEL` | `selfhealing.human-gate.mode=slack` (channel ID, e.g. `C0B15K0SJMU`) | `.env` |

See `.env.example` for the template.

## How the MCP wire protocol toggle works (mcp-http profile)

Activating the `mcp-http` Spring profile flips three things at once:

1. `spring.main.web-application-type` switches to `servlet` so the embedded Tomcat binds.
2. `spring.ai.mcp.server.enabled` becomes `true`, auto-configuring Spring AI 1.0.1's MCP server starter. `McpServerAdapter` contributes a `List<SyncToolSpecification>` bean built from every `McpServer` bean's tool catalogue, registered under qualified names (`prometheus.range_query`, `deploy.rollback`, …).
3. `selfhealing.mcp.mode` becomes `http`, so the `@ConditionalOnProperty` beans `RemoteMcpRegistry`, `RemoteMcpServer`, and `McpServerAdapter` light up. `McpRegistryImpl` (the in-process implementation) is conditionally absent in this mode.

The `RemoteMcpRegistry` builds its `McpSyncClient` lazily on first lookup, calls `tools/list` once to discover the 11 tools, regroups them by qualified-name prefix into one `RemoteMcpServer` per logical audience (`prometheus-mcp`, `log-mcp`, `deploy-mcp`, `notification-mcp`), and from the agent's perspective the registry contract is identical to the in-process case (Open/Closed, Liskov). Verdict JSON is byte-identical to default mode.
