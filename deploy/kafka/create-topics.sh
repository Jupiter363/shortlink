#!/usr/bin/env sh
set -eu
: "${BOOTSTRAP_SERVERS:?Set the explicit target Kafka bootstrap servers}"
: "${REPLICATION_FACTOR:?Set 3 for production or explicitly 1 for isolated development}"
: "${MIN_INSYNC_REPLICAS:?Set 2 for production or explicitly 1 for isolated development}"
KAFKA_BIN=${KAFKA_BIN:-/opt/kafka/bin}
PARTITIONS=${PARTITIONS:-24}
create_topic() {
  "$KAFKA_BIN/kafka-topics.sh" --bootstrap-server "$BOOTSTRAP_SERVERS" --create --if-not-exists \
    --topic "$1" --partitions "$PARTITIONS" --replication-factor "$REPLICATION_FACTOR" \
    --config "min.insync.replicas=$MIN_INSYNC_REPLICAS" --config cleanup.policy=delete \
    --config "message.timestamp.type=$2" --config "retention.ms=$3"
}
for topic in shortlink.click.raw.v1 shortlink.gateway.request.v1 shortlink.route.change.v1 shortlink.route.membership.v1 shortlink.risk.policy.change.v1 shortlink.batch.ready.v1 shortlink.metadata.fetch.v1; do
  create_topic "$topic" LogAppendTime 604800000
done
for topic in shortlink.click.enriched.v1 shortlink.request.enriched.v1 shortlink.stats.5m.v1 shortlink.risk.signal.v1; do
  create_topic "$topic" CreateTime 604800000
done
for topic in shortlink.click.late.v1 shortlink.click.dlq.v1 shortlink.metadata.dlq.v1; do
  create_topic "$topic" LogAppendTime 2592000000
done
# Existing topic configurations are deliberately not altered silently; inspect them against topics.yaml.
