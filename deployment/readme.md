# Deployment Configuration

## ALB Configuration
- DNS Name: chatflow-alb-14549874.us-west-2.elb.amazonaws.com
- Inbound rule: HTTP TCP port 80, 0.0.0.0/0
- Listener: HTTP port 80
- Target Group: HTTP port 8080
- Health Check: HTTP port 8081, path /health
- Healthy threshold: 2
- Unhealthy threshold: 3
- Sticky Sessions: enabled, duration 86400s
- Idle Timeout: 3600s

## EC2 Instances (IP address may change due to AWS Academy Lab)
- Server 1: t3.micro, us-west-2b
    - IP: 35.88.148.36
- Server 2: t3.micro, us-west-2b
    - IP: 18.246.191.99
- RabbitMQ: t3.micro Ubuntu, port 5672
    - IP: 54.185.45.50
    - Username: chatflow
    - Password: 123456

## Run Commands
# Server 1
java -jar server-1.0-SNAPSHOT.jar 8080 server-1

# Server 2
java -jar server-1.0-SNAPSHOT.jar 8080 server-2

# Client
java -jar client-part1-1.0-SNAPSHOT.jar 20




