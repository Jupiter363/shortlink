package com.jupiter.shortlink.contract;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GroupMembersPageTest {
    @Test
    void strictPagesPreserveIdentityCursorAndEmptyScopeWithoutCoercingNumbers() throws Exception {
        var json = new ObjectMapper();
        String version = "a".repeat(64);
        var request = new GroupMembersPage.Request("g1", null, null);
        var page = new GroupMembersPage(GroupMembersPage.SCHEMA, "1001", "analyst", 7, "g1", version,
                null, LongStream.rangeClosed(1, 500).boxed().toList(), 500L);
        assertEquals(page, json.readValue(json.writeValueAsString(page), GroupMembersPage.class));
        assertEquals(request, json.readValue(json.writeValueAsString(request), GroupMembersPage.Request.class));
        page.requireMatches(request, "1001", "analyst", 7);
        var terminal = new GroupMembersPage(GroupMembersPage.SCHEMA, "1001", "analyst", 7, "g1", version, 500L, List.of(501L), null);
        terminal.requireMatches(new GroupMembersPage.Request("g1", 500L, version), "1001", "analyst", 7);
        var empty = new GroupMembersPage(GroupMembersPage.SCHEMA, "1001", "analyst", 7, "g1", version, null, List.of(), null);
        assertTrue(json.readTree(json.writeValueAsString(empty)).has("nextCursor"));
        assertEquals(empty, GroupMembersPage.fromMap(empty.asMap()));
        assertThrows(IllegalArgumentException.class, () -> new GroupMembersPage.Request("g".repeat(65), null, null));
        assertThrows(IllegalArgumentException.class, () -> new GroupMembersPage.Request("g/1", null, null));
        assertThrows(IllegalArgumentException.class, () -> new GroupMembersPage(GroupMembersPage.SCHEMA, "1001", "analyst", 7,
                "g1", version, 500L, List.of(), null));
        for (Object ids : List.of(List.of("1"), List.of(1.0), List.of(1L, 1L), List.of(2L, 1L), List.of(0L))) {
            var invalid = new LinkedHashMap<>(empty.asMap()); invalid.put("linkIds", ids);
            assertThrows(IllegalArgumentException.class, () -> GroupMembersPage.fromMap(invalid));
        }
        for (Map<String, Object> body : List.of(Map.<String, Object>of("gid", "g1", "fullShortUrl", "x"),
                Map.<String, Object>of("gid", "g1", "linkIds", List.of()),
                Map.<String, Object>of("gid", "g1", "afterLinkId", "500", "ownershipVersion", version),
                Map.<String, Object>of("gid", "g1", "afterLinkId", 500)))
            assertThrows(Exception.class, () -> json.readValue(json.writeValueAsString(body), GroupMembersPage.Request.class));
        var unknown = new LinkedHashMap<>(empty.asMap()); unknown.put("snapshotId", "invented");
        assertThrows(IllegalArgumentException.class, () -> GroupMembersPage.fromMap(unknown));
        assertThrows(IllegalArgumentException.class, () -> terminal.requireMatches(request, "1001", "analyst", 7));
        assertThrows(IllegalArgumentException.class, () -> page.requireMatches(request, "1002", "analyst", 7));
        assertThrows(IllegalArgumentException.class, () -> new GroupMembersPage(GroupMembersPage.SCHEMA, "1001", "analyst", 7,
                "g1", version, null, List.of(1L), 1L));
    }
}
