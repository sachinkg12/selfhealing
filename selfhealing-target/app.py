"""selfhealing-target — metrics-emitter that simulates the service the agent
observes through MCP. Exposes:

  GET  /api/search       — serves a request; latency depends on SPIKE_MODE.
  POST /admin/spike      — flips spike mode on (sticky for the pod lifetime).
  POST /admin/recover    — clears spike mode.
  GET  /metrics          — Prometheus exposition (handled by prometheus_client).
  GET  /healthz          — readiness probe.

The Prometheus histogram `http_request_duration_seconds{service="search-api"}`
is the metric the agent queries via the Prometheus MCP server.

Logs are pushed **directly** to Loki via its HTTP push API (Promtail bypass).
This keeps the log-shipping path self-contained and well-typed, and removes
one moving part from the demo cluster.
"""

from __future__ import annotations

import json
import logging
import os
import random
import threading
import time
import urllib.request
import urllib.error
from queue import Queue, Empty
from threading import Lock

from flask import Flask, jsonify
from prometheus_client import (
    CONTENT_TYPE_LATEST,
    Histogram,
    generate_latest,
)

SERVICE = os.getenv("SERVICE_NAME", "search-api")
SPIKE_MODE_DEFAULT = os.getenv("SPIKE_MODE", "false").lower() == "true"
SPIKE_LATENCY_SECONDS = float(os.getenv("SPIKE_LATENCY_SECONDS", "1.8"))
BASELINE_LATENCY_MIN_MS = int(os.getenv("BASELINE_LATENCY_MIN_MS", "180"))
BASELINE_LATENCY_MAX_MS = int(os.getenv("BASELINE_LATENCY_MAX_MS", "230"))
LOKI_PUSH_URL = os.getenv("LOKI_PUSH_URL", "http://loki.selfhealing.svc:3100/loki/api/v1/push")
LOKI_NAMESPACE = os.getenv("LOKI_NAMESPACE", "selfhealing")
LOKI_BATCH_FLUSH_INTERVAL = float(os.getenv("LOKI_BATCH_FLUSH_INTERVAL", "1.0"))

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(name)s %(message)s",
)
log = logging.getLogger("selfhealing-target")

app = Flask(__name__)

REQUEST_DURATION = Histogram(
    "http_request_duration_seconds",
    "Request duration",
    labelnames=("service", "handler"),
    buckets=(0.05, 0.1, 0.2, 0.5, 1.0, 1.5, 2.0, 5.0),
)


class SpikeState:
    """Mutable spike toggle. Defaults from env so v1/v2 manifests can be
    distinguished by env var alone (no rebuild needed)."""

    def __init__(self, initial: bool) -> None:
        self._on = initial
        self._lock = Lock()

    def on(self) -> bool:
        with self._lock:
            return self._on

    def turn_on(self) -> None:
        with self._lock:
            self._on = True

    def turn_off(self) -> None:
        with self._lock:
            self._on = False


class LokiPusher:
    """Background pusher that batches log lines and POSTs them to Loki. A
    failed push is logged locally but never blocks the request path."""

    def __init__(self, url: str, namespace: str, service: str) -> None:
        self.url = url
        self.namespace = namespace
        self.service = service
        self.queue: Queue[tuple[str, str]] = Queue()
        self.stopped = False
        threading.Thread(target=self._loop, daemon=True).start()

    def push(self, severity: str, message: str) -> None:
        ts_ns = str(int(time.time() * 1_000_000_000))
        line = f"severity={severity} service={self.service} {message}"
        self.queue.put((ts_ns, line))

    def _loop(self) -> None:
        while not self.stopped:
            batch: list[tuple[str, str]] = []
            try:
                first = self.queue.get(timeout=LOKI_BATCH_FLUSH_INTERVAL)
                batch.append(first)
            except Empty:
                continue
            # Drain whatever is already pending.
            while True:
                try:
                    batch.append(self.queue.get_nowait())
                except Empty:
                    break
            try:
                self._post(batch)
            except Exception as e:  # noqa: BLE001
                log.warning("loki push failed (%d entries): %s", len(batch), e)

    def _post(self, batch: list[tuple[str, str]]) -> None:
        payload = {
            "streams": [
                {
                    "stream": {
                        "service": self.service,
                        "namespace": self.namespace,
                        "source": "selfhealing-target",
                    },
                    "values": [[ts, line] for ts, line in batch],
                }
            ]
        }
        data = json.dumps(payload).encode("utf-8")
        req = urllib.request.Request(
            self.url,
            data=data,
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        with urllib.request.urlopen(req, timeout=5):
            pass


SPIKE = SpikeState(SPIKE_MODE_DEFAULT)
LOKI = LokiPusher(LOKI_PUSH_URL, LOKI_NAMESPACE, SERVICE)


@app.route("/api/search")
def search() -> tuple[str, int]:
    start = time.time()
    spike_on = SPIKE.on()
    if spike_on:
        trace_id = _trace_id()
        msg = f"SocketTimeoutException calling upstream ranker trace_id={trace_id}"
        log.error(msg)
        LOKI.push("ERROR", msg)
        time.sleep(SPIKE_LATENCY_SECONDS)
    else:
        time.sleep(random.uniform(BASELINE_LATENCY_MIN_MS, BASELINE_LATENCY_MAX_MS) / 1000.0)
    duration = time.time() - start
    REQUEST_DURATION.labels(service=SERVICE, handler="/api/search").observe(duration)
    return "ok\n", 200


@app.route("/admin/spike", methods=["POST"])
def admin_spike() -> tuple[str, int]:
    SPIKE.turn_on()
    log.warning("spike mode turned ON")
    LOKI.push("WARN", "spike mode turned ON")
    return jsonify({"service": SERVICE, "spike": True}).get_data(as_text=True), 200


@app.route("/admin/recover", methods=["POST"])
def admin_recover() -> tuple[str, int]:
    SPIKE.turn_off()
    log.info("spike mode turned OFF")
    LOKI.push("INFO", "spike mode turned OFF")
    return jsonify({"service": SERVICE, "spike": False}).get_data(as_text=True), 200


@app.route("/metrics")
def metrics() -> tuple[bytes, int, dict[str, str]]:
    return generate_latest(), 200, {"Content-Type": CONTENT_TYPE_LATEST}


@app.route("/healthz")
def healthz() -> tuple[str, int]:
    return "ok", 200


def _trace_id() -> str:
    return f"trc-{random.randint(0, 0xFFFFFF):06x}"


if __name__ == "__main__":
    port = int(os.getenv("PORT", "8080"))
    log.info("selfhealing-target starting service=%s", SERVICE)
    log.info(
        "config: SPIKE_MODE=%s SPIKE_LATENCY_SECONDS=%s loki=%s",
        SPIKE.on(),
        SPIKE_LATENCY_SECONDS,
        LOKI_PUSH_URL,
    )

    def _self_traffic() -> None:
        while True:
            try:
                with app.test_client() as c:
                    c.get("/api/search")
            except Exception:  # noqa: BLE001
                pass
            time.sleep(0.5)

    threading.Thread(target=_self_traffic, daemon=True).start()
    app.run(host="0.0.0.0", port=port, threaded=True)
