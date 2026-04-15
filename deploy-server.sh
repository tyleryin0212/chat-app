#!/bin/bash
# Deploy server-v3 to EC2 #1.
# Redis is on the consumer EC2 ($REDIS_IP) — server connects there.
# Usage: ./deploy-server.sh
# Prereq: source ec2-hosts.sh

set -e

if [ -z "$SERVER_IP" ] || [ -z "$REDIS_IP" ] || [ -z "$DB_IP" ]; then
    echo "ERROR: run 'source ec2-hosts.sh' first"
    exit 1
fi

echo "=== Building server-v3 ==="
cd server-v3 && mvn package -q -DskipTests && cd ..

JAR=server-v3/target/server-v3-1.0-SNAPSHOT.jar

echo "=== Deploying JAR to $SERVER_IP ==="
scp -i $SSH_KEY $JAR $SSH_USER@$SERVER_IP:~/server-v3.jar

echo "=== Restarting server instances ==="
ssh -i $SSH_KEY $SSH_USER@$SERVER_IP bash << EOF
    pkill -f 'server-v3' || true
    pkill redis6-server  || true   # kill any leftover Redis from old layout
    sleep 1

    # Args: port serverId redisHost dbHost
    # Redis is now on the consumer EC2
    nohup java -jar ~/server-v3.jar 8080 server-1 $REDIS_IP $DB_IP > server-8080.log 2>&1 &
    nohup java -jar ~/server-v3.jar 8082 server-2 $REDIS_IP $DB_IP > server-8082.log 2>&1 &

    sleep 3
    echo "Processes running:"
    pgrep -a -f server-v3
EOF

echo "=== Done — server-v3 running on $SERVER_IP, Redis at $REDIS_IP ==="