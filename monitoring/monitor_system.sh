#!/usr/bin/env bash
# monitor_system.sh — Polls CPU, memory, and disk metrics every INTERVAL seconds.
# Usage: ./monitor_system.sh [interval_seconds] [output_file]
# Example: ./monitor_system.sh 5 system_metrics.csv
#
# Works on macOS and Linux. Run this on the consumer/server host.
# Metrics collected (per interval):
#   - CPU usage % (all cores)
#   - Memory used MB / total MB / used %
#   - Disk read/write KB (delta since last sample) for the primary disk
#   - Java process CPU % (consumer-v3 and server-v3, if running)

set -euo pipefail

INTERVAL="${1:-5}"
OUTPUT="${2:-system_metrics_$(date +%Y%m%d_%H%M%S).csv}"

OS=$(uname -s)

echo "System monitor started (OS=$OS). Sampling every ${INTERVAL}s. Output: $OUTPUT"

# ── CSV header ─────────────────────────────────────────────────────────────────
echo "timestamp,cpu_pct,mem_used_mb,mem_total_mb,mem_used_pct,disk_read_kb,disk_write_kb,consumer_cpu_pct,server_cpu_pct" \
    | tee "$OUTPUT"

prev_disk_read=0
prev_disk_write=0

get_cpu_pct() {
    if [ "$OS" = "Darwin" ]; then
        # macOS: use top in batch mode for one sample
        top -l 2 -n 0 | awk '/^CPU usage/{print $3}' | tail -1 | tr -d '%'
    else
        # Linux: read /proc/stat twice with a 0.2s gap
        read -r cpu1 _ idle1 _ < <(grep '^cpu ' /proc/stat | awk '{print $1,$2+$3+$4+$5+$6+$7+$8, $5, $2+$3+$4+$5+$6+$7+$8}')
        sleep 0.2
        read -r cpu2 _ idle2 _ < <(grep '^cpu ' /proc/stat | awk '{print $1,$2+$3+$4+$5+$6+$7+$8, $5, $2+$3+$4+$5+$6+$7+$8}')
        total=$(( cpu2 - cpu1 ))
        idle=$(( idle2 - idle1 ))
        echo $(( (total - idle) * 100 / (total > 0 ? total : 1) ))
    fi
}

get_mem() {
    if [ "$OS" = "Darwin" ]; then
        # macOS vm_stat + sysctl
        TOTAL_BYTES=$(sysctl -n hw.memsize)
        TOTAL_MB=$(( TOTAL_BYTES / 1024 / 1024 ))
        # vm_stat reports pages; page size is usually 4096 bytes
        PAGE_SIZE=$(pagesize 2>/dev/null || echo 4096)
        FREE_PAGES=$(vm_stat | awk '/^Pages free/{gsub(/\./, "", $3); print $3}')
        SPECULATIVE=$(vm_stat | awk '/^Pages speculative/{gsub(/\./, "", $3); print $3}')
        FREE_MB=$(( (FREE_PAGES + SPECULATIVE) * PAGE_SIZE / 1024 / 1024 ))
        USED_MB=$(( TOTAL_MB - FREE_MB ))
        USED_PCT=$(( USED_MB * 100 / TOTAL_MB ))
        echo "$USED_MB,$TOTAL_MB,$USED_PCT"
    else
        # Linux: /proc/meminfo
        TOTAL_KB=$(awk '/^MemTotal/{print $2}' /proc/meminfo)
        AVAIL_KB=$(awk '/^MemAvailable/{print $2}' /proc/meminfo)
        USED_KB=$(( TOTAL_KB - AVAIL_KB ))
        TOTAL_MB=$(( TOTAL_KB / 1024 ))
        USED_MB=$(( USED_KB  / 1024 ))
        USED_PCT=$(( USED_MB * 100 / TOTAL_MB ))
        echo "$USED_MB,$TOTAL_MB,$USED_PCT"
    fi
}

get_disk_delta() {
    if [ "$OS" = "Darwin" ]; then
        # Use iostat for macOS (returns KB/s; multiply by interval for KB)
        DISK=$(iostat -d -K disk0 2>/dev/null | awk 'NR==3{print $3, $4}')
        READ_KB=$(echo "$DISK" | awk '{print $1}')
        WRITE_KB=$(echo "$DISK" | awk '{print $2}')
        echo "${READ_KB:-0},${WRITE_KB:-0}"
    else
        # Linux: /proc/diskstats — find primary disk (sda or nvme0n1)
        DISK_DEV=$(lsblk -d -n -o NAME | grep -E '^(sda|nvme0n1|vda|xvda)' | head -1)
        if [ -n "$DISK_DEV" ]; then
            SECTORS=$(awk -v dev="$DISK_DEV" '$3==dev{print $6, $10}' /proc/diskstats)
            CUR_READ=$(echo "$SECTORS"  | awk '{print $1}')
            CUR_WRITE=$(echo "$SECTORS" | awk '{print $2}')
            # Sectors are 512 bytes; delta / 2 = KB
            DELTA_READ=$(( (CUR_READ  - prev_disk_read)  / 2 ))
            DELTA_WRITE=$(( (CUR_WRITE - prev_disk_write) / 2 ))
            prev_disk_read=$CUR_READ
            prev_disk_write=$CUR_WRITE
            echo "$DELTA_READ,$DELTA_WRITE"
        else
            echo "0,0"
        fi
    fi
}

get_java_cpu() {
    local pattern="$1"
    if command -v ps > /dev/null 2>&1; then
        ps aux | grep "$pattern" | grep -v grep | awk '{sum += $3} END {printf "%.1f", sum+0}'
    else
        echo "0.0"
    fi
}

while true; do
    TS=$(date '+%Y-%m-%dT%H:%M:%S')

    CPU_PCT=$(get_cpu_pct)
    MEM=$(get_mem)
    DISK=$(get_disk_delta)
    CONSUMER_CPU=$(get_java_cpu "consumer-v3")
    SERVER_CPU=$(get_java_cpu "server-v3")

    ROW="$TS,${CPU_PCT:-0},$MEM,$DISK,${CONSUMER_CPU:-0},${SERVER_CPU:-0}"
    echo "$ROW" | tee -a "$OUTPUT"

    sleep "$INTERVAL"
done
