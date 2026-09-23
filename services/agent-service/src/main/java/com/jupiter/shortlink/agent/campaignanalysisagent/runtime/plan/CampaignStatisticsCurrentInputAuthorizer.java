package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan;

import com.jupiter.shortlink.agent.business.shortlink.AgentAuthorityClient;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.CapabilityCatalog.TypeRef;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.binding.StepBindings;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.Caller;
import com.jupiter.shortlink.agent.harness.security.AgentPrincipal;
import com.jupiter.shortlink.contract.GroupMembersPage;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/** Current authorization for the parseable ScopeRef and PeriodsRef issued by statistics plans. */
public final class CampaignStatisticsCurrentInputAuthorizer implements StepBindings.CurrentInputAuthorizer {
    private static final String GROUP_PREFIX = "current-group.v1:";
    private static final String PERIOD_PREFIX = "period.v1:";
    private static final LocalDate EARLIEST = LocalDate.of(1970, 1, 1);
    private final AgentAuthorityClient authority;

    public record DateRange(LocalDate startDate, LocalDate endDate) {}

    public CampaignStatisticsCurrentInputAuthorizer(AgentAuthorityClient authority) {
        this.authority = Objects.requireNonNull(authority);
    }

    @Override
    public boolean mayUse(Caller caller, TypeRef type, Object frozenValue) {
        if (caller == null || !(frozenValue instanceof String ref)) return false;
        boolean scope = FrozenStatisticsJobQuery.SCOPE_TYPE.equals(type);
        boolean period = FrozenStatisticsJobQuery.PERIODS_TYPE.equals(type);
        if (!scope && !period) return false;
        String gid;
        try {
            gid = scope ? parseGroupId(ref) : null;
            if (period) parsePeriod(ref);
        } catch (IllegalArgumentException malformed) { return false; }
        try {
            AgentPrincipal expected = new AgentPrincipal(caller.tenantId(), caller.subject(), caller.authVersion(), false);
            AgentPrincipal current = authority.verifyCurrentPrincipal(expected);
            if (!expected.equals(current)) return false;
            if (scope) {
                GroupMembersPage page = authority.resolveGroupMembersPage(current, gid, null, null);
                if (page == null) return false;
                page.requireMatches(new GroupMembersPage.Request(gid, null, null),
                        current.tenantId(), current.username(), current.authVersion());
            }
            return true;
        } catch (RuntimeException denied) {
            // StepBindings turns false into INPUT_ACCESS_DENIED. Stale auth, missing authority,
            // malformed proof and remote failure must all leave the frozen input unusable.
            return false;
        }
    }

    /** Throws on every noncanonical or unsupported scope reference. */
    public static String parseGroupId(String ref) {
        if (ref == null || !ref.startsWith(GROUP_PREFIX)) throw new IllegalArgumentException("STATISTICS_SCOPE_REF_INVALID");
        String gid = ref.substring(GROUP_PREFIX.length());
        if (!gid.matches("[A-Za-z0-9_-]{1,64}")) throw new IllegalArgumentException("STATISTICS_SCOPE_REF_INVALID");
        return gid;
    }

    /** The two dates are inclusive and use the statistics business calendar. */
    public static DateRange parsePeriod(String ref) {
        if (ref == null || !ref.matches("period\\.v1:[0-9]{4}-[0-9]{2}-[0-9]{2}:[0-9]{4}-[0-9]{2}-[0-9]{2}"))
            throw new IllegalArgumentException("STATISTICS_PERIOD_REF_INVALID");
        try {
            String[] parts = ref.substring(PERIOD_PREFIX.length()).split(":", -1);
            LocalDate start = LocalDate.parse(parts[0]);
            LocalDate end = LocalDate.parse(parts[1]);
            long days = ChronoUnit.DAYS.between(start, end) + 1;
            if (start.isBefore(EARLIEST) || days < 1 || days > 180)
                throw new IllegalArgumentException("STATISTICS_PERIOD_REF_INVALID");
            return new DateRange(start, end);
        } catch (DateTimeException | ArithmeticException invalid) {
            throw new IllegalArgumentException("STATISTICS_PERIOD_REF_INVALID", invalid);
        }
    }
}
