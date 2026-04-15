#!/usr/bin/env bash
# collect_all.sh — Master monitoring script.
# Starts all three monitors in parallel, runs for a given duration (or until Ctrl+C),
# then prints a summary from each CSV.
#
# Usage: ./collect_all.sh [duration_seconds] [interval_seconds] [output_dir]
# Example:
#   ./collect_all.sh 1800 5 results/endurance    # 30-minute run, 5s sample interval
#   ./collect_all.sh 0    5 results/baseline      # run until Ctrl+C (duration=0)
#
# Output files in output_dir/:
#   db_metrics.csv, rabbitmq_metrics.csv, system_metrics.csv

set -euo pipefail

DURATION="${1:-0}"        # seconds; 0 = run until Ctrl+C
INTERVAL="${2:-5}"        # polling interval in seconds
OUT_DIR="${3:-results/$(date +%Y%m%d_%H%M%S)}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

mkdir -p "$OUT_DIR"
echo "=== ChatFlow Monitoring ==="
echo "Duration : ${DURATION}s (0 = until Ctrl+C)"
echo "Interval : ${INTERVAL}s"
echo "Output   : $OUT_DIR/"
echo ""

# ── Start sub-monitors ─────────────────────────────────────────────────────────
bash "$SCRIPT_DIR/monitor_db.sh"       "$INTERVAL" "$OUT_DIR/db_metrics.csv"       &
PID_DB=$!

bash "$SCRIPT_DIR/monitor_rabbitmq.sh" "$INTERVAL" "$OUT_DIR/rabbitmq_metrics.csv" &
PID_RABBIT=$!

bash "$SCRIPT_DIR/monitor_system.sh"   "$INTERVAL" "$OUT_DIR/system_metrics.csv"   &
PID_SYS=$!

echo "Monitors started: db=$PID_DB  rabbitmq=$PID_RABBIT  system=$PID_SYS"
echo "Press Ctrl+C to stop early."
echo ""

cleanup() {
    echo ""
    echo "Stopping monitors..."
    kill "$PID_DB"     2>/dev/null || true
    kill "$PID_RABBIT" 2>/dev/null || true
    kill "$PID_SYS"    2>/dev/null || true
    wait 2>/dev/null || true
    echo "All monitors stopped."
    print_summary
}
trap cleanup EXIT INT TERM

# ── Wait for duration or Ctrl+C ────────────────────────────────────────────────
if [ "$DURATION" -gt 0 ]; then
    echo "Running for ${DURATION}s..."
    sleep "$DURATION"
else
    # Run until interrupted
    while true; do sleep 60; done
fi

# ── Summary ────────────────────────────────────────────────────────────────────
print_summary() {
    echo ""
    echo "============================================================"
    echo " MONITORING SUMMARY"
    echo "============================================================"

    if [ -f "$OUT_DIR/db_metrics.csv" ]; then
        echo ""
        echo "-- Database Metrics ($OUT_DIR/db_metrics.csv) --"
        echo "Samples collected : $(( $(wc -l < "$OUT_DIR/db_metrics.csv") - 1 ))"
        # Max connections, average TPS, final message count
        awk -F',' 'NR>1 {
            if ($2+0 > max_conn) max_conn=$2+0;
            tps_sum += $4+0; tps_n++;
            last_msg=$9+0
        }
        END {
            printf "  Max active conns : %d\n", max_conn;
            if (tps_n>0) printf "  Avg TPS          : %.0f\n", tps_sum/tps_n;
            printf "  Final msg count  : %d\n", last_msg;
        }' "$OUT_DIR/db_metrics.csv"
    fi

    if [ -f "$OUT_DIR/rabbitmq_metrics.csv" ]; then
        echo ""
        echo "-- RabbitMQ Metrics ($OUT_DIR/rabbitmq_metrics.csv) --"
        echo "Samples collected : $(( $(wc -l < "$OUT_DIR/rabbitmq_metrics.csv") - 1 ))"
        awk -F',' 'NR>1 {
            if ($3+0 > max_depth) max_depth=$3+0;
            pub_sum += $5+0; del_sum += $6+0; n++
        }
        END {
            printf "  Peak queue depth : %d\n", max_depth;
            if (n>0) {
                printf "  Avg publish rate : %.0f msg/s\n", pub_sum/n;
                printf "  Avg deliver rate : %.0f msg/s\n", del_sum/n;
            }
        }' "$OUT_DIR/rabbitmq_metrics.csv"
    fi

    if [ -f "$OUT_DIR/system_metrics.csv" ]; then
        echo ""
        echo "-- System Metrics ($OUT_DIR/system_metrics.csv) --"
        echo "Samples collected : $(( $(wc -l < "$OUT_DIR/system_metrics.csv") - 1 ))"
        awk -F',' 'NR>1 {
            cpu_sum += $2+0;
            if ($4+0 > max_mem) max_mem=$4+0;
            mem_sum += $5+0; n++
        }
        END {
            if (n>0) {
                printf "  Avg CPU%%         : %.1f\n", cpu_sum/n;
                printf "  Peak mem used%%   : %d\n",  max_mem;
                printf "  Avg mem used%%    : %.1f\n", mem_sum/n;
            }
        }' "$OUT_DIR/system_metrics.csv"
    fi

    echo ""
    echo "All raw data saved to: $OUT_DIR/"
    echo "============================================================"
}