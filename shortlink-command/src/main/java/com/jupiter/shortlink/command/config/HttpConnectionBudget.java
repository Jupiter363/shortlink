package com.jupiter.shortlink.command.config;

import org.apache.coyote.http11.AbstractHttp11Protocol;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class HttpConnectionBudget {
    @Bean
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> uploadReadBudget() {
        return factory ->
                factory.addConnectorCustomizers(
                        connector -> {
                            if (connector.getProtocolHandler()
                                    instanceof AbstractHttp11Protocol<?> protocol) {
                                protocol.setDisableUploadTimeout(false);
                                protocol.setConnectionUploadTimeout(5_000);
                                protocol.setMaxSwallowSize(0);
                            }
                        });
    }
}
