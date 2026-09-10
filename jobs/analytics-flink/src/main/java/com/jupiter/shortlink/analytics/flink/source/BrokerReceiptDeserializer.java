package com.jupiter.shortlink.analytics.flink.source;

import com.jupiter.shortlink.contract.*;

import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.connector.kafka.source.reader.deserializer.KafkaRecordDeserializationSchema;
import org.apache.flink.util.Collector;
import org.apache.kafka.clients.consumer.ConsumerRecord;

import java.nio.charset.StandardCharsets;
import java.util.Map;

public final class BrokerReceiptDeserializer implements KafkaRecordDeserializationSchema<String> {
    private final String clusterId;
    private final Map<String, String> topicIds;

    public BrokerReceiptDeserializer(String clusterId, Map<String, String> topicIds) {
        this.clusterId = clusterId;
        this.topicIds = Map.copyOf(topicIds);
    }

    public void deserialize(ConsumerRecord<byte[], byte[]> r, Collector<String> out) {
        String topicId = topicIds.get(r.topic());
        if (topicId == null) throw new IllegalStateException("Unknown source topic incarnation");
        out.collect(
                EventJson.write(
                        new RawReceipt(
                                clusterId,
                                topicId,
                                r.topic(),
                                r.partition(),
                                r.offset(),
                                r.timestamp(),
                                r.timestampType().toString(),
                                r.value() == null
                                        ? ""
                                        : new String(r.value(), StandardCharsets.UTF_8))));
    }

    public TypeInformation<String> getProducedType() {
        return TypeInformation.of(String.class);
    }
}
