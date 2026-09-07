package com.jupiter.shortlink.admin.config;

import feign.Client;
import feign.Retryer;
import feign.hc5.ApacheHttp5Client;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cloud.openfeign.FeignAutoConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

class AdminFeignTransportConfigurationTest {
    final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(FeignAutoConfiguration.class))
            .withUserConfiguration(AdminFeignTransportConfiguration.class);

    @Test void productionSelectsExplicitHc5WithBoundedPoolAndNoFeignRetries() {
        runner.withPropertyValues("spring.profiles.active=production")
                .withBean(SimpleMeterRegistry.class, SimpleMeterRegistry::new)
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(Client.class);
                    assertThat(context.getBean(Client.class)).isExactlyInstanceOf(ApacheHttp5Client.class);
                    assertThat(context.getBean(Retryer.class)).isSameAs(Retryer.NEVER_RETRY);
                    var pool = context.getBean(PoolingHttpClientConnectionManager.class);
                    assertThat(pool.getMaxTotal()).isEqualTo(32);
                    assertThat(pool.getDefaultMaxPerRoute()).isEqualTo(32);
                    assertThat(pool.getValidateAfterInactivity().toMilliseconds()).isEqualTo(200);
                    var registry = context.getBean(SimpleMeterRegistry.class);
                    assertThat(registry.get("shortlink.admin.feign.pool.max").gauge().value()).isEqualTo(32);
                    assertThat(registry.get("shortlink.admin.feign.pool.pending").gauge().value()).isZero();
                });
    }

    @Test void nonProductionKeepsPreviousDefaultClientEvenWithHc5AdapterPresent() {
        runner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(Client.class);
            assertThat(context.getBean(Client.class)).isExactlyInstanceOf(Client.Default.class);
            assertThat(context).doesNotHaveBean(AdminFeignTransportConfiguration.Settings.class);
            assertThat(context).doesNotHaveBean("adminFeignHttpClient");
        });
    }

    @Test void invalidPoolAndTimeoutSettingsFailStartup() {
        for (String invalid : new String[] {
                "shortlink.admin.transport.max-total=0",
                "shortlink.admin.transport.max-per-route=33",
                "shortlink.admin.transport.lease-timeout-ms=0",
                "shortlink.admin.transport.keep-alive-ms=2000",
                "shortlink.admin.transport.validate-after-ms=1001"}) {
            runner.withPropertyValues("spring.profiles.active=production", invalid)
                    .run(context -> assertThat(context).hasFailed());
        }
    }
}
