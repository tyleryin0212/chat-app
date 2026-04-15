#!/bin/bash
# Deploy Redis config + server-v3 to EC2 #1.
# Redis co-located with server on the same EC2 (localhost).
# Usage: ./deploy-server.sh
# Prereq: source ec2-hosts.sh

set -e

if [ -z "$SERVER_IP" ] || [ -z "$DB_IP" ]; then
    echo "ERROR: run 'source ec2-hosts.sh' first"
    exit 1
fi

echo "=== Building server-v3 ==="
cd server-v3 && mvn package -q -DskipTests && cd ..

JAR=server-v3/target/server-v3-1.0-SNAPSHOT.jar

echo "=== Deploying to $SERVER_IP ==="
scp -i $SSH_KEY redis.conf $SSH_USER@$SERVER_IP:~/redis.conf
scp -i $SSH_KEY $JAR       $SSH_USER@$SERVER_IP:~/server-v3.jar

echo "=== Restarting Redis and server instances ==="
ssh -i $SSH_KEY $SSH_USER@$SERVER_IP bash << EOF
    # Redis
    pkill redis6-server || true
    sleep 1
    nohup redis6-server ~/redis.conf > redis.log 2>&1 &
    sleep 2
    echo "Redis running: \$(redis6-cli ping)"

    # Server instances
    pkill -f 'server-v3' || true
    sleep 1

    # Args: port serverId redisHost dbHost
    # Redis is local — pass localhost
    nohup java -jar server-v3.jar 8080 server-1 localhost $DB_IP > server-8080.log 2>&1 &
    nohup java -jar server-v3.jar 8082 server-2 localhost $DB_IP > server-8082.log 2>&1 &

    sleep 2
    echo "Processes running:"
    pgrep -a -f server-v3
EOF

echo "=== Done — Redis + server-v3 running on $SERVER_IP ==="