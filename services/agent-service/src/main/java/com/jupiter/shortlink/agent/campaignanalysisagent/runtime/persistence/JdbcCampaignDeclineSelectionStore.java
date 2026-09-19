package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.CampaignLinkComparability.Result;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.CampaignParentCoverage.Period;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.DeclineSelectionPage;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.DeclineSelectionPage.*;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Source-backed local projection; every read verifies bounded source pages before using the SQL index. */
public final class JdbcCampaignDeclineSelectionStore implements CampaignDeclineSelectionStore {
    private static final String SELECTED_TYPE = "SelectedEntitiesArtifact";
    private static final String SELECTED_SCHEMA = "campaign.selected-entities/v1";
    private static final String EVIDENCE_TYPE = "DeclineEvidenceArtifact";
    private static final String EVIDENCE_SCHEMA = "campaign.decline-evidence/v1";
    private static final Set<String> FINAL_FIELDS = Set.of("schemaVersion", "collectionId", "scopeRef", "periodsRef",
            "headArtifactId", "headPayloadHash", "chainHash", "candidateCount", "comparedCount", "selectedCount",
            "selectionComplete", "emptyReason");
    private static final Set<String> CURSOR_FIELDS = Set.of("collectionId", "artifactHash", "order", "delta", "linkId");
    private static final Set<String> PERIOD_FIELDS = Set.of("periodsRef", "startDate", "endDate", "timeZone");
    private static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final CampaignRunStore runs;

    public JdbcCampaignDeclineSelectionStore(JdbcTemplate jdbc, TransactionTemplate transactions, Clock clock,
                                             CampaignRunStore runs) {
        this.jdbc = Objects.requireNonNull(jdbc); this.transactions = Objects.requireNonNull(transactions);
        this.clock = Objects.requireNonNull(clock); this.runs = Objects.requireNonNull(runs);
        if (!(transactions.getTransactionManager() instanceof DataSourceTransactionManager manager)
                || manager.getDataSource() != jdbc.getDataSource()
                || transactions.getPropagationBehavior() != TransactionDefinition.PROPAGATION_REQUIRED
                || transactions.isReadOnly() || !(runs instanceof JdbcCampaignRunStore ledger)
                || !ledger.sharesTransactionDataSource(jdbc))
            throw new IllegalArgumentException("Selection and run stores require one writable REQUIRED DataSource transaction");
    }

    @Override public Receipt append(RunToken token, String childId, ArtifactAuthorizer authorizer) {
        id(childId);
        return transaction(() -> {
            requireRun(token, true);
            Pair incoming = pair(token, childId, authorizer);
            Definition definition = incoming.page().definition();
            validateDefinition(definition);
            Artifact scope = scope(token.definition().caller(), definition, authorizer);
            requireDeclaredInput(incoming.child(), scope.metadata());
            Optional<Stored> found = find(definition.collectionId(), true);
            if (found.isEmpty()) {
                require(incoming.page().shardIndex() == 0, "SELECTION_PAGE_NOT_CONTIGUOUS");
                String encoded = encode(definition);
                jdbc.update("INSERT INTO campaign_decline_collection (collection_id,run_id,revision,step_id,executor_version,"
                                + "scope_artifact_id,definition_json,definition_hash,committed_pages,sealed,created_at,updated_at) "
                                + "VALUES (?,?,?,?,?,?,?,?,0,FALSE,?,?)",
                        definition.collectionId(), token.definition().runId(), token.definition().revision(), incoming.stepId(),
                        incoming.pageArtifact().metadata().executorVersion(), definition.scopeArtifactId(), encoded,
                        CampaignRunStore.sha256(encoded), clock.millis(), clock.millis());
            }
            Stored stored = required(definition.collectionId(), true);
            matchOwner(token, stored); matchPair(stored, incoming);
            int ordinal = incoming.page().shardIndex();
            Artifact previous = previous(token, stored, ordinal, authorizer);
            verifyAdvance(incoming, previous);
            Optional<SavedPage> existing = page(definition.collectionId(), ordinal);
            if (existing.isPresent()) {
                require(ordinal < stored.receipt().committedPages(), "SELECTION_PAGE_NOT_CONTIGUOUS");
                matchSaved(existing.get(), incoming);
                verifyRows(definition.collectionId(), incoming.page());
                return stored.receipt();
            }
            require(!stored.receipt().sealed(), "SELECTION_ALREADY_SEALED");
            require(ordinal == stored.receipt().committedPages(), "SELECTION_PAGE_NOT_CONTIGUOUS");
            if (previous != null) require(previous.metadata().ref().artifactId().equals(stored.receipt().chainArtifactId())
                    && previous.metadata().ref().payloadHash().equals(stored.receipt().chainPayloadHash()), "SELECTION_HEAD_CHANGED");
            jdbc.update("INSERT INTO campaign_decline_page (collection_id,ordinal_index,run_id,revision,child_id,"
                            + "page_artifact_id,page_payload_hash,chain_artifact_id,chain_payload_hash) VALUES (?,?,?,?,?,?,?,?,?)",
                    definition.collectionId(), ordinal, token.definition().runId(), token.definition().revision(), childId,
                    incoming.pageArtifact().metadata().ref().artifactId(), incoming.pageArtifact().metadata().ref().payloadHash(),
                    incoming.chainArtifact().metadata().ref().artifactId(), incoming.chainArtifact().metadata().ref().payloadHash());
            Set<Long> selected = selectedIds(incoming.page());
            for (Result row : incoming.page().rows())
                jdbc.update("INSERT INTO campaign_decline_row (collection_id,link_id,ordinal_index,delta_value,selected,result_json) "
                                + "VALUES (?,?,?,?,?,?)", definition.collectionId(), row.linkId(), ordinal,
                        row.delta().longValueExact(), selected.contains(row.linkId()), encode(row));
            jdbc.update("UPDATE campaign_decline_collection SET committed_pages=?,chain_artifact_id=?,chain_payload_hash=?,updated_at=? "
                            + "WHERE collection_id=?", ordinal + 1, incoming.chainArtifact().metadata().ref().artifactId(),
                    incoming.chainArtifact().metadata().ref().payloadHash(), clock.millis(), definition.collectionId());
            return required(definition.collectionId(), true).receipt();
        });
    }

