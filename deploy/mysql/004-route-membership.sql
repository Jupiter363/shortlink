-- Explicit rollout only. Apply to the primary business database before deploying registered writers.
-- Initial OFF does not permit negative Bloom answers. Do not update control mode with ad-hoc SQL.
CREATE TABLE IF NOT EXISTS t_route_membership_control (
 namespace VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
 generation VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 revision BIGINT NOT NULL DEFAULT 0,
 member_count BIGINT NOT NULL DEFAULT 0,
 mode VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'OFF',
 baseline_ready BOOLEAN NOT NULL DEFAULT FALSE,
 transition_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
 CHECK (revision >= 0 AND member_count >= 0),
 CHECK (mode IN ('OFF','SHADOW','ENFORCE','DRAINING'))
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS t_route_membership (
 namespace VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 generation VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 member_ordinal BIGINT NOT NULL,
 registration_revision BIGINT NOT NULL,
 domain_norm VARCHAR(253) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 short_uri VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 PRIMARY KEY (namespace,generation,domain_norm,short_uri),
 UNIQUE KEY uk_membership_ordinal (namespace,generation,member_ordinal),
 CHECK (member_ordinal > 0 AND registration_revision > 0)
) ENGINE=InnoDB;

INSERT INTO t_route_membership_control
 (namespace,generation,revision,member_count,mode,baseline_ready,transition_id)
VALUES ('routes',UUID(),0,0,'OFF',FALSE,UUID())
ON DUPLICATE KEY UPDATE namespace=namespace;

-- Entries are append-only, including registrations whose business transactions roll back.
-- member_ordinal is allocated explicitly under the control lock. It is not an AUTO_INCREMENT
-- high-water mark, a Leaf allocation boundary, or evidence derived from MAX(link_id).
