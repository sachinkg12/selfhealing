package com.sachingupta.selfhealing.mcp.servers;

import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * In-memory {@link DeployBackend}. Used only when the K8s integration is off (default for tests
 * without a cluster). Self-seeds the default {@code search} scope on bean construction so scenarios
 * do not need to call {@link #seed} manually.
 *
 * <p>Activated when {@code selfhealing.k8s.enabled} is missing or {@code false}. In a normal demo
 * run with {@code --selfhealing.k8s.enabled=true}, {@link KubernetesDeployBackend} replaces this
 * bean.
 */
@Component
@ConditionalOnProperty(
        name = "selfhealing.k8s.enabled",
        havingValue = "false",
        matchIfMissing = true)
public class DeployFakeBackend implements DeployBackend {

    private final Map<String, List<Rollout>> historyByService = new HashMap<>();
    private final Map<String, String> currentByService = new HashMap<>();
    private final List<RollbackRecord> rollbacks = new ArrayList<>();
    private final ObjectProvider<PrometheusFakeBackend> prometheusFakeProvider;

    public DeployFakeBackend(ObjectProvider<PrometheusFakeBackend> prometheusFakeProvider) {
        this.prometheusFakeProvider = prometheusFakeProvider;
    }

    /**
     * Seeds the canonical {@code search} scope used by happy-path and refusal scenarios. Scenarios
     * may still override via {@link #seed} if they want different state.
     */
    @PostConstruct
    void seedDefaults() {
        seed(
                "search",
                List.of(
                        new Rollout("rev-7c2a", "rollout", Instant.now().minusSeconds(7200)),
                        new Rollout("rev-7c2b", "canary", Instant.now().minusSeconds(720))),
                "rev-7c2b");
    }

    public void seed(String service, List<Rollout> history, String currentRevision) {
        historyByService.put(service, new ArrayList<>(history));
        currentByService.put(service, currentRevision);
    }

    @Override
    public Map<String, Object> history(String scope) {
        List<Rollout> rollouts = historyByService.getOrDefault(scope, List.of());
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("service", scope);
        r.put(
                "rollouts",
                rollouts.stream()
                        .map(
                                rl ->
                                        Map.of(
                                                "revision",
                                                rl.revision(),
                                                "kind",
                                                rl.kind(),
                                                "when",
                                                rl.relativeWhen()))
                        .toList());
        String signal =
                rollouts.stream()
                        .filter(rl -> "canary".equals(rl.kind()))
                        .findFirst()
                        .map(
                                rl ->
                                        "canary cutover at "
                                                + rl.relativeWhen()
                                                + " ("
                                                + rl.revision()
                                                + ")")
                        .orElse("no recent rollouts");
        r.put("signal", signal);
        return r;
    }

    @Override
    public Map<String, Object> currentRevision(String scope) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("service", scope);
        r.put("revision", currentByService.getOrDefault(scope, "unknown"));
        r.put("signal", "current revision " + r.get("revision"));
        return r;
    }

    @Override
    public Map<String, Object> rollback(String scope, Map<String, Object> arguments) {
        String target = (String) arguments.get("target_revision");
        if (target == null || target.isBlank()) {
            throw new IllegalArgumentException("rollback requires target_revision");
        }
        rollbacks.add(new RollbackRecord(scope, target, Instant.now()));
        currentByService.put(scope, target);
        // A successful rollback returns the service to baseline; the verify step should now see
        // p99 recover. Without this transition the agent (or LLM planner) would loop trying to
        // verify a non-recovering service. In real-observability mode the fake Prometheus is
        // absent and the cluster handles the transition naturally.
        PrometheusFakeBackend prometheusFake = prometheusFakeProvider.getIfAvailable();
        if (prometheusFake != null) {
            prometheusFake.setTrajectory(scope, PrometheusFakeBackend.Trajectory.RECOVERED);
        }
        return Map.of(
                "service",
                scope,
                "rolled_back_to",
                target,
                "status",
                "succeeded",
                "signal",
                "rollback to " + target + " succeeded");
    }

    @Override
    public Map<String, Object> scale(String scope, Map<String, Object> arguments) {
        return Map.of(
                "service",
                scope,
                "replicas",
                arguments.getOrDefault("replicas", 1),
                "status",
                "succeeded",
                "signal",
                "scaled to " + arguments.getOrDefault("replicas", 1) + " replicas");
    }

    @Override
    public Map<String, Object> restart(String scope) {
        return Map.of(
                "service", scope, "status", "restart-triggered", "signal", "restart triggered");
    }

    public List<RollbackRecord> rollbacks() {
        return List.copyOf(rollbacks);
    }

    public record Rollout(String revision, String kind, Instant when) {
        public String relativeWhen() {
            long secs = (Instant.now().getEpochSecond() - when.getEpochSecond());
            if (secs < 60) {
                return "T-" + secs + "s";
            }
            return "T-" + (secs / 60) + "m";
        }
    }

    public record RollbackRecord(String service, String targetRevision, Instant when) {}
}
