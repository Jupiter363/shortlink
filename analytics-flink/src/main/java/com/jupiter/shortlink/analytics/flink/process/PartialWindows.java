package com.jupiter.shortlink.analytics.flink.process;

import com.jupiter.shortlink.contract.*;

import org.apache.flink.api.common.state.*;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.functions.KeyedProcessFunction;
import org.apache.flink.util.Collector;

public final class PartialWindows extends KeyedProcessFunction<String, String, String> {
    private final String build, epoch;
    private transient ValueState<Long> count, revision, timer;
    private transient ValueState<String> visitor, ip, sample;
    private transient ValueState<Boolean> dirty;

    public PartialWindows(String build, String epoch) {
        this.build = build;
        this.epoch = epoch;
    }

    @Override
    public void open(Configuration c) {
        count = getRuntimeContext().getState(new ValueStateDescriptor<>("pv", Long.class));
        revision = getRuntimeContext().getState(new ValueStateDescriptor<>("revision", Long.class));
        timer = getRuntimeContext().getState(new ValueStateDescriptor<>("timer", Long.class));
        visitor = getRuntimeContext().getState(new ValueStateDescriptor<>("visitor", String.class));
        ip = getRuntimeContext().getState(new ValueStateDescriptor<>("ip", String.class));
        sample = getRuntimeContext().getState(new ValueStateDescriptor<>("sample", String.class));
        dirty = getRuntimeContext().getState(new ValueStateDescriptor<>("dirty", Boolean.class));
    }

    public void processElement(String value, Context ctx, Collector<String> out) throws Exception {
        EnrichedRecord r = EventJson.read(value, EnrichedRecord.class);
        count.update(Math.addExact(count.value() == null ? 0 : count.value(), 1));
        HllSketch v = visitor.value() == null ? new HllSketch() : new HllSketch(visitor.value());
        HllSketch i = ip.value() == null ? new HllSketch() : new HllSketch(ip.value());
        v.add(r.visitorHash());
        i.add(r.ipHash());
        visitor.update(v.encode());
        ip.update(i.encode());
        sample.update(value);
        dirty.update(true);
        if (timer.value() == null) {
            long t = ctx.timerService().currentProcessingTime() + 1000;
            timer.update(t);
            ctx.timerService().registerProcessingTimeTimer(t);
        }
    }

    public void onTimer(long timestamp, OnTimerContext ctx, Collector<String> out)
            throws Exception {
        if (sample.value() == null) return;
        EnrichedRecord r = EventJson.read(sample.value(), EnrichedRecord.class);
        long start = Math.floorDiv(r.occurredAt(), 300000) * 300000;
        if (Boolean.TRUE.equals(dirty.value())) {
            long rev = Math.addExact(revision.value() == null ? 0 : revision.value(), 1);
            revision.update(rev);
            out.collect(
                    EventJson.write(
                            new WindowResult(
                                    r.tenantId(),
                                    r.linkId(),
                                    start,
                                    start + 300000,
                                    EventKeys.bucket(r.eventId(), 16),
                                    rev,
                                    count.value(),
                                    new HllSketch(visitor.value()).estimate(),
                                    new HllSketch(ip.value()).estimate(),
                                    visitor.value(),
                                    ip.value(),
                                    build,
                                    epoch,
                                    "click-v1",
                                    r.detailDatasetVersion())));
            dirty.update(false);
        }
        if (timestamp >= start + 300000 + 480000 + 60000) {
            count.clear();
            revision.clear();
            visitor.clear();
            ip.clear();
            sample.clear();
            dirty.clear();
            timer.clear();
        } else {
            long next = timestamp + 1000;
            timer.update(next);
            ctx.timerService().registerProcessingTimeTimer(next);
        }
    }
}