    @Override public Optional<Receipt> loadReceipt(RunToken token, String collectionId, ArtifactAuthorizer authorizer) {
        id(collectionId); Objects.requireNonNull(authorizer);
        return transaction(() -> {
            requireRun(token, false);
            Optional<Stored> found = find(collectionId, true);
            if (found.isEmpty()) return Optional.empty();
            Stored stored = found.get(); matchOwner(token, stored);
            Chain head = verifyPrefix(token, stored, authorizer);
            if (stored.receipt().sealed()) {
                requireCompletePages(stored.receipt());
                FinalPair actual = finalPair(token, stored, stored.finalChildId(), head, authorizer);
                require(actual.selected().metadata().ref().artifactId().equals(stored.receipt().selectedArtifactId())
                        && actual.evidence().metadata().ref().artifactId().equals(stored.receipt().evidenceArtifactId()),
                        "SELECTION_FINAL_CHANGED");
            }
            return Optional.of(stored.receipt());
        });
    }

    @Override public Receipt seal(RunToken token, String collectionId, String finalChildId, ArtifactAuthorizer authorizer) {
        id(collectionId); id(finalChildId);
        return transaction(() -> {
            requireRun(token, true);
            Stored stored = required(collectionId, true); matchOwner(token, stored);
            Chain head = verifyAll(token, stored, authorizer);
            FinalPair outputs = finalPair(token, stored, finalChildId, head, authorizer);
            if (stored.receipt().sealed()) {
                require(finalChildId.equals(stored.finalChildId())
                        && outputs.selected().metadata().ref().artifactId().equals(stored.receipt().selectedArtifactId())
                        && outputs.evidence().metadata().ref().artifactId().equals(stored.receipt().evidenceArtifactId()),
                        "SELECTION_FINAL_CHANGED");
                return stored.receipt();
            }
            jdbc.update("UPDATE campaign_decline_collection SET sealed=TRUE,final_child_id=?,selected_artifact_id=?,"
                            + "evidence_artifact_id=?,updated_at=? WHERE collection_id=? AND sealed=FALSE", finalChildId,
                    outputs.selected().metadata().ref().artifactId(), outputs.evidence().metadata().ref().artifactId(),
                    clock.millis(), collectionId);
            return required(collectionId, true).receipt();
        });
    }

    @Override public SelectionPair inspectPair(Caller caller, String selectedId, String evidenceId, ArtifactAuthorizer authorizer) {
        id(selectedId); id(evidenceId); Objects.requireNonNull(authorizer);
        return transaction(() -> {
            Artifact selected = runs.readArtifact(caller, selectedId, authorizer);
            Artifact evidence = runs.readArtifact(caller, evidenceId, authorizer);
            contract(selected, SELECTED_TYPE, SELECTED_SCHEMA); contract(evidence, EVIDENCE_TYPE, EVIDENCE_SCHEMA);
            List<String> keys = jdbc.queryForList("SELECT collection_id FROM campaign_decline_collection "
                    + "WHERE selected_artifact_id=? AND evidence_artifact_id=?", String.class, selectedId, evidenceId);
            require(keys.size() == 1, "SELECTION_PAIR_MISMATCH");
            Stored peek = required(keys.get(0), false);
            RunToken token = currentStoredToken(caller, peek);
            Stored stored = required(keys.get(0), true); matchOwner(token, stored);
            require(stored.receipt().sealed(), "SELECTION_NOT_SEALED");
            requireCompletePages(stored.receipt());
            List<List<Period>> sourcePeriods = new ArrayList<>(1);
            Chain head = verifyPrefix(token, stored, authorizer, page -> {
                List<Period> actual = sourcePeriods(page);
                if (sourcePeriods.isEmpty()) sourcePeriods.add(actual);
                else require(sourcePeriods.get(0).equals(actual), "SELECTION_PERIODS_MISMATCH");
            });
            require(sourcePeriods.size() == 1, "SELECTION_PERIODS_MISSING");
            FinalPair actual = finalPair(token, stored, stored.finalChildId(), head, authorizer);
            require(selected.equals(actual.selected()) && evidence.equals(actual.evidence()), "SELECTION_PAIR_MISMATCH");
            Artifact scope = scope(caller, stored.receipt().definition(), authorizer);
            // A long source-chain check must not return permission or expiry captured only at entry.
            require(selected.metadata().equals(runs.inspectArtifact(caller, selectedId, authorizer))
                    && evidence.metadata().equals(runs.inspectArtifact(caller, evidenceId, authorizer))
                    && scope.metadata().equals(runs.inspectArtifact(caller, scope.metadata().ref().artifactId(), authorizer)),
                    "SELECTION_FINAL_CHANGED");
            return new SelectionPair(selected.metadata(), evidence.metadata(), stored.receipt().definition(),
                    scope.metadata(), sourcePeriods.get(0), DeclineSelectionPage.selectionComplete(head),
                    DeclineSelectionPage.emptyReason(head), head.totals().selected(), stored.stepId());
        });
    }

