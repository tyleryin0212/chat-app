#!/bin/bash
# Deploy and run client-v3 on EC2 #4.
# Usage: ./deploy-client.sh [totalMessages] [threads]
# Prereq: source ec2-hosts.sh

set -e

if [ -z "$CLIENT_IP" ] || [ -z "$SERVER_IP" ]; then
    echo "ERROR: run 'source ec2-hosts.sh' first"
    exit 1
fi

TOTAL_MESSAGES="${1:-500000}"
THREADS="${2:-3}"

echo "=== Building client-v3 ==="
cd client-v3 && mvn package -q -DskipTests && cd ..

JAR=client-v3/target/client-v3-1.0-SNAPSHOT.jar

echo "=== Deploying JAR to $CLIENT_IP ==="
scp -i $SSH_KEY $JAR $SSH_USER@$CLIENT_IP:~/client-v3.jar

echo "=== Running load test: $TOTAL_MESSAGES messages, $THREADS threads ==="
ssh -i $SSH_KEY $SSH_USER@$CLIENT_IP \
    "java -jar client-v3.jar $TOTAL_MESSAGES ws://$SERVER_IP:8080/chat/ http://$SERVER_IP:8081/metrics $THREADS 2>&1 | tee client.log"

echo "=== Done ==="