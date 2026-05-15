# selfhealing — Agentic Operations Gateway

A self-healing microservices agent that reasons over telemetry, deploy history, and logs via the Model Context Protocol (MCP), and remediates production incidents behind an RBAC / ACL / OAuth / human-approval security envelope.

The agent runs a *detect → correlate → hypothesize → remediate → verify* loop over four MCP servers (Prometheus, Logs, Deploy, Notification). An advisor pipeline (evidence tracker, confidence scorer, refusal policy) decides whether to remediate, refuse, or escalate, and emits a structured `Verdict` JSON. Five runnable scenarios exercise the full system — MCP gateway, advisor pipeline, confidence scoring, refusal policy, RBAC, per-tool ACLs, and the OAuth-gated human-in-the-loop.

## Quick start

```bash
mvn -q -DskipTests compile
mvn -q -DskipTests spring-boot:run -Dspring-boot.run.arguments="--all"
```

Or one scenario at a time:

```bash
mvn -q -DskipTests spring-boot:run -Dspring-boot.run.arguments="--scenario happy-path"
```

Scenario slugs: `happy-path`, `refusal`, `rbac-denial`, `acl-denial`, `oauth-gate`.

## What the scenarios exercise

| Scenario | What it shows | Result |
|---|---|---|
| `happy-path` | End-to-end remediation loop | `REMEDIATE` verdict with confidence ≈ 0.87, action block with OAuth-gated rollback |
| `refusal` | Incomplete telemetry → refusal | `REFUSE` verdict with `missing_signals: [search]` |
| `rbac-denial` | Triage role cannot call write tools | `RbacDeniedException` before any backend is touched |
| `acl-denial` | Per-tool ACL: principal authorized for `search` scope, not `auth` | RBAC denial: principal not authorized for scope `auth` |
| `oauth-gate` | Audience-scoped approval token | Four attempts: no token, wrong audience, expired, valid → reject, reject, reject, accept |

## Build, format, verify

```bash
mvn spotless:apply       # format (Google Java Format, AOSP)
mvn spotless:check       # verified on every build
mvn -DskipTests verify   # build + tests
```

## Architecture in one paragraph

The agent (`agent/Agent`) runs a planner that emits `McpToolInvocation` commands. Each invocation goes through `AbstractMcpServer`, which applies the same security envelope every time: RBAC → per-tool ACL → OAuth approval-token check (write only) → execute. Successful responses join a per-incident `EvidenceLedger`. After the loop, the `AdvisorPipeline` runs the chain (`EvidenceTracker` → `ConfidenceScorer` → `RefusalPolicy`) and produces a structured `Verdict` JSON containing the decision, evidence, missing signals, confidence, threshold, action, and next steps.

## Layout

```
src/main/java/com/sachingupta/selfhealing/
├── SelfHealingApplication        # Spring Boot entry point
├── verdict/                      # Verdict, Action, EvidenceRef, McpResponse, VerdictBuilder
├── mcp/api/                      # McpServer, McpTool, McpRegistry, McpToolInvocation
├── mcp/infrastructure/           # AbstractMcpServer (template), McpRegistryImpl
├── mcp/servers/                  # Prometheus, Log, Deploy, Notification (+ in-memory backends)
├── advisor/api/                  # Advisor, AdvisorChain, ReasoningContext
├── advisor/pipeline/             # AdvisorPipeline (chain) + AdvisorChainImpl
├── advisor/evidence/             # EvidenceLedger (Repository) + EvidenceTracker (Advisor)
├── advisor/confidence/           # ConfidenceStrategy + CompletenessConsistencyStrategy + ConfidenceScorer
├── advisor/refusal/              # RefusalRule + ThresholdRule + CompletenessRule + RefusalPolicy
├── security/identity/            # AgentIdentity, Role
├── security/rbac/                # RbacGuard
├── security/acl/                 # ToolAcl, AclGuard, PrincipalAclResolver, InMemoryPrincipalAclResolver
├── security/oauth/               # ApprovalToken, TokenIssuer, TokenVerifier
├── agent/                        # Agent, Planner, PlannedStep, ReasoningPhase, ChatClient
└── scenarios/                    # Scenario, ScenarioRunner, 5 scenario implementations
```