    @Override public PageResult readSelectedPage(Caller caller, String artifactId, String cursor, int size, ArtifactAuthorizer authorizer) {
        return read(caller, artifactId, cursor, size, authorizer, ReadOrder.SELECTED_DELTA);
    }

    @Override public PageResult readSelectedByLinkId(Caller caller, String artifactId, String cursor, int size, ArtifactAuthorizer authorizer) {
        return read(caller, artifactId, cursor, size, authorizer, ReadOrder.SELECTED_LINK);
    }

    @Override public PageResult readEvidencePage(Caller caller, String artifactId, String cursor, int size, ArtifactAuthorizer authorizer) {
        return read(caller, artifactId, cursor, size, authorizer, ReadOrder.EVIDENCE_LINK);
    }

    private PageResult read(Caller caller, String artifactId, String cursor, int size, ArtifactAuthorizer authorizer, ReadOrder readOrder) {
        id(artifactId);
        if (size < 1 || size > 500) throw new IllegalArgumentException("SELECTION_PAGE_SIZE_INVALID");
        boolean selected = readOrder != ReadOrder.EVIDENCE_LINK;
        boolean deltaOrder = readOrder == ReadOrder.SELECTED_DELTA;
        return transaction(() -> {
            Artifact actual = runs.readArtifact(caller, artifactId, authorizer);
            contract(actual, selected ? SELECTED_TYPE : EVIDENCE_TYPE, selected ? SELECTED_SCHEMA : EVIDENCE_SCHEMA);
            String column = selected ? "selected_artifact_id" : "evidence_artifact_id";
            List<String> keys = jdbc.queryForList("SELECT collection_id FROM campaign_decline_collection WHERE " + column + "=?",
                    String.class, artifactId);
            require(keys.size() == 1, "SELECTION_NOT_SEALED");
            Stored peek = required(keys.get(0), false);
            RunToken token = currentStoredToken(caller, peek);
            Stored stored = required(keys.get(0), true); matchOwner(token, stored);
            require(stored.receipt().sealed(), "SELECTION_NOT_SEALED");
            Chain head = verifyAll(token, stored, authorizer);
            FinalPair finalOutputs = finalPair(token, stored, stored.finalChildId(), head, authorizer);
            Artifact finalArtifact = selected ? finalOutputs.selected() : finalOutputs.evidence();
            require(actual.equals(finalArtifact), "SELECTION_FINAL_CHANGED");
            Cursor after = cursor == null ? null : cursor(cursor, stored, finalArtifact, readOrder.cursorOrder);
            String predicate = "collection_id=?" + (selected ? " AND selected=TRUE" : "");
            List<Object> arguments = new ArrayList<>(); arguments.add(stored.receipt().definition().collectionId());
            if (after != null) {
                List<Map<String, Object>> boundary = jdbc.queryForList("SELECT delta_value,selected FROM campaign_decline_row "
                        + "WHERE collection_id=? AND link_id=?", after.collectionId(), after.linkId());
                require(boundary.size() == 1 && ((Number) boundary.get(0).get("delta_value")).longValue() == after.delta()
                        && (!selected || Boolean.TRUE.equals(boundary.get(0).get("selected"))), "SELECTION_CURSOR_INVALID");
                predicate += deltaOrder ? " AND (delta_value>? OR (delta_value=? AND link_id>?))" : " AND link_id>?";
                if (deltaOrder) { arguments.add(after.delta()); arguments.add(after.delta()); }
                arguments.add(after.linkId());
            }
            String order = deltaOrder ? "delta_value,link_id" : "link_id";
            arguments.add(size + 1);
            List<Result> rows = jdbc.query("SELECT result_json FROM campaign_decline_row WHERE " + predicate
                    + " ORDER BY " + order + " LIMIT ?", (rs, index) -> result(rs.getString("result_json")), arguments.toArray());
            boolean more = rows.size() > size;
            if (more) rows = new ArrayList<>(rows.subList(0, size));
            // Authorization may change while validating a large collection; do not return cached permission.
            require(finalArtifact.metadata().equals(runs.inspectArtifact(caller, artifactId, authorizer)), "SELECTION_FINAL_CHANGED");
            String next = null;
            if (more) {
                Result last = rows.get(rows.size() - 1);
                next = cursor(new Cursor(stored.receipt().definition().collectionId(), finalArtifact.metadata().ref().payloadHash(),
                        readOrder.cursorOrder, last.delta().longValueExact(), last.linkId()));
            }
            return new PageResult(rows, next);
        });
    }

