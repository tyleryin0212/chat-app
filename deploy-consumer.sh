#!/bin/bash
# Deploy Redis + consumer-v3 to EC2 #2.
# Redis is co-located with consumer on this EC2 (localhost).
# Usage: ./deploy-consumer.sh
# Prereq: source ec2-hosts.sh

set -e
cd "$(dirname "$0")"

if [ -z "$CONSUMER_IP" ] || [ -z "$DB_IP" ]; then
    echo "ERROR: run 'source ec2-hosts.sh' first"
    exit 1
fi

echo "=== Building consumer-v3 ==="
cd consumer-v3 && mvn package -q -DskipTests && cd ..

JAR=consumer-v3/target/consumer-v3-1.0-SNAPSHOT.jar

echo "=== Deploying Redis config + consumer JAR to $CONSUMER_IP ==="
scp -i $SSH_KEY redis.conf $SSH_USER@$CONSUMER_IP:~/redis.conf
scp -i $SSH_KEY $JAR       $SSH_USER@$CONSUMER_IP:~/consumer-v3.jar

echo "=== Restarting Redis and consumer-v3 ==="
ssh -i $SSH_KEY $SSH_USER@$CONSUMER_IP bash << EOF
    pkill -f 'consumer-v3' || true
    pkill redis6-server     || true
    sleep 2

    # Redis — local to this EC2
    nohup redis6-server ~/redis.conf > redis.log 2>&1 &
    # Wait for Redis to finish loading AOF before flushing
    echo -n "Waiting for Redis..."
    until redis6-cli ping 2>/dev/null | grep -q PONG; do sleep 1; echo -n "."; done
    echo " ready"
    redis6-cli FLUSHALL   # clear any stale AOF data from previous runs
    echo "Redis flushed"

    # Args: redisHost dbHost batchSize flushIntervalMs
    # Redis is local — pass localhost
    nohup java -jar ~/consumer-v3.jar localhost $DB_IP 500 500 > consumer.log 2>&1 &

    sleep 3
    echo "Processes running:"
    pgrep -a -f 'consumer-v3\|redis'
EOF

echo "=== Done — Redis + consumer-v3 running on $CONSUMER_IP ==="