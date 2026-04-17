#!/bin/bash
# ── ChatFlow EC2 host config ──────────────────────────────────────────────────
# Run `source ec2-hosts.sh` at the start of every AWS Academy lab session.
# Only this file needs updating when IPs rotate.

export SERVER1_IP=16.148.83.127   # EC2 #1: server-v3 (port 8080)
export SERVER2_IP=18.237.120.235   # EC2 #2: server-v3 (port 8080)
export ALB_DNS=chatflow-alb-14549874.us-west-2.elb.amazonaws.com
export CONSUMER_IP=35.87.22.136   # EC2 #3: consumer-v3 (port 9090)
export DB_IP=34.214.78.148        # EC2 #4: PostgreSQL
export REDIS_IP=35.89.219.23      # EC2 #5: Redis (port 6379)
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