    private Pair pair(RunToken token, String childId, ArtifactAuthorizer authorizer) {
        Map<String, ArtifactRef> outputs = runs.localOutputs(token, childId, authorizer);
        require(outputs.keySet().equals(Set.of("comparisonPage", "selectionChain")), "SELECTION_PAGE_OUTPUTS_INVALID");
        ChildRecord child = runs.child(token, childId).orElseThrow(() -> failure("SELECTION_CHILD_MISSING"));
        Artifact page = runs.readArtifact(token.definition().caller(), outputs.get("comparisonPage").artifactId(), authorizer);
        Artifact chain = runs.readArtifact(token.definition().caller(), outputs.get("selectionChain").artifactId(), authorizer);
        contract(page, DeclineSelectionPage.PAGE_TYPE, DeclineSelectionPage.PAGE_SCHEMA);
        contract(chain, DeclineSelectionPage.CHAIN_TYPE, DeclineSelectionPage.CHAIN_SCHEMA);
        Page decoded = DeclineSelectionPage.decode(page.payloadJson());
        Chain decodedChain = DeclineSelectionPage.decodeChain(chain.payloadJson());
        String stepId = step(token, child);
        requireProducer(token, child, page, stepId); requireProducer(token, child, chain, stepId);
        require(decoded.definition().equals(decodedChain.definition())
                && page.metadata().ref().expiresAt().equals(chain.metadata().ref().expiresAt())
                && decoded.definition().scopeRef().equals(page.metadata().ref().scopeRef())
                && decoded.definition().periodsRef().equals(page.metadata().ref().periodsRef())
                && decoded.definition().scopeRef().equals(chain.metadata().ref().scopeRef())
                && decoded.definition().periodsRef().equals(chain.metadata().ref().periodsRef()), "SELECTION_PAGE_BINDING_INVALID");
        Set<String> inputs = new HashSet<>();
        child.spec().localInvocation().inputs().values().forEach(input -> inputs.add(input.ref().artifactId()));
        for (Result row : decoded.rows()) require(row.evidenceRefs().size() == 2
                && new HashSet<>(row.evidenceRefs()).size() == 2 && inputs.containsAll(row.evidenceRefs()),
                "SELECTION_ROW_EVIDENCE_INVALID");
        return new Pair(child, stepId, page, chain, decoded, decodedChain);
    }

    private FinalPair finalPair(RunToken token, Stored stored, String childId, Chain head, ArtifactAuthorizer authorizer) {
        Map<String, ArtifactRef> outputs = runs.localOutputs(token, childId, authorizer);
        require(outputs.keySet().equals(Set.of("selectedEntities", "selectionEvidence")), "SELECTION_FINAL_OUTPUTS_INVALID");
        ChildRecord child = runs.child(token, childId).orElseThrow(() -> failure("SELECTION_CHILD_MISSING"));
        require(stored.stepId().equals(step(token, child)), "SELECTION_STEP_MISMATCH");
        Artifact selected = runs.readArtifact(token.definition().caller(), outputs.get("selectedEntities").artifactId(), authorizer);
        Artifact evidence = runs.readArtifact(token.definition().caller(), outputs.get("selectionEvidence").artifactId(), authorizer);
        contract(selected, SELECTED_TYPE, SELECTED_SCHEMA); contract(evidence, EVIDENCE_TYPE, EVIDENCE_SCHEMA);
        requireProducer(token, child, selected, stored.stepId()); requireProducer(token, child, evidence, stored.stepId());
        require(stored.executorVersion().equals(selected.metadata().executorVersion())
                && stored.executorVersion().equals(evidence.metadata().executorVersion())
                && selected.metadata().ref().expiresAt().equals(evidence.metadata().ref().expiresAt()), "SELECTION_FINAL_BINDING_INVALID");
        Artifact headArtifact = runs.readArtifact(token.definition().caller(), stored.receipt().chainArtifactId(), authorizer);
        requireDeclaredInput(child, headArtifact.metadata());
        requireDeclaredInput(child, scope(token.definition().caller(), stored.receipt().definition(), authorizer).metadata());
        require(!selected.metadata().ref().expiresAt().isAfter(headArtifact.metadata().ref().expiresAt()), "SELECTION_EXPIRY_EXTENDED");
        finalManifest(selected, stored, head, SELECTED_SCHEMA); finalManifest(evidence, stored, head, EVIDENCE_SCHEMA);
        return new FinalPair(selected, evidence);
    }

