package com.jupiter.shortlink.admin.config;

import org.apache.coyote.http11.AbstractHttp11Protocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.embedded.tomcat.TomcatWebServer;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.servlet.context.ServletWebServerInitializedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class HttpConnectionBudget {
    private static final Logger LOG = LoggerFactory.getLogger(HttpConnectionBudget.class);

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

    @Bean
    ApplicationListener<ServletWebServerInitializedEvent> reportHttpConnectionBudgets() {
        return event -> {
            if (!(event.getWebServer() instanceof TomcatWebServer server)) return;
            String role = "management".equals(event.getApplicationContext().getServerNamespace())
                    ? "management" : "application";
            for (var connector : server.getTomcat().getService().findConnectors()) {
                if (connector.getProtocolHandler() instanceof AbstractHttp11Protocol<?> protocol) {
                    // Read the running connector after all customizers and property binding.
                    // Fixed labels and numeric budgets only: no address, URL, credentials or body.
                    LOG.info("admin_http_connection_budget role={} port={} connection_timeout_ms={} "
                                    + "keep_alive_timeout_ms={} max_keep_alive_requests={} "
                                    + "upload_timeout_enabled={} upload_timeout_ms={} max_connections={} "
                                    + "max_threads={} min_spare_threads={} accept_count={} "
                                    + "max_request_header_bytes={} max_swallow_bytes={}",
                            role, connector.getLocalPort(), protocol.getConnectionTimeout(),
                            protocol.getKeepAliveTimeout(), protocol.getMaxKeepAliveRequests(),
                            !protocol.getDisableUploadTimeout(), protocol.getConnectionUploadTimeout(),
                            protocol.getMaxConnections(), protocol.getMaxThreads(),
                            protocol.getMinSpareThreads(), protocol.getAcceptCount(),
                            protocol.getMaxHttpRequestHeaderSize(), protocol.getMaxSwallowSize());
                }
            }
        };
    }
}
