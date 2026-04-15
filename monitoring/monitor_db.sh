#!/usr/bin/env bash
# monitor_db.sh — Polls PostgreSQL metrics every INTERVAL seconds.
# Usage: ./monitor_db.sh [interval_seconds] [output_file]
# Example: ./monitor_db.sh 5 db_metrics.csv
#
# Metrics collected (per interval):
#   - Active connections
#   - Transactions per second (commit + rollback delta)
#   - Lock wait count
#   - Buffer hit ratio (from pg_stat_bgwriter)
#   - Blocks read from disk vs cache (pg_statio_user_tables)
#   - Total messages written so far (chatdb.messages)

set -euo pipefail

DB_HOST="${DB_HOST:-54.188.60.194}"
DB_PORT="${DB_PORT:-5432}"
DB_NAME="${DB_NAME:-chatdb}"
DB_USER="${DB_USER:-chatflow}"
export PGPASSWORD="${DB_PASSWORD:-123456}"

INTERVAL="${1:-5}"
OUTPUT="${2:-db_metrics_$(date +%Y%m%d_%H%M%S).csv}"

PSQL="psql -h $DB_HOST -p $DB_PORT -U $DB_USER -d $DB_NAME -t -A -F,"

# ── Verify connection ──────────────────────────────────────────────────────────
echo "Connecting to PostgreSQL at $DB_HOST:$DB_PORT/$DB_NAME ..."
$PSQL -c "SELECT 1" > /dev/null 2>&1 || { echo "ERROR: Cannot connect to database."; exit 1; }
echo "Connected. Sampling every ${INTERVAL}s. Output: $OUTPUT"

# ── CSV header ─────────────────────────────────────────────────────────────────
echo "timestamp,active_connections,total_connections,tps,lock_waits,buf_hit_ratio_pct,blks_hit,blks_read,messages_total" \
    | tee "$OUTPUT"

prev_xact=0
prev_time=0

while true; do
    NOW=$(date +%s)
    TS=$(date '+%Y-%m-%dT%H:%M:%S')

    # Active and total connections
    CONN=$($PSQL -c "
        SELECT
          SUM(CASE WHEN state = 'active' THEN 1 ELSE 0 END),
          COUNT(*)
        FROM pg_stat_activity
        WHERE datname = '$DB_NAME';" 2>/dev/null || echo "0,0")
    ACTIVE_CONN=$(echo "$CONN" | cut -d',' -f1)
    TOTAL_CONN=$(echo "$CONN"  | cut -d',' -f2)

    # Transactions committed + rolled back (cumulative)
    XACT=$($PSQL -c "
        SELECT xact_commit + xact_rollback
        FROM pg_stat_database
        WHERE datname = '$DB_NAME';" 2>/dev/null || echo "0")

    # TPS = delta xact / delta seconds
    if [ "$prev_xact" -gt 0 ] && [ "$prev_time" -gt 0 ]; then
        DELTA_XACT=$(( XACT - prev_xact ))
        DELTA_TIME=$(( NOW  - prev_time  ))
        TPS=$(( DELTA_XACT / (DELTA_TIME > 0 ? DELTA_TIME : 1) ))
    else
        TPS=0
    fi
    prev_xact=$XACT
    prev_time=$NOW

    # Lock waits
    LOCK_WAITS=$($PSQL -c "
        SELECT COUNT(*)
        FROM pg_stat_activity
        WHERE wait_event_type = 'Lock'
          AND datname = '$DB_NAME';" 2>/dev/null || echo "0")

    # Buffer hit ratio — cumulative across all user tables
    BUF=$($PSQL -c "
        SELECT
          SUM(heap_blks_hit),
          SUM(heap_blks_read)
        FROM pg_statio_user_tables;" 2>/dev/null || echo "0,0")
    BLKS_HIT=$(echo "$BUF"  | cut -d',' -f1)
    BLKS_READ=$(echo "$BUF" | cut -d',' -f2)
    TOTAL_BLKS=$(( BLKS_HIT + BLKS_READ ))
    if [ "$TOTAL_BLKS" -gt 0 ]; then
        # Multiply by 10000 then divide for 2 decimal places without bc
        HIT_RATIO=$(( BLKS_HIT * 10000 / TOTAL_BLKS ))
        HIT_RATIO="${HIT_RATIO:0:-2}.${HIT_RATIO: -2}"
    else
        HIT_RATIO="0.00"
    fi

    # Total messages in DB (write progress)
    MSG_TOTAL=$($PSQL -c "SELECT COUNT(*) FROM messages;" 2>/dev/null || echo "0")

    ROW="$TS,$ACTIVE_CONN,$TOTAL_CONN,$TPS,$LOCK_WAITS,$HIT_RATIO,$BLKS_HIT,$BLKS_READ,$MSG_TOTAL"
    echo "$ROW" | tee -a "$OUTPUT"

    sleep "$INTERVAL"
done