    private void finalManifest(Artifact artifact, Stored stored, Chain head, String schema) {
        JsonNode value = object(artifact.payloadJson());
        require(fields(value).equals(FINAL_FIELDS), "SELECTION_FINAL_MANIFEST_INVALID");
        Definition definition = stored.receipt().definition();
        require(schema.equals(text(value, "schemaVersion")) && definition.collectionId().equals(text(value, "collectionId"))
                && definition.scopeRef().equals(text(value, "scopeRef")) && definition.periodsRef().equals(text(value, "periodsRef"))
                && stored.receipt().chainArtifactId().equals(text(value, "headArtifactId"))
                && stored.receipt().chainPayloadHash().equals(text(value, "headPayloadHash"))
                && head.chainHash().equals(text(value, "chainHash"))
                && number(value.get("candidateCount"), false) == definition.memberCount()
                && number(value.get("comparedCount"), false) == head.totals().compared()
                && number(value.get("selectedCount"), false) == head.totals().selected()
                && value.get("selectionComplete").isBoolean()
                && value.get("selectionComplete").booleanValue() == DeclineSelectionPage.selectionComplete(head),
                "SELECTION_FINAL_MANIFEST_INVALID");
        String empty = DeclineSelectionPage.emptyReason(head);
        require(empty == null ? value.get("emptyReason").isNull()
                : value.get("emptyReason").isTextual() && empty.equals(value.get("emptyReason").textValue()),
                "SELECTION_FINAL_MANIFEST_INVALID");
        require(definition.scopeRef().equals(artifact.metadata().ref().scopeRef())
                && definition.periodsRef().equals(artifact.metadata().ref().periodsRef()), "SELECTION_FINAL_BINDING_INVALID");
    }

    private Chain verifyAll(RunToken token, Stored stored, ArtifactAuthorizer authorizer) {
        requireCompletePages(stored.receipt());
        return verifyPrefix(token, stored, authorizer);
    }

    private static void requireCompletePages(Receipt receipt) {
        require(receipt.committedPages() == Math.max(1, receipt.definition().shardCount()), "SELECTION_PAGES_INCOMPLETE");
    }

    private Chain verifyPrefix(RunToken token, Stored stored, ArtifactAuthorizer authorizer) {
        return verifyPrefix(token, stored, authorizer, ignored -> {});
    }

    private Chain verifyPrefix(RunToken token, Stored stored, ArtifactAuthorizer authorizer, Consumer<Pair> verifiedPage) {
        Receipt receipt = stored.receipt(); Definition definition = receipt.definition();
        Artifact scope = scope(token.definition().caller(), definition, authorizer);
        require(receipt.committedPages() > 0 && receipt.committedPages() <= Math.max(1, definition.shardCount()),
                "SELECTION_INDEX_CORRUPTED");
        Long pageCount = jdbc.queryForObject("SELECT COUNT(*) FROM campaign_decline_page WHERE collection_id=?", Long.class,
                definition.collectionId());
        require(pageCount != null && pageCount == receipt.committedPages(), "SELECTION_INDEX_CORRUPTED");
        Artifact previous = null; Chain head = null;
        for (int ordinal = 0; ordinal < receipt.committedPages(); ordinal++) {
            SavedPage saved = page(definition.collectionId(), ordinal).orElseThrow(() -> failure("SELECTION_INDEX_CORRUPTED"));
            Pair actual = pair(token, saved.childId(), authorizer);
            matchPair(stored, actual); matchSaved(saved, actual);
            require(actual.page().shardIndex() == ordinal, "SELECTION_INDEX_CORRUPTED");
            requireDeclaredInput(actual.child(), scope.metadata());
            verifyAdvance(actual, previous);
            verifyRows(definition.collectionId(), actual.page());
            verifiedPage.accept(actual);
            previous = actual.chainArtifact(); head = actual.chain();
        }
        require(previous != null && previous.metadata().ref().artifactId().equals(receipt.chainArtifactId())
                && previous.metadata().ref().payloadHash().equals(receipt.chainPayloadHash()), "SELECTION_HEAD_CHANGED");
        Long rowCount = jdbc.queryForObject("SELECT COUNT(*) FROM campaign_decline_row WHERE collection_id=?", Long.class, definition.collectionId());
        require(head != null && rowCount != null && rowCount == head.totals().compared(), "SELECTION_INDEX_CORRUPTED");
        return head;
    }

    /** The immutable LOCAL invocation is the source of dates; manifests only carry opaque period references. */
    private static List<Period> sourcePeriods(Pair page) {
        require(page.child().spec().mode() == ChildMode.LOCAL && page.child().spec().localInvocation() != null,
                "SELECTION_PERIODS_MISSING");
        JsonNode parameters = object(page.child().spec().localInvocation().parametersJson());
        require(object(encode(page.page().definition())).equals(parameters.get("definition"))
                && number(parameters.get("shardIndex"), false) == page.page().shardIndex(), "SELECTION_PERIODS_MISMATCH");
        JsonNode raw = parameters.get("periods");
        require(raw != null && raw.isArray() && raw.size() == 2, "SELECTION_PERIODS_MISSING");
        List<Period> periods = new ArrayList<>(2);
        for (JsonNode value : raw) {
            require(value.isObject() && fields(value).equals(PERIOD_FIELDS), "SELECTION_PERIODS_INVALID");
            try {
                periods.add(new Period(text(value, "periodsRef"), text(value, "startDate"),
                        text(value, "endDate"), text(value, "timeZone")));
            } catch (IllegalArgumentException | java.time.DateTimeException invalid) {
                throw failure("SELECTION_PERIODS_INVALID");
            }
        }
        return List.copyOf(periods);
    }

