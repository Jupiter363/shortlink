package com.jupiter.shortlink.contract;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** A current, version-pinned authority page. The version is not a historical MVCC snapshot. */
@JsonInclude(JsonInclude.Include.ALWAYS)
@JsonDeserialize(using = GroupMembersPage.PageDeserializer.class)
public record GroupMembersPage(String schemaVersion, String tenantId, String subjectId, long authVersion,
                               String gid, String ownershipVersion, Long afterLinkId,
                               List<Long> linkIds, Long nextCursor) {
    public static final String SCHEMA = "group-members-page/v1";
    public static final int PAGE_SIZE = 500;
    private static final Set<String> FIELDS = Set.of("schemaVersion", "tenantId", "subjectId", "authVersion",
            "gid", "ownershipVersion", "afterLinkId", "linkIds", "nextCursor");

    public GroupMembersPage {
        require(SCHEMA.equals(schemaVersion), "Unsupported authority page schema");
        require(tenantId != null && tenantId.matches("[1-9][0-9]{0,18}"), "Invalid authority tenant");
        bounded(subjectId, 128); group(gid);
        require(authVersion >= 0, "Invalid authority version");
        hash(ownershipVersion);
        require(afterLinkId == null || afterLinkId > 0, "Invalid authority cursor");
        require(linkIds != null && linkIds.size() <= PAGE_SIZE, "Invalid authority page size");
        require(!linkIds.isEmpty() || (afterLinkId == null && nextCursor == null), "Only a terminal first page can prove an empty group");
        long previous = afterLinkId == null ? 0 : afterLinkId;
        for (Long id : linkIds) {
            require(id != null && id > previous, "Authority members must be positive, unique and ordered");
            previous = id;
        }
        linkIds = List.copyOf(linkIds);
        require(nextCursor == null || (linkIds.size() == PAGE_SIZE && nextCursor > 0 && nextCursor == previous),
                "Nonterminal authority page requires 500 members and the last member cursor");
    }

    public static GroupMembersPage fromMap(Map<?, ?> value) {
        require(value != null && value.keySet().equals(FIELDS), "Authority page fields changed");
        require(value.get("linkIds") instanceof List<?>, "Authority members are required");
        require(((List<?>) value.get("linkIds")).size() <= PAGE_SIZE, "Invalid authority page size");
        var members = new ArrayList<Long>();
        for (Object id : (List<?>) value.get("linkIds")) members.add(integer(id, false));
        return new GroupMembersPage(text(value, "schemaVersion"), text(value, "tenantId"), text(value, "subjectId"),
                integer(value.get("authVersion"), true), text(value, "gid"), text(value, "ownershipVersion"),
                nullableId(value.get("afterLinkId")), members, nullableId(value.get("nextCursor")));
    }

    /** Validate the page against the exact trusted principal and frozen request, including cursor echo. */
    public void requireMatches(Request request, String tenant, String subject, long version) {
        require(tenantId.equals(tenant) && subjectId.equals(subject) && authVersion == version
                && gid.equals(request.gid()) && Objects.equals(afterLinkId, request.afterLinkId()),
                "Authority page identity mismatch");
        require(request.ownershipVersion() == null || request.ownershipVersion().equals(ownershipVersion),
                "QUERY_SCOPE_CHANGED");
    }

    public Map<String, Object> asMap() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("schemaVersion", schemaVersion); value.put("tenantId", tenantId); value.put("subjectId", subjectId);
        value.put("authVersion", authVersion); value.put("gid", gid); value.put("ownershipVersion", ownershipVersion);
        value.put("afterLinkId", afterLinkId); value.put("linkIds", linkIds); value.put("nextCursor", nextCursor);
        return java.util.Collections.unmodifiableMap(value);
    }

    /** No principal, arbitrary URLs or caller-supplied member lists are accepted by this endpoint. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonDeserialize(using = GroupMembersPage.RequestDeserializer.class)
    public record Request(String gid, Long afterLinkId, String ownershipVersion) {
        public Request {
            group(gid);
            require((afterLinkId == null) == (ownershipVersion == null), "Authority cursor and version must be paired");
            if (afterLinkId != null) {
                require(afterLinkId > 0, "Invalid authority cursor");
                hash(ownershipVersion);
            }
        }

        public static Request fromMap(Map<?, ?> value) {
            require(value != null && value.containsKey("gid")
                    && Set.of("gid", "afterLinkId", "ownershipVersion").containsAll(value.keySet()),
                    "Authority request fields changed");
            Object version = value.get("ownershipVersion");
            require(version == null || version instanceof String, "Invalid authority version");
            return new Request(text(value, "gid"), nullableId(value.get("afterLinkId")), (String) version);
        }

        public Map<String, Object> asMap() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("gid", gid);
            if (afterLinkId != null) { value.put("afterLinkId", afterLinkId); value.put("ownershipVersion", ownershipVersion); }
            return java.util.Collections.unmodifiableMap(value);
        }
    }

    /** Avoid record/ParameterNamesModule creator collisions without allowing numeric coercion. */
    public static final class PageDeserializer extends JsonDeserializer<GroupMembersPage> {
        @Override public GroupMembersPage deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            Map<String, Object> value = parser.readValueAs(new TypeReference<Map<String, Object>>() {});
            try { return fromMap(value); }
            catch (IllegalArgumentException invalid) {
                return context.reportInputMismatch(GroupMembersPage.class, "AUTHORITY_PAGE_INVALID");
            }
        }
    }

    public static final class RequestDeserializer extends JsonDeserializer<Request> {
        @Override public Request deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            Map<String, Object> value = parser.readValueAs(new TypeReference<Map<String, Object>>() {});
            try { return Request.fromMap(value); }
            catch (IllegalArgumentException invalid) {
                return context.reportInputMismatch(Request.class, "AUTHORITY_REQUEST_INVALID");
            }
        }
    }

    private static Long nullableId(Object value) { return value == null ? null : integer(value, false); }
    private static long integer(Object value, boolean allowZero) {
        long number;
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long)
            number = ((Number) value).longValue();
        else if (value instanceof BigInteger big && big.bitLength() < 64) number = big.longValue();
        else throw new IllegalArgumentException("Authority identities require integral JSON numbers");
        require(allowZero ? number >= 0 : number > 0, "Invalid authority numeric identity");
        return number;
    }
    private static String text(Map<?, ?> value, String key) {
        require(value.get(key) instanceof String, "Missing authority text field");
        return (String) value.get(key);
    }
    private static void bounded(String value, int max) {
        require(value != null && !value.isBlank() && value.length() <= max, "Invalid authority reference");
    }
    private static void group(String value) {
        require(value != null && value.matches("[A-Za-z0-9_-]{1,64}"), "Invalid authority group identity");
    }
    private static void hash(String value) {
        require(value != null && value.matches("[a-f0-9]{64}"), "Invalid authority ownership version");
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
