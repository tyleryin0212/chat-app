#!/bin/bash
# ── ChatFlow EC2 host config ──────────────────────────────────────────────────
# Run `source ec2-hosts.sh` at the start of every AWS Academy lab session.
# Only this file needs updating when IPs rotate.

export SERVER_IP=52.10.75.155     # EC2 #1: server-v3 + Redis (ports 8080, 8082, 6379)
export CONSUMER_IP=35.92.105.145  # EC2 #2: consumer-v3 only (Redis moved to server EC2)
export DB_IP=34.218.237.235       # EC2 #3: PostgreSQL (user: ubuntu)
export CLIENT_IP=44.254.75.132    # EC2 #4: client-v3 load test runner

export SSH_KEY=/Users/tyleryin/Documents/GitHub/AWS/chat.pem
export SSH_USER=ec2-user
export DB_SSH_USER=ubuntu

echo "Hosts configured:"
echo "  SERVER   : $SERVER_IP"
echo "  CONSUMER : $CONSUMER_IP"
echo "  DB       : $DB_IP"
echo "  CLIENT   : $CLIENT_IP"