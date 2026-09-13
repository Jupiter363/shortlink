package com.jupiter.shortlink.analytics.flink.process;

import com.jupiter.shortlink.contract.*;

import org.apache.flink.streaming.api.functions.ProcessFunction;
import org.apache.flink.util.Collector;
import org.apache.flink.util.OutputTag;

public final class InterpretEvents extends ProcessFunction<String, String> {
    public static final OutputTag<String> ONLINE = new OutputTag<String>("online") {};
    public static final OutputTag<String> LATE = new OutputTag<String>("late") {};
    public static final OutputTag<String> INVALID = new OutputTag<String>("invalid") {};
    private final String hashKey;
    private transient EventEnricher enricher;

    public InterpretEvents(String hashKey) {
        this.hashKey = hashKey;
    }

    @Override
    public void open(org.apache.flink.configuration.Configuration c) {
        enricher = EventEnricher.fromEnvironment(hashKey, 5000);
    }

    @Override
    public void close() {
        if (enricher != null) enricher.close();
    }

    public void processElement(String input, Context ctx, Collector<String> out) {
        EnrichedRecord r = enricher.enrich(EventJson.read(input, RawReceipt.class));
        String value = EventJson.write(r);
        out.collect(value);
        if (!r.valid()) ctx.output(INVALID, value);
        else if (r.click()) {
            if (TimeValidation.online(r.occurredAt(), ctx.timerService().currentProcessingTime()))
                ctx.output(ONLINE, value);
            else ctx.output(LATE, value);
        }
    }
}
