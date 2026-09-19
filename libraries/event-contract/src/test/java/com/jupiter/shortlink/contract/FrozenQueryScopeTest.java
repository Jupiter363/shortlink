package com.jupiter.shortlink.contract;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FrozenQueryScopeTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void wireAndProofRoundTripUseOneClosedCanonicalMemberContract() throws Exception {
        var ids = List.of(1L, 2L);
        String hash = FrozenQueryScope.memberHash(ids);
        var scope = new FrozenQueryScope(FrozenQueryScope.SCHEMA, "FROZEN_SET", "scope-1", hash, 2,
                "a".repeat(64), FrozenQueryScope.shardIdFor("scope-1", 0, hash), 0, 1, hash, ids);
        assertEquals(scope, json.readValue(json.writeValueAsString(scope), FrozenQueryScope.class));
        assertEquals(scope, FrozenQueryScope.fromProof(scope.proof("b".repeat(64))));
        assertFalse((boolean) scope.proof("b".repeat(64)).get("parentComplete"));
        for (Object badIds : List.of(List.of("1", "2"), List.of(1.0, 2.0), List.of(2, 1), List.of(1, 1))) {
            var bad = new LinkedHashMap<>(scope.asMap());
            bad.put("linkIds", badIds);
            assertThrows(Exception.class, () -> json.readValue(json.writeValueAsString(bad), FrozenQueryScope.class));
        }
        var extra = new LinkedHashMap<>(scope.asMap());
        extra.put("expandGroup", true);
        assertThrows(Exception.class, () -> json.readValue(json.writeValueAsString(extra), FrozenQueryScope.class));
        var inflated = new LinkedHashMap<>(scope.proof("b".repeat(64)));
        inflated.put("parentComplete", true);
        assertThrows(IllegalArgumentException.class, () -> FrozenQueryScope.fromProof(inflated));
        assertThrows(IllegalArgumentException.class, () -> FrozenQueryScope.fromMap(scope.proof("b".repeat(64))));
    }
}
