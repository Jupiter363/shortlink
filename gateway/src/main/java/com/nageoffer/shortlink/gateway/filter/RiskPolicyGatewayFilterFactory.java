package com.nageoffer.shortlink.gateway.filter;

import com.alibaba.fastjson2.JSON;
import com.nageoffer.shortlink.gateway.config.Config;
import com.nageoffer.shortlink.gateway.dto.GatewayErrorResult;
import com.nageoffer.shortlink.gateway.risk.RiskClientIpResolver;
import com.nageoffer.shortlink.gateway.risk.RiskDomainNormalizer;
import com.nageoffer.shortlink.gateway.risk.RiskHashService;
import com.nageoffer.shortlink.gateway.risk.RiskPolicyPayload;
import com.nageoffer.shortlink.gateway.risk.RiskPolicyProperties;
import com.nageoffer.shortlink.gateway.risk.RiskPolicyRedisKeyBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
public class RiskPolicyGatewayFilterFactory extends AbstractGatewayFilterFactory<Config> {

    private static final String MESSAGE = "Risk policy rejected request";
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");

    private final StringRedisTemplate stringRedisTemplate;
    private final RiskPolicyProperties riskPolicyProperties;
    private final RiskDomainNormalizer domainNormalizer;
    private final RiskClientIpResolver ipResolver;
    private final RiskHashService hashService;
    private final RiskPolicyRedisKeyBuilder keyBuilder;
    private final Clock clock;

    @Autowired
    public RiskPolicyGatewayFilterFactory(
            StringRedisTemplate stringRedisTemplate,
            RiskPolicyProperties riskPolicyProperties
    ) {
        this(stringRedisTemplate, riskPolicyProperties, Clock.system(ZoneId.of(riskPolicyProperties.getClockZone())));
    }

    RiskPolicyGatewayFilterFactory(
            StringRedisTemplate stringRedisTemplate,
            RiskPolicyProperties riskPolicyProperties,
            Clock clock
    ) {
        super(Config.class);
        this.stringRedisTemplate = stringRedisTemplate;
        this.riskPolicyProperties = riskPolicyProperties;
        this.domainNormalizer = new RiskDomainNormalizer();
        this.ipResolver = new RiskClientIpResolver(riskPolicyProperties.isTrustedProxyEnabled());
        this.hashService = new RiskHashService(riskPolicyProperties.getHashSalt());
        this.keyBuilder = new RiskPolicyRedisKeyBuilder();
        this.clock = clock;
    }

    @Override
    public GatewayFilter apply(Config config) {
        return (exchange, chain) -> {
            ServerHttpRequest request = exchange.getRequest();
            String domain = domainNormalizer.normalize(request.getHeaders().getFirst(HttpHeaders.HOST));
            String shortUri = shortUri(request);
            ValueOperations<String, String> valueOperations = stringRedisTemplate.opsForValue();

            if (hasPolicy(valueOperations, keyBuilder.disableShortLinkKey(domain, shortUri))) {
                return reject(exchange.getResponse(), HttpStatus.NOT_FOUND);
            }

            String ipHash = hashService.sha256(ipResolver.resolve(request));
            if (hasPolicy(valueOperations, keyBuilder.blockIpKey(ipHash))) {
                return reject(exchange.getResponse(), HttpStatus.FORBIDDEN);
            }

            String timeWindowPayload = valueOperations.get(keyBuilder.timeWindowShortLinkKey(domain, shortUri));
            if (StringUtils.hasText(timeWindowPayload) && isOutsideAllowedWindow(timeWindowPayload)) {
                return reject(exchange.getResponse(), HttpStatus.FORBIDDEN);
            }

            String rateLimitPayload = valueOperations.get(keyBuilder.rateLimitShortLinkKey(domain, shortUri));
            if (StringUtils.hasText(rateLimitPayload) && exceedsRateLimit(domain, shortUri, ipHash, rateLimitPayload)) {
                return reject(exchange.getResponse(), HttpStatus.TOO_MANY_REQUESTS);
            }

            return chain.filter(exchange);
        };
    }

    private String shortUri(ServerHttpRequest request) {
        String path = request.getPath().pathWithinApplication().value();
        if (!StringUtils.hasText(path)) {
            return "";
        }
        return path.startsWith("/") ? path.substring(1) : path;
    }

    private boolean hasPolicy(ValueOperations<String, String> valueOperations, String key) {
        return StringUtils.hasText(valueOperations.get(key));
    }

    private boolean isOutsideAllowedWindow(String payloadJson) {
        RiskPolicyPayload payload = JSON.parseObject(payloadJson, RiskPolicyPayload.class);
        List<String> allowedWindows = payload.getAllowedWindows();
        if (allowedWindows == null || allowedWindows.isEmpty()) {
            return false;
        }
        ZoneId zoneId = ZoneId.of(StringUtils.hasText(payload.getTimezone())
                ? payload.getTimezone()
                : riskPolicyProperties.getClockZone());
        LocalTime now = LocalTime.now(clock.withZone(zoneId));
        return allowedWindows.stream().noneMatch(window -> contains(window, now));
    }

    private boolean contains(String window, LocalTime now) {
        if (!StringUtils.hasText(window) || !window.contains("-")) {
            return false;
        }
        String[] parts = window.split("-", 2);
        LocalTime start = LocalTime.parse(parts[0].trim(), TIME_FORMATTER);
        LocalTime end = LocalTime.parse(parts[1].trim(), TIME_FORMATTER);
        if (start.equals(end)) {
            return true;
        }
        if (start.isBefore(end)) {
            return !now.isBefore(start) && !now.isAfter(end);
        }
        return !now.isBefore(start) || !now.isAfter(end);
    }

    private boolean exceedsRateLimit(String domain, String shortUri, String ipHash, String payloadJson) {
        RiskPolicyPayload payload = JSON.parseObject(payloadJson, RiskPolicyPayload.class);
        if (payload.getLimit() == null || payload.getLimit() < 1) {
            return false;
        }
        String counterKey = keyBuilder.rateCounterKey(domain, shortUri, ipHash);
        Long count = stringRedisTemplate.opsForValue().increment(counterKey);
        long currentCount = count == null ? 0L : count;
        if (currentCount == 1L && payload.getWindowSeconds() != null && payload.getWindowSeconds() > 0) {
            stringRedisTemplate.expire(counterKey, payload.getWindowSeconds(), TimeUnit.SECONDS);
        }
        return currentCount > payload.getLimit();
    }

    private Mono<Void> reject(ServerHttpResponse response, HttpStatus status) {
        response.setStatusCode(status);
        return response.writeWith(Mono.fromSupplier(() -> {
            DataBufferFactory bufferFactory = response.bufferFactory();
            GatewayErrorResult resultMessage = GatewayErrorResult.builder()
                    .status(status.value())
                    .message(MESSAGE)
                    .build();
            return bufferFactory.wrap(JSON.toJSONString(resultMessage).getBytes(StandardCharsets.UTF_8));
        }));
    }
}
