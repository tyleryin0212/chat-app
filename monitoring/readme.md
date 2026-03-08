# Monitoring

## RabbitMQ Management UI
- URL: http://<rabbitmq-ip>:15672
- Metrics: queue depth, message rates, connections

## Alerts
- Queue depth alarm set at 1000 messages
- Configured via RabbitMQ management UI

## Key Metrics Tracked
- Messages per second (publish/consume)
- Queue depth over time
- Consumer lag
- Connection count per server
- Error rates