    private Artifact previous(RunToken token, Stored stored, int ordinal, ArtifactAuthorizer authorizer) {
        if (ordinal == 0) return null;
        require(ordinal <= stored.receipt().committedPages(), "SELECTION_PAGE_NOT_CONTIGUOUS");
        SavedPage saved = page(stored.receipt().definition().collectionId(), ordinal - 1)
                .orElseThrow(() -> failure("SELECTION_PAGE_NOT_CONTIGUOUS"));
        Pair actual = pair(token, saved.childId(), authorizer);
        matchPair(stored, actual); matchSaved(saved, actual);
        require(actual.chain().pageCount() == ordinal, "SELECTION_HEAD_CHANGED");
        return actual.chainArtifact();
    }

    private void verifyAdvance(Pair actual, Artifact previous) {
        require(DeclineSelectionPage.advance(actual.page(), actual.pageArtifact().metadata().ref().artifactId(), previous)
                .equals(actual.chain()), "SELECTION_CHAIN_MISMATCH");
        require(actual.pageArtifact().metadata().ref().payloadHash().equals(actual.chain().pagePayloadHash()), "SELECTION_CHAIN_MISMATCH");
        if (previous != null) {
            requireDeclaredInput(actual.child(), previous.metadata());
            require(!actual.chainArtifact().metadata().ref().expiresAt().isAfter(previous.metadata().ref().expiresAt()), "SELECTION_EXPIRY_EXTENDED");
        }
    }

    private void verifyRows(String collectionId, Page page) {
        List<Indexed> stored = jdbc.query("SELECT link_id,ordinal_index,delta_value,selected,result_json FROM campaign_decline_row "
                        + "WHERE collection_id=? AND ordinal_index=? ORDER BY link_id LIMIT 501", (rs, index) ->
                        new Indexed(rs.getLong("link_id"), rs.getInt("ordinal_index"), rs.getLong("delta_value"),
                                rs.getBoolean("selected"), rs.getString("result_json")), collectionId, page.shardIndex());
        require(stored.size() == page.rows().size(), "SELECTION_INDEX_CORRUPTED");
        Map<Long, Result> expected = new HashMap<>(); page.rows().forEach(row -> expected.put(row.linkId(), row));
        Set<Long> selected = selectedIds(page);
        for (Indexed indexed : stored) {
            Result row = expected.remove(indexed.id());
            require(row != null && indexed.ordinal() == page.shardIndex() && indexed.delta() == row.delta().longValueExact()
                    && indexed.selected() == selected.contains(row.linkId())
                    && object(indexed.json()).equals(object(encode(row))), "SELECTION_INDEX_CORRUPTED");
        }
        require(expected.isEmpty(), "SELECTION_INDEX_CORRUPTED");
    }

    private Artifact scope(Caller caller, Definition definition, ArtifactAuthorizer authorizer) {
        Artifact actual = runs.readArtifact(caller, definition.scopeArtifactId(), authorizer);
        contract(actual, "ScopeArtifact", "campaign-scope/v1");
        JsonNode body = object(actual.payloadJson());
        require("campaign-scope/v1".equals(text(body, "schemaVersion"))
                && definition.scopeRef().equals(actual.metadata().ref().scopeRef())
                && definition.scopeRef().equals(text(body, "scopeRef"))
                && definition.memberCount() == number(body.get("memberCount"), false)
                && definition.shardCount() == number(body.get("shardCount"), false)
                && number(body.get("pageCount"), false) == Math.max(1, definition.shardCount()), "SELECTION_SCOPE_MISMATCH");
        return actual;
    }

    private static void requireDeclaredInput(ChildRecord child, ArtifactMetadata expected) {
        require(child.spec().localInvocation().inputs().values().stream().anyMatch(expected::equals), "SELECTION_INPUT_BINDING_MISMATCH");
    }

    private void requireProducer(RunToken token, ChildRecord child, Artifact actual, String stepId) {
        var metadata = actual.metadata(); var definition = token.definition();
        require(definition.runId().equals(metadata.runId()) && definition.planId().equals(metadata.planId())
                && definition.revision() == metadata.revision() && child.spec().childId().equals(metadata.childId())
                && child.spec().actionId().equals(metadata.actionId()) && stepId.equals(step(token, child)), "SELECTION_PRODUCER_MISMATCH");
    }

    private String step(RunToken token, ChildRecord child) {
        List<String> found = jdbc.queryForList("SELECT step_id FROM campaign_action_ledger WHERE run_id=? AND revision=? AND action_id=?",
                String.class, token.definition().runId(), token.definition().revision(), child.spec().actionId());
        require(found.size() == 1, "SELECTION_ACTION_MISSING"); return found.get(0);
    }

    private static void matchPair(Stored stored, Pair pair) {
        require(stored.receipt().definition().equals(pair.page().definition()) && stored.stepId().equals(pair.stepId())
                && stored.executorVersion().equals(pair.pageArtifact().metadata().executorVersion())
                && stored.executorVersion().equals(pair.chainArtifact().metadata().executorVersion()), "SELECTION_DEFINITION_CHANGED");
    }

