#!/usr/bin/env bash
# bench.sh — run N happy-path scenarios against the full real stack, capture
# LLM round-trips, token counts, end-to-end latency, and kubectl evidence.
#
# Usage: scripts/bench.sh [N]   (default N=3)

set -euo pipefail

N=${1:-3}
OUT=bench-results
mkdir -p "$OUT"

echo "running $N happy-path runs in full-real mode (LLM + K8s + Prom + Loki + OAuth)"
echo "writing logs to $OUT/run-N.log"
echo ""

for i in $(seq 1 "$N"); do
    echo "=== run $i / $N ==="
    echo "  resetting cluster..."
    make kind-clean > /dev/null 2>&1
    make kind-observability > /dev/null 2>&1
    make kind-deploy > /dev/null 2>&1
    make kind-deploy-v2 > /dev/null 2>&1
    echo "  cluster ready; sleeping 15s for Prometheus to see the spike"
    sleep 15

    START=$(date +%s)
    mvn -DskipTests spring-boot:run \
        -Dspring-boot.run.arguments="--selfhealing.planner.mode=llm \
            --selfhealing.k8s.enabled=true \
            --selfhealing.observability.enabled=true \
            --selfhealing.oauth.enabled=true \
            --scenario happy-path" \
        > "$OUT/run-$i.log" 2>&1 || true
    END=$(date +%s)
    ELAPSED=$((END - START))

    STEPS=$(grep -c "LLM planner step" "$OUT/run-$i.log" || echo 0)
    DEDUPS=$(grep -c "already-called" "$OUT/run-$i.log" || echo 0)
    INPUT=$(grep "LLM planner step" "$OUT/run-$i.log" | grep -oE 'input=[0-9]+' | sed 's/input=//' | paste -sd+ - | bc || echo 0)
    OUTPUT=$(grep "LLM planner step" "$OUT/run-$i.log" | grep -oE 'output=[0-9]+' | sed 's/output=//' | paste -sd+ - | bc || echo 0)
    LATENCY=$(grep "LLM planner step" "$OUT/run-$i.log" | grep -oE 'latency=[0-9]+ms' | sed 's/latency=//;s/ms//' | paste -sd+ - | bc || echo 0)
    DECISION=$(grep -oE '"decision" : "(remediate|refuse)"' "$OUT/run-$i.log" | head -1 | sed 's/.*"\(remediate\|refuse\)".*/\1/' || echo "?")
    CONFIDENCE=$(grep -oE '"confidence" : [0-9.]+' "$OUT/run-$i.log" | head -1 | sed 's/.* : //' || echo "?")

    echo "  steps=$STEPS dedups=$DEDUPS llm_latency_ms=$LATENCY input_tokens=$INPUT output_tokens=$OUTPUT end_to_end_s=$ELAPSED decision=$DECISION confidence=$CONFIDENCE"
done

echo ""
echo "logs in $OUT/"
