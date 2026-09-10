package com.jupiter.shortlink.command.config;

import com.jupiter.shortlink.id.SegmentIdGenerator;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import org.springframework.context.annotation.Configuration;

@Configuration
public class IdMetrics {
    public IdMetrics(SegmentIdGenerator generator, MeterRegistry registry) {
        Gauge.builder(
                        "shortlink.id.current.remaining",
                        generator,
                        g -> g.snapshot().currentRemaining())
                .register(registry);
        Gauge.builder("shortlink.id.next.ready", generator, g -> g.snapshot().nextReady() ? 1 : 0)
                .register(registry);
        Gauge.builder("shortlink.id.requested.step", generator, g -> g.snapshot().requestedStep())
                .register(registry);
        Gauge.builder("shortlink.id.refill.failures", generator, g -> g.snapshot().refillFailures())
                .register(registry);
        Gauge.builder(
                        "shortlink.id.executor.rejections",
                        generator,
                        g -> g.snapshot().executorRejections())
                .register(registry);
        Gauge.builder("shortlink.id.discarded", generator, g -> g.snapshot().discardedIds())
                .register(registry);
        Gauge.builder("shortlink.id.issued", generator, g -> g.snapshot().issuedIds())
                .register(registry);
    }
}
