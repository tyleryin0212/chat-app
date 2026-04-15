#!/bin/bash
# ── ChatFlow EC2 host config ──────────────────────────────────────────────────
# Run `source ec2-hosts.sh` at the start of every AWS Academy lab session.
# Only this file needs updating when IPs rotate.

export SERVER1_IP=35.93.131.77    # EC2 #1: server-v3 (port 8080)
export SERVER2_IP=34.213.43.130   # EC2 #2: server-v3 (port 8080)
export ALB_DNS=chatflow-alb-14549874.us-west-2.elb.amazonaws.com
export CONSUMER_IP=54.201.6.32    # EC2 #3: Redis + consumer-v3 (ports 6379, 9090)
export DB_IP=35.95.32.174         # EC2 #4: PostgreSQL
export REDIS_IP=$CONSUMER_IP      # Redis lives on consumer EC2
export SERVER_IP=$SERVER1_IP      # backward compat

export SSH_KEY=/Users/tyleryin/Documents/GitHub/AWS/chat.pem
export SSH_USER=ec2-user
export DB_SSH_USER=ubuntu

echo "Hosts configured:"
echo "  SERVER1  : $SERVER1_IP"
echo "  SERVER2  : $SERVER2_IP"
echo "  ALB      : $ALB_DNS"
echo "  CONSUMER : $CONSUMER_IP  (also Redis)"
echo "  DB       : $DB_IP"