    private static void matchSaved(SavedPage saved, Pair actual) {
        require(saved.childId().equals(actual.child().spec().childId())
                && saved.ordinal() == actual.page().shardIndex()
                && saved.pageId().equals(actual.pageArtifact().metadata().ref().artifactId())
                && saved.pageHash().equals(actual.pageArtifact().metadata().ref().payloadHash())
                && saved.chainId().equals(actual.chainArtifact().metadata().ref().artifactId())
                && saved.chainHash().equals(actual.chainArtifact().metadata().ref().payloadHash()), "SELECTION_PAGE_CONFLICT");
    }

    private Optional<SavedPage> page(String collectionId, int ordinal) {
        return jdbc.query("SELECT ordinal_index,child_id,page_artifact_id,page_payload_hash,chain_artifact_id,chain_payload_hash "
                        + "FROM campaign_decline_page WHERE collection_id=? AND ordinal_index=? FOR UPDATE", (rs, index) ->
                        new SavedPage(rs.getInt("ordinal_index"), rs.getString("child_id"), rs.getString("page_artifact_id"),
                                rs.getString("page_payload_hash"), rs.getString("chain_artifact_id"), rs.getString("chain_payload_hash")),
                collectionId, ordinal).stream().findFirst();
    }

    private Optional<Stored> find(String collectionId, boolean lock) {
        return jdbc.query("SELECT * FROM campaign_decline_collection WHERE collection_id=?" + (lock ? " FOR UPDATE" : ""),
                (rs, index) -> stored(rs), collectionId).stream().findFirst();
    }
    private Stored required(String collectionId, boolean lock) { return find(collectionId, lock).orElseThrow(() -> failure("SELECTION_NOT_FOUND")); }

    private Stored stored(ResultSet rs) throws SQLException {
        String encoded = rs.getString("definition_json");
        require(CampaignRunStore.sha256(encoded).equals(rs.getString("definition_hash")), "SELECTION_DEFINITION_CORRUPTED");
        Definition definition;
        try { definition = JSON.readValue(encoded, Definition.class); }
        catch (JsonProcessingException malformed) { throw failure("SELECTION_DEFINITION_CORRUPTED"); }
        validateDefinition(definition);
        int count = rs.getInt("committed_pages");
        require(definition.collectionId().equals(rs.getString("collection_id"))
                && definition.scopeArtifactId().equals(rs.getString("scope_artifact_id"))
                && count >= 0 && count <= Math.max(1, definition.shardCount()), "SELECTION_DEFINITION_CORRUPTED");
        Receipt receipt = new Receipt(definition, count, rs.getString("chain_artifact_id"), rs.getString("chain_payload_hash"),
                rs.getBoolean("sealed"), rs.getString("selected_artifact_id"), rs.getString("evidence_artifact_id"));
        return new Stored(receipt, rs.getString("run_id"), rs.getInt("revision"), rs.getString("step_id"),
                rs.getString("executor_version"), rs.getString("final_child_id"));
    }

