package com.jupiter.shortlink.analytics.api;

import java.util.List;
import java.util.Set;

/** Deduplicates raw identity before applying authorized link scope, with one coherent geo tuple. */
public final class AnalyticsFacts {
    private AnalyticsFacts() {}
    public static String sql(String table, String predicate, String tenant, List<Long> linkIds) {
        if (!Set.of("event_receipts", "rebuild_input").contains(table)) throw new IllegalArgumentException("Invalid facts table");
        String ids = linkIds.isEmpty() ? "0" : String.join(",", linkIds.stream().map(String::valueOf).toList());
        return "SELECT *,tupleElement(geo,1) country,tupleElement(geo,2) province,tupleElement(geo,3) city,"
                + "tupleElement(geo,4) network,tupleElement(geo,5) geo_status,tupleElement(geo,6) geo_version"
                + " FROM (SELECT kind,tenant_id,event_id,any(link_id) link_id,any(occurred_at) occurred_at,"
                + "any(visitor_hash) visitor_hash,any(ip_hash) ip_hash,any(browser) browser,any(os) os,any(device) device,"
                + "argMax(tuple(country,province,city,network,geo_status,geo_version),"
                + "tuple(geo_version!='',geo_version,country,province,city,network,geo_status)) geo,"
                + "uniqExactIf(geo_version,geo_version!='') geo_version_count,"
                + "any(referer_domain) referer_domain,any(request_source) request_source,"
                + "any(decision_stage) decision_stage,any(status) status FROM " + table
                + " WHERE " + predicate + " AND tenant_id=" + ClickHouseReader.quote(tenant)
                + " AND validation_result='VALID' GROUP BY kind,tenant_id,event_id HAVING uniqExact(payload_hash)=1)"
                + " WHERE link_id IN (" + ids + ")";
    }
}
