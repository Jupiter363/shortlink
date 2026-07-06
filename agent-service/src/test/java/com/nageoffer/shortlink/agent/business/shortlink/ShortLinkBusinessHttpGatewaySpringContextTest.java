package com.nageoffer.shortlink.agent.business.shortlink;

import com.nageoffer.shortlink.agent.infrastructure.config.AgentProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.web.client.RestTemplateAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class ShortLinkBusinessHttpGatewaySpringContextTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RestTemplateAutoConfiguration.class))
            .withBean(AgentProperties.class)
            .withUserConfiguration(ShortLinkBusinessHttpGateway.class);

    @Test
    void createsGatewayBeanWithConstructorInjection() {
        contextRunner.run(context -> assertThat(context).hasSingleBean(ShortLinkBusinessGateway.class));
    }
}