    private void requireRun(RunToken token, boolean active) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM campaign_run_ledger "
                + "WHERE run_id=? AND revision=? FOR UPDATE", token.definition().runId(), token.definition().revision());
        require(rows.size() == 1, "RUN_NOT_FOUND");
        var row = rows.get(0);
        var definition = token.definition(); var caller = definition.caller();
        if (!caller.tenantId().equals(row.get("tenant_id")) || !caller.subject().equals(row.get("subject_name"))
                || caller.authVersion() != ((Number) row.get("auth_version")).longValue())
            throw new SecurityException("LEDGER_SUBJECT_MISMATCH");
        require(definition.sessionId().equals(row.get("session_id")) && definition.planId().equals(row.get("plan_id"))
                && definition.definitionJson().equals(row.get("definition_json"))
                && definition.definitionHash().equals(row.get("definition_hash")), "RUN_DEFINITION_CHANGED");
        require(((Number) row.get("row_version")).longValue() == token.version()
                && token.advanceToken().equals(row.get("advance_token")), "RUN_TOKEN_FENCED");
        if (active) require("ACTIVE".equals(row.get("run_status")), "RUN_NOT_ACTIVE");
    }

    private RunToken currentStoredToken(Caller caller, Stored stored) {
        List<RunToken> rows = jdbc.query("SELECT * FROM campaign_run_ledger WHERE run_id=? AND revision=? FOR UPDATE", (rs, index) -> {
            Caller owner = new Caller(rs.getString("tenant_id"), rs.getString("subject_name"), rs.getLong("auth_version"));
            if (!caller.equals(owner)) throw new SecurityException("LEDGER_SUBJECT_MISMATCH");
            return new RunToken(new RunDefinition(owner, rs.getString("session_id"), rs.getString("run_id"),
                    rs.getString("plan_id"), rs.getInt("revision"), rs.getString("definition_json")),
                    rs.getLong("row_version"), rs.getString("advance_token"));
        }, stored.runId(), stored.revision());
        require(rows.size() == 1, "RUN_NOT_FOUND");
        requireRun(rows.get(0), false); return rows.get(0);
    }

    private static void matchOwner(RunToken token, Stored stored) {
        require(token.definition().runId().equals(stored.runId()) && token.definition().revision() == stored.revision(), "SELECTION_RUN_MISMATCH");
    }
    private static void contract(Artifact artifact, String type, String schema) {
        require(type.equals(artifact.metadata().ref().type()) && schema.equals(artifact.metadata().ref().schemaVersion()), "SELECTION_ARTIFACT_CONTRACT_MISMATCH");
    }
    private static void validateDefinition(Definition definition) {
        Objects.requireNonNull(definition); id(definition.collectionId()); id(definition.scopeArtifactId());
        require(definition.scopeRef() != null && !definition.scopeRef().isBlank() && definition.scopeRef().length() <= 256
                && definition.periodsRef() != null && !definition.periodsRef().isBlank() && definition.periodsRef().length() <= 256,
                "SELECTION_DEFINITION_INVALID");
    }
    private static Set<Long> selectedIds(Page page) {
        Set<Long> ids = new HashSet<>(); DeclineSelectionPage.selected(page).forEach(row -> ids.add(row.linkId())); return ids;
    }
    private static String encode(Object value) {
        try { return JSON.writeValueAsString(value); }
        catch (JsonProcessingException invalid) { throw failure("SELECTION_JSON_INVALID"); }
    }
    private static Result result(String value) {
        try { return JSON.readValue(value, Result.class); }
        catch (JsonProcessingException invalid) { throw failure("SELECTION_INDEX_CORRUPTED"); }
    }
    private static JsonNode object(String value) {
        try { JsonNode result = JSON.readTree(value); require(result != null && result.isObject(), "SELECTION_JSON_INVALID"); return result; }
        catch (JsonProcessingException invalid) { throw failure("SELECTION_JSON_INVALID"); }
    }
    private static Set<String> fields(JsonNode node) { Set<String> result = new HashSet<>(); node.fieldNames().forEachRemaining(result::add); return result; }
    private static String text(JsonNode node, String name) {
        require(node.path(name).isTextual() && !node.path(name).textValue().isBlank(), "SELECTION_JSON_INVALID"); return node.path(name).textValue();
    }
    private static long number(JsonNode node, boolean signed) {
        require(node != null && node.isIntegralNumber() && node.canConvertToLong() && (signed || node.longValue() >= 0), "SELECTION_NUMBER_INVALID");
        return node.longValue();
    }
    private static Cursor cursor(String supplied, Stored stored, Artifact artifact, String expectedOrder) {
        try {
            require(supplied.matches("[A-Za-z0-9_-]{1,2048}"), "SELECTION_CURSOR_INVALID");
            JsonNode value = object(new String(Base64.getUrlDecoder().decode(supplied), StandardCharsets.UTF_8));
            require(fields(value).equals(CURSOR_FIELDS), "SELECTION_CURSOR_INVALID");
            Cursor decoded = new Cursor(text(value, "collectionId"), text(value, "artifactHash"), text(value, "order"),
                    number(value.get("delta"), true), number(value.get("linkId"), false));
            require(decoded.collectionId().equals(stored.receipt().definition().collectionId())
                    && decoded.artifactHash().equals(artifact.metadata().ref().payloadHash())
                    && decoded.order().equals(expectedOrder) && decoded.linkId() > 0,
                    "SELECTION_CURSOR_INVALID");
            return decoded;
        } catch (IllegalArgumentException invalid) { throw failure("SELECTION_CURSOR_INVALID"); }
    }
    private static String cursor(Cursor value) { return Base64.getUrlEncoder().withoutPadding().encodeToString(encode(value).getBytes(StandardCharsets.UTF_8)); }
    private static void id(String value) { if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,95}")) throw new IllegalArgumentException("SELECTION_REFERENCE_INVALID"); }
    private static void require(boolean condition, String code) { if (!condition) throw failure(code); }
    private static IllegalStateException failure(String code) { return new IllegalStateException(code); }
    private <T> T transaction(Supplier<T> work) {
        try { return transactions.execute(status -> work.get()); }
        catch (DataIntegrityViolationException invalid) { throw failure("SELECTION_IDENTITY_CONFLICT"); }
    }
    private record Stored(Receipt receipt, String runId, int revision, String stepId, String executorVersion, String finalChildId) {}
    private record Pair(ChildRecord child, String stepId, Artifact pageArtifact, Artifact chainArtifact, Page page, Chain chain) {}
    private record SavedPage(int ordinal, String childId, String pageId, String pageHash, String chainId, String chainHash) {}
    private record Indexed(long id, int ordinal, long delta, boolean selected, String json) {}
    private record FinalPair(Artifact selected, Artifact evidence) {}
    private record Cursor(String collectionId, String artifactHash, String order, long delta, long linkId) {}
    private enum ReadOrder {
        SELECTED_DELTA("DELTA_LINK"), EVIDENCE_LINK("LINK"), SELECTED_LINK("SELECTED_LINK");
        private final String cursorOrder;
        ReadOrder(String cursorOrder) { this.cursorOrder = cursorOrder; }
    }
}
