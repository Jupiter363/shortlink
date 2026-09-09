package com.jupiter.shortlink.redirect;

import static org.junit.jupiter.api.Assertions.*;

import com.jupiter.shortlink.contract.*;
import com.jupiter.shortlink.risk.*;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

/** The actual enrichment output is the policy input; this must catch drift across both modules. */
class RiskIpIdentityContractTest {
    private static final String KEY = "component-test-stable-hash-secret-32-bytes";

    @Test
    void analyticsIpHashBlocksTheSameClientForItsTenantOnly() {
        for (String ip : List.of("203.0.113.9", "2001:db8::a", "::ffff:192.0.2.9")) {
            long now = 1_800_000_000_000L;
            ClickEventV1 event =
                    new ClickEventV1(
                            EventIdentity.bind(now, "click-1"),
                            1,
                            now,
                            "redirect-1",
                            "1001",
                            7,
                            "g1",
                            1,
                            "nurl.ink",
                            "abc123",
                            1,
                            "visitor",
                            ip,
                            "Chrome/123",
                            "",
                            "req-1",
                            "trace-1",
                            1);
            EnrichedRecord enriched =
                    new EventEnricher(KEY, 5000)
                            .enrich(
                                    new RawReceipt(
                                            "cluster-1",
                                            "topic-1",
                                            Topics.CLICK_RAW,
                                            0,
                                            1,
                                            now,
                                            "LogAppendTime",
                                            EventJson.write(event)));
            RiskHash hash = new RiskHash(KEY);
            assertEquals(EventEnricher.HASH, RiskHash.VERSION);
            assertEquals(enriched.ipHash(), hash.hash("1001", ip));
            assertNotEquals(enriched.ipHash(), hash.hash("1002", ip));
            var snapshot =
                    new PolicySnapshot(
                            "1001:7",
                            3,
                            now,
                            null,
                            now + 1000,
                            PolicyState.KNOWN_RESTRICTED,
                            false,
                            true,
                            "UTC",
                            List.of(),
                            Set.of(enriched.ipHash()),
                            null);
            assertEquals(
                    403,
                    new RiskEvaluator()
                            .evaluate(snapshot, "1001:7", hash.hash("1001", ip), now)
                            .status());
        }
    }
}
