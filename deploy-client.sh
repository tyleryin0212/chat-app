#!/bin/bash
# Build and run client-v3 locally against the EC2 server.
# Usage: ./deploy-client.sh [totalMessages] [threads]
# Prereq: source ec2-hosts.sh

set -e

if [ -z "$SERVER_IP" ] || [ -z "$CONSUMER_IP" ]; then
    echo "ERROR: run 'source ec2-hosts.sh' first"
    exit 1
fi

TOTAL_MESSAGES="${1:-500000}"
THREADS="${2:-3}"

echo "=== Building client-v3 ==="
cd client-v3 && mvn package -q -DskipTests && cd ..

JAR=client-v3/target/client-v3-1.0-SNAPSHOT.jar

echo "=== Running load test locally ==="
echo "  messages=$TOTAL_MESSAGES  threads=$THREADS"
echo "  server=ws://$SERVER_IP:8080/chat/"
echo "  metrics=http://$SERVER_IP:8081/metrics"
echo "  pipeline-stats=http://$SERVER_IP:8081/pipeline-stats"
echo "  consumer-stats=http://$CONSUMER_IP:9090/stats"

java -jar $JAR \
    $TOTAL_MESSAGES \
    ws://$SERVER_IP:8080/chat/ \
    http://$SERVER_IP:8081/metrics \
    $THREADS \
    http://$SERVER_IP:8081/pipeline-stats \
    http://$CONSUMER_IP:9090/stats \
    2>&1 | tee client.log

echo "=== Done — full output saved to client.log ==="