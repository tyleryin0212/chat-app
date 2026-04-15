#!/bin/bash
# ── ChatFlow EC2 host config ──────────────────────────────────────────────────
# Run `source ec2-hosts.sh` at the start of every AWS Academy lab session.
# Only this file needs updating when IPs rotate.

export SERVER_IP=50.112.4.130     # EC2 #1: server-v3 only (ports 8080, 8082)
export CONSUMER_IP=34.222.136.76  # EC2 #2: Redis + consumer-v3 (ports 6379, 9090)
export DB_IP=34.220.236.149       # EC2 #3: PostgreSQL
export REDIS_IP=$CONSUMER_IP      # Redis lives on consumer EC2

export SSH_KEY=/Users/tyleryin/Documents/GitHub/AWS/chat.pem
export SSH_USER=ec2-user
export DB_SSH_USER=ubuntu

echo "Hosts configured:"
echo "  SERVER   : $SERVER_IP"
echo "  CONSUMER : $CONSUMER_IP  (also Redis)"
echo "  DB       : $DB_IP"