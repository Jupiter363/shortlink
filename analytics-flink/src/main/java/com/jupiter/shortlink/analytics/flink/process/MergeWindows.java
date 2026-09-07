package com.jupiter.shortlink.analytics.flink.process;

import com.jupiter.shortlink.contract.*;

import org.apache.flink.api.common.state.*;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.functions.KeyedProcessFunction;
import org.apache.flink.util.Collector;

public final class MergeWindows extends KeyedProcessFunction<String, String, String> {
    private transient MapState<Integer, String> buckets;
    private transient ValueState<Long> revision;

    @Override
    public void open(Configuration c) {
        buckets =
                getRuntimeContext()
                        .getMapState(
                                new MapStateDescriptor<>("buckets", Integer.class, String.class));
        revision = getRuntimeContext().getState(new ValueStateDescriptor<>("revision", Long.class));
    }

    public void processElement(String value, Context ctx, Collector<String> out) throws Exception {
        WindowResult current = EventJson.read(value, WindowResult.class);
        String prev = buckets.get(current.bucket());
        if (prev != null) {
            WindowResult previous = EventJson.read(prev, WindowResult.class);
            if (current.revision() < previous.revision()) return;
            if (current.revision() == previous.revision()) {
                if (!value.equals(prev))
                    throw new IllegalStateException("Same partial revision changed payload");
                return;
            }
        }
        buckets.put(current.bucket(), value);
        long pv = 0;
        HllSketch visitors = new HllSketch(), ips = new HllSketch();
        for (String v : buckets.values()) {
            WindowResult w = EventJson.read(v, WindowResult.class);
            pv = Math.addExact(pv, w.pv());
            visitors.merge(new HllSketch(w.visitorSketch()));
            ips.merge(new HllSketch(w.ipSketch()));
        }
        long rev = Math.addExact(revision.value() == null ? 0 : revision.value(), 1);
        revision.update(rev);
        out.collect(
                EventJson.write(
                        new WindowResult(
                                current.tenantId(),
                                current.linkId(),
                                current.windowStart(),
                                current.windowEnd(),
                                -1,
                                rev,
                                pv,
                                visitors.estimate(),
                                ips.estimate(),
                                visitors.encode(),
                                ips.encode(),
                                current.buildId(),
                                current.recoveryEpoch(),
                                current.metricVersion(),
                                current.detailDatasetVersion())));
        ctx.timerService()
                .registerProcessingTimeTimer(
                        Math.max(
                                ctx.timerService().currentProcessingTime() + 60000,
                                current.windowEnd() + 600000));
    }

    public void onTimer(long timestamp, OnTimerContext ctx, Collector<String> out)
            throws Exception {
        buckets.clear();
        revision.clear();
    }
}
