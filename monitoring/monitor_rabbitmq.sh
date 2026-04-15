#!/usr/bin/env bash
# monitor_rabbitmq.sh — Polls RabbitMQ management API every INTERVAL seconds.
# Usage: ./monitor_rabbitmq.sh [interval_seconds] [output_file]
# Example: ./monitor_rabbitmq.sh 5 rabbitmq_metrics.csv
#
# Requires: curl, jq
# Metrics collected (per interval, summed across all room.* queues):
#   - Total messages ready (queue depth)
#   - Total messages unacknowledged
#   - Publish rate (messages/sec incoming)
#   - Deliver rate (messages/sec outgoing)
#   - Consumer count

set -euo pipefail

RABBIT_HOST="${RABBIT_HOST:-localhost}"
RABBIT_MGMT_PORT="${RABBIT_MGMT_PORT:-15672}"
RABBIT_USER="${RABBIT_USER:-guest}"
RABBIT_PASS="${RABBIT_PASS:-guest}"
RABBIT_VHOST="${RABBIT_VHOST:-%2F}"   # URL-encoded "/"

INTERVAL="${1:-5}"
OUTPUT="${2:-rabbitmq_metrics_$(date +%Y%m%d_%H%M%S).csv}"

BASE_URL="http://$RABBIT_HOST:$RABBIT_MGMT_PORT/api"

# ── Verify connection ──────────────────────────────────────────────────────────
echo "Connecting to RabbitMQ management at $RABBIT_HOST:$RABBIT_MGMT_PORT ..."
curl -sf -u "$RABBIT_USER:$RABBIT_PASS" "$BASE_URL/overview" > /dev/null \
    || { echo "ERROR: Cannot reach RabbitMQ management API. Is the management plugin enabled?"; exit 1; }
echo "Connected. Sampling every ${INTERVAL}s. Output: $OUTPUT"

# ── CSV header ─────────────────────────────────────────────────────────────────
echo "timestamp,queue_count,messages_ready,messages_unacked,publish_rate,deliver_rate,consumer_count" \
    | tee "$OUTPUT"

while true; do
    TS=$(date '+%Y-%m-%dT%H:%M:%S')

    # Fetch all queues matching pattern "room.*" in the default vhost
    QUEUES=$(curl -sf -u "$RABBIT_USER:$RABBIT_PASS" \
        "$BASE_URL/queues/$RABBIT_VHOST" 2>/dev/null || echo "[]")

    # Filter to room.* queues and aggregate
    STATS=$(echo "$QUEUES" | jq -r '
        [ .[] | select(.name | startswith("room.")) ] |
        {
            queue_count:      length,
            messages_ready:   (map(.messages_ready   // 0) | add // 0),
            messages_unacked: (map(.messages_unacknowledged // 0) | add // 0),
            publish_rate:     (map(.message_stats.publish_details.rate // 0) | add // 0),
            deliver_rate:     (map(.message_stats.deliver_details.rate // 0) | add // 0),
            consumer_count:   (map(.consumers // 0) | add // 0)
        } |
        [.queue_count, .messages_ready, .messages_unacked,
         (.publish_rate | floor), (.deliver_rate | floor), .consumer_count] |
        @csv
    ' 2>/dev/null || echo "0,0,0,0,0,0")

    echo "$TS,$STATS" | tee -a "$OUTPUT"
    sleep "$INTERVAL"
done