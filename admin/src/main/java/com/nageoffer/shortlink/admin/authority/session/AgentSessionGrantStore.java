package com.nageoffer.shortlink.admin.authority.session;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nageoffer.shortlink.admin.authority.session.outbox.AgentAuthorityOutboxEvent;
import com.nageoffer.shortlink.admin.authority.session.outbox.JdbcAgentAuthorityOutboxRepository;
import com.nageoffer.shortlink.admin.authority.session.persistence.AgentTokenRevocation;
import com.nageoffer.shortlink.admin.authority.session.persistence.JdbcAgentSessionGrantRepository;
import com.nageoffer.shortlink.admin.authority.session.persistence.JdbcAgentTokenRevocationRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class AgentSessionGrantStore {

    public static final String CAMPAIGN_ANALYSIS_AGENT = "campaign-analysis";

    public static final String SESSION_REVOKED_EVENT = "agent.session.revoked.v1";

    private static final String SESSION_AGGREGATE = "agent-session";

    private static final String TOKEN_ROTATED_REASON = "TOKEN_ROTATED";

    private static final Pattern CONTEXT_ID = Pattern.compile("^[A-Za-z0-9._:-]{1,128}$");

    private static final Pattern DELEGATION_TOKEN_ID = Pattern.compile("^adt-[A-Za-z0-9_-]{1,128}$");

    private static final Pattern SCOPE = Pattern.compile("^[A-Za-z0-9:_-]{1,128}$");

    private static final Set<String> SESSION_REVOCATION_REASONS = Set.of(
            "USER_CLOSED",
            "ADMIN_REVOKED",
            "SESSION_EXPIRED",
            "SECURITY_RESPONSE"
    );

    private final JdbcAgentSessionGrantRepository grantRepository;

    private final JdbcAgentTokenRevocationRepository revocationRepository;

    private final JdbcAgentAuthorityOutboxRepository outboxRepository;

    private final ObjectMapper objectMapper;

    private final Clock clock;

    private final TransactionTemplate transactionTemplate;

    @Autowired
    public AgentSessionGrantStore(
            JdbcAgentSessionGrantRepository grantRepository,
            JdbcAgentTokenRevocationRepository revocationRepository,
            JdbcAgentAuthorityOutboxRepository outboxRepository,
            ObjectMapper objectMapper,
            @Qualifier("agentIdentityClock") Clock clock,
            PlatformTransactionManager transactionManager
    ) {
        this(
                grantRepository,
                revocationRepository,
                outboxRepository,
                objectMapper,
                clock,
                new TransactionTemplate(transactionManager)
        );
    }

    AgentSessionGrantStore(
            JdbcAgentSessionGrantRepository grantRepository,
            JdbcAgentTokenRevocationRepository revocationRepository,
            JdbcAgentAuthorityOutboxRepository outboxRepository,
            ObjectMapper objectMapper,
            Clock clock,
            TransactionTemplate transactionTemplate
    ) {
        this.grantRepository = grantRepository;
        this.revocationRepository = revocationRepository;
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.transactionTemplate = transactionTemplate;
    }

    public String newSessionId() {
        return "as-s-" + UUID.randomUUID().toString().replace("-", "");
    }

    public AgentSessionGrant bootstrap(BootstrapCommand command) {
        validateBootstrap(command);
        Instant now = clock.instant();
        AgentSessionGrant grant = new AgentSessionGrant(
                null,
                command.sessionId(),
                command.tenantId(),
                valueOrEmpty(command.ownerUserId()),
                command.ownerUsername(),
                command.agentType(),
                Set.copyOf(command.scopes()),
                AgentSessionGrantStatus.ACTIVE,
                command.grantVersion(),
                command.tokenId(),
                command.tokenExpiresAt(),
                command.grantExpiresAt(),
                now,
                now
        );
        if (!grantRepository.create(grant)) {
            throw AgentSessionGrantException.conflict();
        }
        return grant;
    }

    public Optional<AgentSessionGrant> find(String sessionId) {
        requireContextId(sessionId, "sessionId");
        return grantRepository.findBySessionId(sessionId);
    }

    public AgentSessionGrant requireOwnedActive(OwnerCommand command) {
        validateOwnerCommand(command);
        AgentSessionGrant grant = grantRepository.findBySessionId(command.sessionId())
                .orElseThrow(AgentSessionGrantException::notFound);
        requireOwner(grant, command.tenantId(), command.ownerUserId(), command.ownerUsername());
        requireActive(grant, clock.instant());
        return grant;
    }

    public RefreshResult refresh(RefreshCommand command) {
        validateRefresh(command);
        return requireTransactionResult(transactionTemplate.execute(status -> refreshInTransaction(command)));
    }

    public RevokeResult revoke(RevokeCommand command) {
        validateRevoke(command);
        return requireTransactionResult(transactionTemplate.execute(status -> revokeInTransaction(command)));
    }

    public AgentSessionGrant requireActiveToken(ActiveTokenCommand command) {
        validateActiveTokenCommand(command);
        AgentSessionGrant grant = grantRepository.findBySessionId(command.sessionId())
                .orElseThrow(AgentSessionGrantException::notFound);
        requireActive(grant, clock.instant());
        String expectedSubject = grant.ownerUserId().isBlank()
                ? "username:" + grant.ownerUsername()
                : grant.ownerUserId();
        if (!grant.tenantId().equals(command.tenantId())
                || !grant.ownerUsername().equals(command.username())
                || !expectedSubject.equals(command.subject())
                || grant.grantVersion() != command.grantVersion()
                || !grant.latestTokenId().equals(command.tokenId())
                || !grant.scopes().containsAll(command.scopes())
                || revocationRepository.isRevoked(command.tokenId(), clock.instant())) {
            throw AgentSessionGrantException.inactive();
        }
        return grant;
    }

    public boolean isTokenRevoked(String tokenId) {
        requireTokenId(tokenId);
        return revocationRepository.isRevoked(tokenId, clock.instant());
    }

    public boolean isActive(String sessionId, long grantVersion, String tokenId) {
        if (sessionId == null
                || !CONTEXT_ID.matcher(sessionId).matches()
                || grantVersion < 1
                || tokenId == null
                || !DELEGATION_TOKEN_ID.matcher(tokenId).matches()) {
            return false;
        }
        Instant now = clock.instant();
        Optional<AgentSessionGrant> candidate = grantRepository.findBySessionId(sessionId);
        if (candidate.isEmpty()) {
            return false;
        }
        AgentSessionGrant grant = candidate.orElseThrow();
        return grant.status() == AgentSessionGrantStatus.ACTIVE
                && grant.expiresAt().isAfter(now)
                && grant.grantVersion() == grantVersion
                && grant.latestTokenId().equals(tokenId)
                && !revocationRepository.isRevoked(tokenId, now);
    }

    private RefreshResult refreshInTransaction(RefreshCommand command) {
        Instant now = clock.instant();
        AgentSessionGrant current = grantRepository.findBySessionIdForUpdate(command.sessionId())
                .orElseThrow(AgentSessionGrantException::notFound);
        requireOwner(current, command.tenantId(), command.ownerUserId(), command.ownerUsername());
        requireActive(current, now);
        if (current.grantVersion() != command.expectedGrantVersion()
                || !current.latestTokenId().equals(command.expectedLatestTokenId())
                || command.nextGrantVersion() != current.grantVersion() + 1) {
            throw AgentSessionGrantException.conflict();
        }
        requireTokenWindow(command.nextTokenExpiresAt(), current.expiresAt(), now);
        if (!grantRepository.rotateLatestToken(
                current.sessionId(),
                current.grantVersion(),
                current.latestTokenId(),
                command.nextTokenId(),
                command.nextTokenExpiresAt(),
                now
        )) {
            throw AgentSessionGrantException.conflict();
        }
        recordRevocation(
                current.latestTokenId(),
                current.sessionId(),
                current.tenantId(),
                current.grantVersion(),
                TOKEN_ROTATED_REASON,
                now,
                current.latestTokenExpiresAt()
        );
        AgentSessionGrant refreshed = copy(
                current,
                AgentSessionGrantStatus.ACTIVE,
                command.nextGrantVersion(),
                command.nextTokenId(),
                command.nextTokenExpiresAt(),
                now
        );
        return new RefreshResult(refreshed, current.latestTokenId());
    }

    private RevokeResult revokeInTransaction(RevokeCommand command) {
        Instant now = clock.instant();
        AgentSessionGrant current = grantRepository.findBySessionIdForUpdate(command.sessionId())
                .orElseThrow(AgentSessionGrantException::notFound);
        requireOwner(current, command.tenantId(), command.ownerUserId(), command.ownerUsername());
        if (current.status() == AgentSessionGrantStatus.REVOKED) {
            return new RevokeResult(current, false, null);
        }
        if (current.status() != AgentSessionGrantStatus.ACTIVE) {
            throw AgentSessionGrantException.inactive();
        }
        if (!grantRepository.revoke(current.sessionId(), current.grantVersion(), now)) {
            throw AgentSessionGrantException.conflict();
        }
        long revokedVersion = current.grantVersion() + 1;
        recordRevocation(
                current.latestTokenId(),
                current.sessionId(),
                current.tenantId(),
                current.grantVersion(),
                command.reason(),
                now,
                current.latestTokenExpiresAt()
        );
        AgentSessionGrant revoked = copy(
                current,
                AgentSessionGrantStatus.REVOKED,
                revokedVersion,
                "",
                null,
                now
        );
        String eventId = "ase-" + UUID.randomUUID().toString().replace("-", "");
        AgentAuthorityOutboxEvent event = new AgentAuthorityOutboxEvent(
                eventId,
                SESSION_REVOKED_EVENT,
                SESSION_AGGREGATE,
                current.sessionId(),
                revokedVersion,
                current.tenantId(),
                revocationPayload(eventId, current, revokedVersion, command.reason(), now),
                now
        );
        if (!outboxRepository.createIfAbsent(event)) {
            throw AgentSessionGrantException.conflict();
        }
        return new RevokeResult(revoked, true, eventId);
    }

    private void recordRevocation(
            String tokenId,
            String sessionId,
            String tenantId,
            long grantVersion,
            String reason,
            Instant revokedAt,
            Instant tokenExpiresAt
    ) {
        if (tokenId == null || tokenId.isBlank() || tokenExpiresAt == null || !tokenExpiresAt.isAfter(revokedAt)) {
            return;
        }
        revocationRepository.createIfAbsent(new AgentTokenRevocation(
                tokenId,
                sessionId,
                tenantId,
                grantVersion,
                reason,
                revokedAt,
                tokenExpiresAt
        ));
    }

    private String revocationPayload(
            String eventId,
            AgentSessionGrant grant,
            long revokedVersion,
            String reason,
            Instant occurredAt
    ) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("eventId", eventId);
        payload.put("eventType", SESSION_REVOKED_EVENT);
        payload.put("occurredAt", occurredAt.toString());
        payload.put("tenantId", grant.tenantId());
        payload.put("sessionId", grant.sessionId());
        payload.put("grantVersion", revokedVersion);
        payload.put("status", AgentSessionGrantStatus.REVOKED.name());
        payload.put("reasonCode", reason);
        payload.put("revokedAt", occurredAt.toString());
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Agent session revocation event cannot be serialized.", exception);
        }
    }

    private AgentSessionGrant copy(
            AgentSessionGrant current,
            AgentSessionGrantStatus status,
            long grantVersion,
            String latestTokenId,
            Instant latestTokenExpiresAt,
            Instant now
    ) {
        return new AgentSessionGrant(
                current.id(),
                current.sessionId(),
                current.tenantId(),
                current.ownerUserId(),
                current.ownerUsername(),
                current.agentType(),
                current.scopes(),
                status,
                grantVersion,
                latestTokenId,
                latestTokenExpiresAt,
                current.expiresAt(),
                current.createdAt(),
                now
        );
    }

    private void validateBootstrap(BootstrapCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        requireContextId(command.sessionId(), "sessionId");
        if (!command.sessionId().startsWith("as-s-")) {
            throw AgentSessionGrantException.invalid("sessionId must be generated by the authority service.");
        }
        requireContextId(command.tenantId(), "tenantId");
        requireOwnerFields(command.ownerUserId(), command.ownerUsername());
        if (!CAMPAIGN_ANALYSIS_AGENT.equals(command.agentType())) {
            throw AgentSessionGrantException.invalid("Only campaign-analysis grants can be created.");
        }
        requireScopes(command.scopes());
        if (command.grantVersion() != 1) {
            throw AgentSessionGrantException.invalid("Initial grantVersion must be 1.");
        }
        requireTokenId(command.tokenId());
        Instant now = clock.instant();
        if (command.grantExpiresAt() == null || !command.grantExpiresAt().isAfter(now)) {
            throw AgentSessionGrantException.invalid("grantExpiresAt must be in the future.");
        }
        requireTokenWindow(command.tokenExpiresAt(), command.grantExpiresAt(), now);
    }

    private void validateOwnerCommand(OwnerCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        requireContextId(command.sessionId(), "sessionId");
        requireContextId(command.tenantId(), "tenantId");
        requireOwnerFields(command.ownerUserId(), command.ownerUsername());
    }

    private void validateRefresh(RefreshCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        validateOwnerCommand(new OwnerCommand(
                command.sessionId(),
                command.tenantId(),
                command.ownerUserId(),
                command.ownerUsername()
        ));
        if (command.expectedGrantVersion() < 1 || command.nextGrantVersion() < 2) {
            throw AgentSessionGrantException.invalid("Refresh grant versions are invalid.");
        }
        requireTokenId(command.expectedLatestTokenId());
        requireTokenId(command.nextTokenId());
        if (command.expectedLatestTokenId().equals(command.nextTokenId())) {
            throw AgentSessionGrantException.invalid("Refresh must rotate the delegation token id.");
        }
    }

    private void validateRevoke(RevokeCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        validateOwnerCommand(new OwnerCommand(
                command.sessionId(),
                command.tenantId(),
                command.ownerUserId(),
                command.ownerUsername()
        ));
        if (!SESSION_REVOCATION_REASONS.contains(command.reason())) {
            throw AgentSessionGrantException.invalid("Revocation reason is invalid.");
        }
    }

    private void validateActiveTokenCommand(ActiveTokenCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        requireContextId(command.sessionId(), "sessionId");
        requireContextId(command.tenantId(), "tenantId");
        requireOwnerFields(null, command.username());
        if (command.subject() == null || command.subject().isBlank() || command.subject().length() > 256) {
            throw AgentSessionGrantException.invalid("Token subject is invalid.");
        }
        if (command.grantVersion() < 1) {
            throw AgentSessionGrantException.invalid("Token grantVersion is invalid.");
        }
        requireTokenId(command.tokenId());
        requireScopes(command.scopes());
    }

    private void requireOwner(
            AgentSessionGrant grant,
            String tenantId,
            String ownerUserId,
            String ownerUsername
    ) {
        if (!grant.tenantId().equals(tenantId)
                || !grant.ownerUserId().equals(valueOrEmpty(ownerUserId))
                || !grant.ownerUsername().equals(ownerUsername)) {
            throw AgentSessionGrantException.forbidden();
        }
    }

    private void requireActive(AgentSessionGrant grant, Instant now) {
        if (grant.status() != AgentSessionGrantStatus.ACTIVE) {
            throw AgentSessionGrantException.inactive();
        }
        if (!grant.expiresAt().isAfter(now)) {
            throw AgentSessionGrantException.expired();
        }
    }

    private void requireOwnerFields(String ownerUserId, String ownerUsername) {
        if (ownerUsername == null || ownerUsername.isBlank() || ownerUsername.length() > 128
                || (ownerUserId != null && ownerUserId.length() > 256)) {
            throw AgentSessionGrantException.invalid("Agent session owner is invalid.");
        }
    }

    private void requireContextId(String value, String fieldName) {
        if (value == null || !CONTEXT_ID.matcher(value).matches()) {
            throw AgentSessionGrantException.invalid(fieldName + " is invalid.");
        }
    }

    private void requireTokenId(String value) {
        if (value == null || !DELEGATION_TOKEN_ID.matcher(value).matches()) {
            throw AgentSessionGrantException.invalid("Delegation token id is invalid.");
        }
    }

    private void requireScopes(Set<String> scopes) {
        if (scopes == null || scopes.isEmpty() || scopes.size() > 32
                || scopes.stream().anyMatch(each -> each == null || !SCOPE.matcher(each).matches())) {
            throw AgentSessionGrantException.invalid("Agent session scopes are invalid.");
        }
    }

    private void requireTokenWindow(Instant tokenExpiresAt, Instant grantExpiresAt, Instant now) {
        if (tokenExpiresAt == null
                || !tokenExpiresAt.isAfter(now)
                || tokenExpiresAt.isAfter(grantExpiresAt)) {
            throw AgentSessionGrantException.invalid("Delegation token expiry exceeds the grant window.");
        }
    }

    private String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }

    private <T> T requireTransactionResult(T value) {
        return Objects.requireNonNull(value, "Transaction completed without a result.");
    }

    public record BootstrapCommand(
            String sessionId,
            String tenantId,
            String ownerUserId,
            String ownerUsername,
            String agentType,
            Set<String> scopes,
            long grantVersion,
            String tokenId,
            Instant tokenExpiresAt,
            Instant grantExpiresAt
    ) {
    }

    public record OwnerCommand(
            String sessionId,
            String tenantId,
            String ownerUserId,
            String ownerUsername
    ) {
    }

    public record RefreshCommand(
            String sessionId,
            String tenantId,
            String ownerUserId,
            String ownerUsername,
            long expectedGrantVersion,
            String expectedLatestTokenId,
            long nextGrantVersion,
            String nextTokenId,
            Instant nextTokenExpiresAt
    ) {
    }

    public record RevokeCommand(
            String sessionId,
            String tenantId,
            String ownerUserId,
            String ownerUsername,
            String reason
    ) {
    }

    public record ActiveTokenCommand(
            String sessionId,
            String tenantId,
            String subject,
            String username,
            long grantVersion,
            Set<String> scopes,
            String tokenId
    ) {
    }

    public record RefreshResult(AgentSessionGrant grant, String revokedTokenId) {
    }

    public record RevokeResult(AgentSessionGrant grant, boolean changed, String eventId) {
    }
}
