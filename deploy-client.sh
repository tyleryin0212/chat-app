#!/bin/bash
# Build and run client-v3 locally against the EC2 server.
# Usage: ./deploy-client.sh [totalMessages] [threads]
# Prereq: source ec2-hosts.sh

set -e
cd "$(dirname "$0")"

if [ -z "$SERVER1_IP" ] || [ -z "$SERVER2_IP" ] || [ -z "$CONSUMER_IP" ]; then
    echo "ERROR: run 'source ec2-hosts.sh' first"
    exit 1
fi

TOTAL_MESSAGES="${1:-500000}"
THREADS="${2:-20}"

echo "=== Building client-v3 ==="
cd client-v3 && mvn package -q -DskipTests && cd ..

JAR=client-v3/target/client-v3-1.0-SNAPSHOT.jar

echo "=== Running load test locally ==="
echo "  messages=$TOTAL_MESSAGES  threads=$THREADS"
echo "  servers=ws://$SERVER1_IP:8080/chat/,ws://$SERVER2_IP:8080/chat/"
echo "  metrics=http://$SERVER1_IP:8081/metrics"

java -jar $JAR \
    $TOTAL_MESSAGES \
    ws://$SERVER1_IP:8080/chat/,ws://$SERVER2_IP:8080/chat/ \
    http://$SERVER1_IP:8081/metrics \
    $THREADS \
    2>&1 | tee client.log

echo "=== Done — full output saved to client.log ==="