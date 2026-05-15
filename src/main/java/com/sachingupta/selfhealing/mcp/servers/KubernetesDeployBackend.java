package com.sachingupta.selfhealing.mcp.servers;

import io.fabric8.kubernetes.api.model.OwnerReference;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.ReplicaSet;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Real {@link DeployBackend} backed by a fabric8 {@link KubernetesClient}. Operations map directly
 * to standard Kubernetes API calls:
 *
 * <ul>
 *   <li>{@code history(scope)} lists the {@code ReplicaSet}s owned by the Deployment and reports
 *       them with their {@code deployment.kubernetes.io/revision} annotation.
 *   <li>{@code currentRevision(scope)} reads the same annotation off the Deployment itself.
 *   <li>{@code rollback(scope, target_revision)} finds the ReplicaSet matching the target revision
 *       and updates the Deployment's container image back to that ReplicaSet's image — equivalent
 *       to {@code kubectl rollout undo --to-revision=N}.
 *   <li>{@code scale} / {@code restart} use the standard scale and rolling-restart annotation
 *       primitives.
 * </ul>
 *
 * <p>Scopes map to Deployment names by the convention {@code scope → "<scope>-api"} (for example
 * {@code "search" → "search-api"}). Override via {@code selfhealing.k8s.scope-deployment-suffix} if
 * a different convention is needed.
 *
 * <p>Active when {@code selfhealing.k8s.enabled=true}. The Prometheus fake backend is optionally
 * injected so that a successful rollback can simulate metric recovery for the verify step while the
 * real Prometheus integration is still in Phase 2. Once Phase 2 lands and a real metrics backend
 * replaces the fake, this dependency becomes a no-op.
 */
@Component
@ConditionalOnProperty(name = "selfhealing.k8s.enabled", havingValue = "true")
public class KubernetesDeployBackend implements DeployBackend {

    private static final Logger log = LoggerFactory.getLogger(KubernetesDeployBackend.class);
    private static final String REVISION_ANNOTATION = "deployment.kubernetes.io/revision";
    private static final String RESTART_ANNOTATION = "selfhealing/restartedAt";

    private final KubernetesClient client;
    private final String namespace;
    private final String deploymentSuffix;
    private final ObjectProvider<PrometheusFakeBackend> prometheusFakeProvider;

    public KubernetesDeployBackend(
            @Value("${selfhealing.k8s.namespace:selfhealing}") String namespace,
            @Value("${selfhealing.k8s.scope-deployment-suffix:-api}") String deploymentSuffix,
            ObjectProvider<PrometheusFakeBackend> prometheusFakeProvider) {
        this.namespace = namespace;
        this.deploymentSuffix = deploymentSuffix;
        this.prometheusFakeProvider = prometheusFakeProvider;
        this.client = new KubernetesClientBuilder().build();
        log.info(
                "KubernetesDeployBackend ready (namespace={}, master={})",
                namespace,
                client.getConfiguration().getMasterUrl());
    }

    @PreDestroy
    void close() {
        try {
            client.close();
        } catch (Exception ignored) {
            // Best-effort cleanup; nothing to do.
        }
    }

    @Override
    public Map<String, Object> history(String scope) {
        String name = deploymentName(scope);
        Deployment dep = getDeployment(name);
        if (dep == null) {
            return missing(scope, name);
        }
        List<ReplicaSet> rsets = ownedReplicaSets(dep);
        List<Map<String, Object>> rollouts = new ArrayList<>();
        for (int i = 0; i < rsets.size(); i++) {
            ReplicaSet rs = rsets.get(i);
            String rev = revisionFromAnnotation(rs.getMetadata().getAnnotations());
            String image = imageOf(rs);
            String when = relativeWhen(rs.getMetadata().getCreationTimestamp());
            String kind = (i == rsets.size() - 1) ? "canary" : "rollout";
            rollouts.add(
                    Map.of(
                            "revision", "rev-" + rev,
                            "image", image,
                            "kind", kind,
                            "when", when));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("service", scope);
        result.put("deployment", name);
        result.put("rollouts", rollouts);
        result.put(
                "signal",
                rollouts.isEmpty()
                        ? "no rollouts found for " + name
                        : "canary cutover at "
                                + rollouts.get(rollouts.size() - 1).get("when")
                                + " ("
                                + rollouts.get(rollouts.size() - 1).get("revision")
                                + ")");
        return result;
    }

    @Override
    public Map<String, Object> currentRevision(String scope) {
        String name = deploymentName(scope);
        Deployment dep = getDeployment(name);
        if (dep == null) {
            return missing(scope, name);
        }
        String rev = revisionFromAnnotation(dep.getMetadata().getAnnotations());
        return Map.of(
                "service",
                scope,
                "deployment",
                name,
                "revision",
                "rev-" + rev,
                "signal",
                "current revision rev-" + rev);
    }

    @Override
    public Map<String, Object> rollback(String scope, Map<String, Object> arguments) {
        String target = (String) arguments.get("target_revision");
        if (target == null || target.isBlank()) {
            throw new IllegalArgumentException("rollback requires target_revision");
        }
        String name = deploymentName(scope);
        Deployment dep = getDeployment(name);
        if (dep == null) {
            throw new IllegalStateException("deployment " + name + " not found in " + namespace);
        }
        String targetRevisionNum =
                target.startsWith("rev-") ? target.substring("rev-".length()) : target;

        ReplicaSet targetRs =
                ownedReplicaSets(dep).stream()
                        .filter(
                                rs ->
                                        targetRevisionNum.equals(
                                                revisionFromAnnotation(
                                                        rs.getMetadata().getAnnotations())))
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "revision "
                                                        + target
                                                        + " not found for deployment "
                                                        + name));
        String targetImage = imageOf(targetRs);

