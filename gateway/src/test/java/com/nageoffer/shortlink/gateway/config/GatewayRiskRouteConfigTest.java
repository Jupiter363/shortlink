package com.nageoffer.shortlink.gateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionLocator;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.cloud.nacos.discovery.enabled=false",
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration"
})
@ActiveProfiles("risk-local")
class GatewayRiskRouteConfigTest {

    @Autowired
    private RouteDefinitionLocator routeDefinitionLocator;

    @MockBean
    private StringRedisTemplate stringRedisTemplate;

    @Test
    void redirectRouteHasRiskPolicyFilterAndApiRoutesHaveHigherPriority() {
        List<RouteDefinition> routes = Flux.from(routeDefinitionLocator.getRouteDefinitions()).collectList().block();

        RouteDefinition redirect = find(routes, "short-link-redirect");
        RouteDefinition adminApi = find(routes, "short-link-admin-api");
        RouteDefinition projectApi = find(routes, "short-link-project-api");

        assertThat(redirect.getOrder()).isEqualTo(100);
        assertThat(redirect.getFilters()).anyMatch(filter -> filter.getName().equals("RiskPolicy"));
        assertThat(adminApi.getOrder()).isLessThan(redirect.getOrder());
        assertThat(projectApi.getOrder()).isLessThan(redirect.getOrder());
    }

    private RouteDefinition find(List<RouteDefinition> routes, String id) {
        return routes.stream()
                .filter(route -> route.getId().equals(id))
                .findFirst()
                .orElseThrow();
    }
}
