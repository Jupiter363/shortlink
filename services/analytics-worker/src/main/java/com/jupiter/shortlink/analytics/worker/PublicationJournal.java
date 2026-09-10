package com.jupiter.shortlink.analytics.worker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Component
public class PublicationJournal {
    private static final Logger LOG = LoggerFactory.getLogger(PublicationJournal.class);
    private final ControlLedger ledger;
    private final ObjectArchive archive;

    public PublicationJournal(ControlLedger l, ObjectArchive a) {
        ledger = l;
        archive = a;
    }

    @Scheduled(fixedDelayString = "${analytics.publication.poll-delay:1000}")
    public void flush() {
        try {
            for (var p : ledger.pendingPublications()) {
                String key =
                        "catalog/publications/"
                                + p.get("recovery_epoch")
                                + "/"
                                + p.get("window_start")
                                + "/"
                                + p.get("manifest_revision")
                                + ".json";
                archive.put(
                        key, p.get("manifest_payload").toString().getBytes(StandardCharsets.UTF_8));
                ledger.journaled(p.get("intent_id").toString());
            }
        } catch (Exception e) {
            LOG.warn("Publication journal remains pending: {}", e.getClass().getSimpleName());
        }
    }
}
