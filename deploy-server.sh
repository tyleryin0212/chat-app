#!/bin/bash
# Deploy server-v3 to EC2 #1 and EC2 #2 (one instance each, port 8080).
# Both servers point at Redis on the consumer EC2.
# Usage: ./deploy-server.sh
# Prereq: source ec2-hosts.sh

set -e
cd "$(dirname "$0")"

if [ -z "$SERVER1_IP" ] || [ -z "$SERVER2_IP" ] || [ -z "$REDIS_IP" ] || [ -z "$DB_IP" ]; then
    echo "ERROR: run 'source ec2-hosts.sh' first"
    exit 1
fi

echo "=== Building server-v3 ==="
cd server-v3 && mvn package -q -DskipTests && cd ..

JAR=server-v3/target/server-v3-1.0-SNAPSHOT.jar

deploy_server() {
    local IP=$1
    local ID=$2
    echo "=== Deploying to $IP ($ID) ==="
    scp -i $SSH_KEY $JAR $SSH_USER@$IP:~/server-v3.jar
    ssh -i $SSH_KEY $SSH_USER@$IP bash << EOF
        pkill -f 'server-v3' || true
        pkill redis6-server   || true
        sleep 1
        nohup java -jar ~/server-v3.jar 8080 $ID $REDIS_IP $DB_IP > server-8080.log 2>&1 &
        sleep 3
        echo "Processes running on $IP:"
        pgrep -a -f server-v3
EOF
}

deploy_server $SERVER1_IP server-1
deploy_server $SERVER2_IP server-2

echo "=== Done — server-v3 running on $SERVER1_IP and $SERVER2_IP, Redis at $REDIS_IP ==="