        // Copy the entire pod template from the target ReplicaSet. This mirrors
        // `kubectl rollout undo --to-revision=N` semantics: image, env vars,
        // command, labels — every field — reverts atomically. Without this,
        // env-var-driven behaviour (e.g. SPIKE_MODE) would stay at v2 even
        // after the image flips back to v1.
        client.apps()
                .deployments()
                .inNamespace(namespace)
                .withName(name)
                .edit(
                        d -> {
                            d.getSpec()
                                    .getTemplate()
                                    .setSpec(targetRs.getSpec().getTemplate().getSpec());
                            // Preserve the deployment's selector labels on the template metadata
                            // so the Deployment controller does not reject the update.
                            d.getSpec()
                                    .getTemplate()
                                    .getMetadata()
                                    .setLabels(
                                            targetRs.getSpec()
                                                    .getTemplate()
                                                    .getMetadata()
                                                    .getLabels());
                            return d;
                        });

        // Courtesy bridge to Phase 2: keep the Prometheus fake (if present) in
        // sync so the agent's verify step observes a recovery.
        PrometheusFakeBackend prometheusFake = prometheusFakeProvider.getIfAvailable();
        if (prometheusFake != null) {
            prometheusFake.setTrajectory(scope, PrometheusFakeBackend.Trajectory.RECOVERED);
        }

        return Map.of(
                "service", scope,
                "deployment", name,
                "rolled_back_to", target,
                "image", targetImage,
                "status", "succeeded",
                "signal", "rollback to " + target + " succeeded (image=" + targetImage + ")");
    }

    @Override
    public Map<String, Object> scale(String scope, Map<String, Object> arguments) {
        String name = deploymentName(scope);
        int replicas = ((Number) arguments.getOrDefault("replicas", 1)).intValue();
        client.apps().deployments().inNamespace(namespace).withName(name).scale(replicas);
        return Map.of(
                "service", scope,
                "deployment", name,
                "replicas", replicas,
                "status", "succeeded",
                "signal", "scaled " + name + " to " + replicas + " replicas");
    }

    @Override
    public Map<String, Object> restart(String scope) {
        String name = deploymentName(scope);
        String stamp = Instant.now().toString();
        client.apps()
                .deployments()
                .inNamespace(namespace)
                .withName(name)
                .edit(
                        d -> {
                            d.getSpec()
                                    .getTemplate()
                                    .getMetadata()
                                    .getAnnotations()
                                    .put(RESTART_ANNOTATION, stamp);
                            return d;
                        });
        return Map.of(
                "service",
                scope,
                "deployment",
                name,
                "status",
                "restart-triggered",
                "signal",
                "rolling restart of " + name + " triggered");
    }

    private Deployment getDeployment(String name) {
        return client.apps().deployments().inNamespace(namespace).withName(name).get();
    }

    private List<ReplicaSet> ownedReplicaSets(Deployment dep) {
        String depUid = dep.getMetadata().getUid();
        return client.apps().replicaSets().inNamespace(namespace).list().getItems().stream()
                .filter(rs -> hasOwner(rs, depUid))
                .sorted(
                        Comparator.comparingInt(
                                rs ->
                                        Integer.parseInt(
                                                revisionFromAnnotation(
                                                        rs.getMetadata().getAnnotations()))))
                .toList();
    }

    private static boolean hasOwner(ReplicaSet rs, String depUid) {
        List<OwnerReference> owners = rs.getMetadata().getOwnerReferences();
        if (owners == null) {
            return false;
        }
        return owners.stream().anyMatch(o -> Objects.equals(o.getUid(), depUid));
    }

    private static String revisionFromAnnotation(Map<String, String> annotations) {
        if (annotations == null) {
            return "0";
        }
        return Optional.ofNullable(annotations.get(REVISION_ANNOTATION)).orElse("0");
    }

    private static String imageOf(ReplicaSet rs) {
        return rs.getSpec().getTemplate().getSpec().getContainers().get(0).getImage();
    }

    private static String relativeWhen(String creationTimestamp) {
        if (creationTimestamp == null || creationTimestamp.isBlank()) {
            return "T-?";
        }
        try {
            long secs =
                    Instant.now().getEpochSecond()
                            - Instant.parse(creationTimestamp).getEpochSecond();
            if (secs < 60) {
                return "T-" + secs + "s";
            }
            return "T-" + (secs / 60) + "m";
        } catch (Exception e) {
            return "T-?";
        }
    }

    private String deploymentName(String scope) {
        return scope + deploymentSuffix;
    }

    private Map<String, Object> missing(String scope, String deploymentName) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("service", scope);
        result.put("deployment", deploymentName);
        result.put("rollouts", List.of());
        result.put("signal", "deployment " + deploymentName + " not found in " + namespace);
        return result;
    }
}
