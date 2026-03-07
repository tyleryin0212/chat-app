# Deployment Configuration

## ALB Configuration
- Name: chatflow-alb
- Type: Application Load Balancer
- Scheme: Internet-facing
- Inbound rule: HTTP TCP port 80, 0.0.0.0/0
- Listener: HTTP port 80
- Target Group: HTTP port 8080
- Health Check: HTTP port 8081, path /health
- Sticky Sessions: enabled, duration 86400s
- Idle Timeout: 3600s

## EC2 Instances
- Server 1: t3.micro, us-west-2b, server-1
- Server 2: t3.micro, us-west-2b, server-2
- RabbitMQ: t3.micro Ubuntu, Docker rabbitmq:3-management

## Run Commands
# Server 1
java -jar chatflow-server-1.0-SNAPSHOT.jar 8080 server-1

# Server 2
java -jar chatflow-server-1.0-SNAPSHOT.jar 8080 server-2