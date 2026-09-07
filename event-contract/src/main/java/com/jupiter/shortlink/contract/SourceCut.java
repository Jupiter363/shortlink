package com.jupiter.shortlink.contract;

import java.util.List;

public record SourceCut(List<Range> ranges) {
    public SourceCut {
        ranges = List.copyOf(ranges);
    }

    public record Range(
            String clusterId, String topicId, String topic, int partition, long start, long end) {
        public Range {
            if (clusterId == null
                    || topicId == null
                    || topic == null
                    || partition < 0
                    || start < 0
                    || end < start) throw new IllegalArgumentException("Invalid source range");
        }

        public boolean contains(RawReceipt r) {
            return clusterId.equals(r.clusterId())
                    && topicId.equals(r.topicId())
                    && topic.equals(r.topic())
                    && partition == r.partition()
                    && r.offset() >= start
                    && r.offset() < end;
        }
    }

    public boolean contains(RawReceipt r) {
        return ranges.stream().anyMatch(x -> x.contains(r));
    }

    public boolean covers(SourceCut other) {
        return other.ranges.stream()
                .allMatch(
                        old ->
                                ranges.stream()
                                        .anyMatch(
                                                n ->
                                                        n.clusterId.equals(old.clusterId)
                                                                && n.topicId.equals(old.topicId)
                                                                && n.partition == old.partition
                                                                && n.start <= old.start
                                                                && n.end >= old.end));
    }
}
