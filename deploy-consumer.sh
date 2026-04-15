#!/bin/bash
# Deploy Redis + consumer-v3 to EC2 #2.
# Redis is co-located with consumer on this EC2 (localhost).
# Usage: ./deploy-consumer.sh
# Prereq: source ec2-hosts.sh

set -e
cd "$(dirname "$0")"

if [ -z "$CONSUMER_IP" ] || [ -z "$DB_IP" ] || [ -z "$REDIS_IP" ]; then
    echo "ERROR: run 'source ec2-hosts.sh' first"
    exit 1
fi

echo "=== Building consumer-v3 ==="
cd consumer-v3 && mvn package -q -DskipTests && cd ..

JAR=consumer-v3/target/consumer-v3-1.0-SNAPSHOT.jar

echo "=== Deploying consumer JAR to $CONSUMER_IP ==="
scp -i $SSH_KEY $JAR $SSH_USER@$CONSUMER_IP:~/consumer-v3.jar

echo "=== Restarting consumer-v3 ==="
ssh -i $SSH_KEY $SSH_USER@$CONSUMER_IP bash << EOF
    pkill -f 'consumer-v3' || true
    sleep 1

    # Args: redisHost dbHost batchSize flushIntervalMs
    nohup java -jar ~/consumer-v3.jar $REDIS_IP $DB_IP 500 500 > consumer.log 2>&1 &

    sleep 3
    echo "Processes running:"
    pgrep -a -f 'consumer-v3'
EOF

echo "=== Done — consumer-v3 running on $CONSUMER_IP, Redis at $REDIS_IP ==="