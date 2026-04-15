#!/bin/bash
# Deploy consumer-v3 to EC2 #2.
# Redis is on the server EC2 ($SERVER_IP) — consumer connects there.
# Usage: ./deploy-consumer.sh
# Prereq: source ec2-hosts.sh

set -e

if [ -z "$CONSUMER_IP" ] || [ -z "$SERVER_IP" ] || [ -z "$DB_IP" ]; then
    echo "ERROR: run 'source ec2-hosts.sh' first"
    exit 1
fi

echo "=== Building consumer-v3 ==="
cd consumer-v3 && mvn package -q -DskipTests && cd ..

JAR=consumer-v3/target/consumer-v3-1.0-SNAPSHOT.jar

echo "=== Deploying JAR to $CONSUMER_IP ==="
scp -i $SSH_KEY $JAR $SSH_USER@$CONSUMER_IP:~/consumer-v3.jar

echo "=== Restarting consumer-v3 ==="
ssh -i $SSH_KEY $SSH_USER@$CONSUMER_IP bash << EOF
    pkill -f 'consumer-v3' || true
    sleep 1
    # Args: redisHost dbHost batchSize flushIntervalMs
    # Redis is on the server EC2 — connect there
    nohup java -jar consumer-v3.jar $SERVER_IP $DB_IP 500 500 > consumer.log 2>&1 &

    sleep 2
    echo "Processes running:"
    pgrep -a -f consumer-v3
EOF

echo "=== Done — consumer-v3 running on $CONSUMER_IP, Redis at $SERVER_IP ==="