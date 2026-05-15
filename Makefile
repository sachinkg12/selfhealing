CLUSTER ?= selfhealing
NAMESPACE ?= selfhealing
INFRA := infra
TARGET := selfhealing-target

.PHONY: help build verify spotless run run-llm run-llm-k8s \
        target-image target-load \
        kind-up kind-down kind-ready kind-clean \
        kind-deploy kind-deploy-v2 kind-rollout kind-rollback-manual \
        kind-observability kind-bootstrap

help:
	@echo "Build:"
	@echo "  make build              - mvn compile"
	@echo "  make verify             - mvn verify (Spotless check)"
	@echo "  make spotless           - mvn spotless:apply"
	@echo ""
	@echo "Run agent:"
	@echo "  make run                - all scenarios, deterministic"
	@echo "  make run-llm            - happy-path LLM mode"
	@echo "  make run-llm-k8s        - happy-path LLM against real K8s + observability"
	@echo ""
	@echo "Metrics-emitter (selfhealing-target):"
	@echo "  make target-image       - docker build selfhealing-target:v1 + :v2"
	@echo "  make target-load        - kind load the v1/v2 images"
	@echo ""
	@echo "Cluster lifecycle:"
	@echo "  make kind-up            - create kind cluster '$(CLUSTER)'"
	@echo "  make kind-down          - delete cluster"
	@echo "  make kind-clean         - delete namespace (keeps cluster)"
	@echo "  make kind-bootstrap     - up, observability, app v1, app v2"
	@echo ""
	@echo "Workload:"
	@echo "  make kind-deploy        - apply v1 manifests (revision 1)"
	@echo "  make kind-deploy-v2     - patch to v2 (revision 2, canary, spike on)"
	@echo "  make kind-rollout       - kubectl rollout history"
	@echo ""
	@echo "Observability (Phase 2):"
	@echo "  make kind-observability - install Prometheus + Loki + Promtail"

build:
	mvn -DskipTests compile

verify:
	mvn -DskipTests verify

spotless:
	mvn spotless:apply

run:
	mvn -q -DskipTests spring-boot:run -Dspring-boot.run.arguments="--all"

run-llm:
	mvn -q -DskipTests spring-boot:run -Dspring-boot.run.arguments="--selfhealing.planner.mode=llm --scenario happy-path"

run-llm-k8s:
	mvn -q -DskipTests spring-boot:run -Dspring-boot.run.arguments="--selfhealing.planner.mode=llm --selfhealing.k8s.enabled=true --selfhealing.observability.enabled=true --scenario happy-path"

target-image:
	cd $(TARGET) && docker build -t selfhealing-target:v1 . && docker tag selfhealing-target:v1 selfhealing-target:v2

target-load:
	kind load docker-image --name $(CLUSTER) selfhealing-target:v1 selfhealing-target:v2

kind-up:
	@if kind get clusters | grep -q '^$(CLUSTER)$$'; then \
	  echo "cluster $(CLUSTER) already exists"; \
	else \
	  kind create cluster --name $(CLUSTER) --config $(INFRA)/kind-config.yaml; \
	fi
	kubectl config use-context kind-$(CLUSTER)

kind-down:
	kind delete cluster --name $(CLUSTER)

kind-ready:
	kubectl --context kind-$(CLUSTER) wait --for=condition=Ready nodes --all --timeout=120s

kind-clean:
	kubectl --context kind-$(CLUSTER) delete namespace $(NAMESPACE) --ignore-not-found

kind-deploy:
	kubectl --context kind-$(CLUSTER) apply -f $(INFRA)/sample-app/namespace.yaml
	kubectl --context kind-$(CLUSTER) apply -f $(INFRA)/sample-app/search-api-v1.yaml
	kubectl --context kind-$(CLUSTER) -n $(NAMESPACE) rollout status deployment/search-api --timeout=180s

kind-deploy-v2:
	kubectl --context kind-$(CLUSTER) -n $(NAMESPACE) patch deployment search-api --patch-file $(INFRA)/sample-app/search-api-v2-patch.yaml
	kubectl --context kind-$(CLUSTER) -n $(NAMESPACE) rollout status deployment/search-api --timeout=180s

kind-rollout:
	kubectl --context kind-$(CLUSTER) -n $(NAMESPACE) rollout history deployment/search-api

kind-rollback-manual:
	kubectl --context kind-$(CLUSTER) -n $(NAMESPACE) rollout undo deployment/search-api

kind-observability:
	kubectl --context kind-$(CLUSTER) apply -f $(INFRA)/sample-app/namespace.yaml
	kubectl --context kind-$(CLUSTER) apply -f $(INFRA)/observability/prometheus.yaml
	kubectl --context kind-$(CLUSTER) apply -f $(INFRA)/observability/loki.yaml
	kubectl --context kind-$(CLUSTER) apply -f $(INFRA)/observability/promtail.yaml
	kubectl --context kind-$(CLUSTER) -n $(NAMESPACE) rollout status deployment/prometheus --timeout=180s
	kubectl --context kind-$(CLUSTER) -n $(NAMESPACE) rollout status deployment/loki --timeout=180s

kind-bootstrap: kind-up target-load kind-observability kind-deploy kind-deploy-v2
	@echo ""
	@echo "Cluster ready. Prometheus on http://localhost:9090, Loki on http://localhost:3100"
	@echo "search-api at v2 (spike mode on). Run: make run-llm-k8s"
