package com.jupiter.shortlink.analytics.flink.process;

import com.jupiter.shortlink.contract.*;

import org.apache.flink.api.common.state.*;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.functions.KeyedProcessFunction;
import org.apache.flink.util.Collector;

import java.time.Duration;

public final class BoundedDeduplication extends KeyedProcessFunction<String, String, String> {
    private transient ValueState<String> payload;

    @Override
    public void open(Configuration c) throws Exception {
        var d = new ValueStateDescriptor<String>("event-payload", String.class);
        d.enableTimeToLive(
                StateTtlConfig.newBuilder(Duration.ofMinutes(10))
                        .setUpdateType(StateTtlConfig.UpdateType.OnCreateAndWrite)
                        .setStateVisibility(StateTtlConfig.StateVisibility.NeverReturnExpired)
                        .build());
        payload = getRuntimeContext().getState(d);
    }

    public void processElement(String value, Context ctx, Collector<String> out) throws Exception {
        EnrichedRecord r = EventJson.read(value, EnrichedRecord.class);
        String seen = payload.value();
        if (seen == null) {
            payload.update(r.payloadHash());
            out.collect(value);
        } else if (!seen.equals(r.payloadHash())) ctx.output(InterpretEvents.INVALID, value);
    }
}
