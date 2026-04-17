#!/bin/bash
# Deploy Redis to its dedicated EC2 instance.
# Usage: ./deploy-redis.sh
# Prereq: source ec2-hosts.sh

set -e
cd "$(dirname "$0")"

if [ -z "$REDIS_IP" ]; then
    echo "ERROR: run 'source ec2-hosts.sh' first"
    exit 1
fi

echo "=== Deploying Redis to $REDIS_IP ==="
scp -i $SSH_KEY redis.conf $SSH_USER@$REDIS_IP:~/redis.conf

ssh -i $SSH_KEY $SSH_USER@$REDIS_IP bash << EOF
    # Install redis6 if not already present (Amazon Linux 2023)
    if ! command -v redis6-server &>/dev/null; then
        echo "Installing redis6..."
        sudo dnf install -y redis6
    fi

    pkill redis6-server || true
    sleep 1
    nohup redis6-server ~/redis.conf > redis.log 2>&1 &
    echo -n "Waiting for Redis..."
    until redis6-cli ping 2>/dev/null | grep -q PONG; do sleep 1; echo -n "."; done
    echo " ready"
    redis6-cli FLUSHALL
    echo "Redis flushed"
    pgrep -a redis
EOF

echo "=== Done — Redis running on $REDIS_IP ==="