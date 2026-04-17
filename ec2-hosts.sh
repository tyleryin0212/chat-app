#!/bin/bash
# ── ChatFlow EC2 host config ──────────────────────────────────────────────────
# Run `source ec2-hosts.sh` at the start of every AWS Academy lab session.
# Only this file needs updating when IPs rotate.

export SERVER1_IP=35.164.137.76   # EC2 #1: server-v3 (port 8080)
export SERVER2_IP=52.34.127.218   # EC2 #2: server-v3 (port 8080)
export ALB_DNS=chatflow-alb-14549874.us-west-2.elb.amazonaws.com
export CONSUMER_IP=52.13.112.250  # EC2 #3: consumer-v3 (port 9090)
export DB_IP=34.219.9.49          # EC2 #4: PostgreSQL
export REDIS_IP=54.190.120.64     # EC2 #5: Redis (port 6379)
export SERVER_IP=$SERVER1_IP      # backward compat

export SSH_KEY=/Users/tyleryin/Documents/GitHub/AWS/chat.pem
export SSH_USER=ec2-user
export DB_SSH_USER=ubuntu

echo "Hosts configured:"
echo "  SERVER1  : $SERVER1_IP"
echo "  SERVER2  : $SERVER2_IP"
echo "  ALB      : $ALB_DNS"
echo "  CONSUMER : $CONSUMER_IP"
echo "  REDIS    : $REDIS_IP"
echo "  DB       : $DB_IP"