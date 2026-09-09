package com.jupiter.shortlink.command.group;

import com.jupiter.shortlink.command.security.CommandAuthorization;
import com.jupiter.shortlink.command.security.CommandPrincipal;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.util.*;

@Service
public class GroupCommandService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final CommandAuthorization auth;
    private final Clock clock;

    public GroupCommandService(
            JdbcTemplate jdbc,
            PlatformTransactionManager manager,
            CommandAuthorization auth,
            Clock clock) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(manager);
        this.auth = auth;
        this.clock = clock;
    }

    public record Group(
            String gid, String name, int sortOrder, long linkCount, long jobRefs, long revision) {}

    private Group row(java.sql.ResultSet r, int n) throws java.sql.SQLException {
        return new Group(
                r.getString("gid"),
                r.getString("name"),
                r.getInt("sort_order"),
                r.getLong("link_count"),
                r.getLong("job_refs"),
                r.getLong("revision"));
    }

    public List<Group> list(CommandPrincipal p) {
        auth.check(p, false);
        return jdbc.query(
                "SELECT * FROM t_group WHERE username=? AND tenant_id=? AND del_flag=0 ORDER BY"
                        + " sort_order DESC,id",
                this::row,
                p.username(),
                p.tenantId());
    }

    public Group lockActive(CommandPrincipal p, String gid) {
        if (gid == null || gid.isBlank() || gid.length() > 64)
            throw new IllegalArgumentException("Invalid group");
        var rows =
                jdbc.query(
                        "SELECT * FROM t_group WHERE username=? AND tenant_id=? AND gid=? AND"
                                + " del_flag=0 FOR UPDATE",
                        this::row,
                        p.username(),
                        p.tenantId(),
                        gid);
        if (rows.size() != 1)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Group unavailable");
        return rows.get(0);
    }

    public void adjustReferences(CommandPrincipal p, String gid, long links, long jobs) {
        int n =
                jdbc.update(
                        "UPDATE t_group SET"
                            + " link_count=link_count+?,job_refs=job_refs+?,revision=revision+1,update_time=CURRENT_TIMESTAMP"
                            + " WHERE username=? AND tenant_id=? AND gid=? AND del_flag=0 AND"
                            + " link_count+?>=0 AND job_refs+?>=0",
                        links,
                        jobs,
                        p.username(),
                        p.tenantId(),
                        gid,
                        links,
                        jobs);
        if (n != 1) throw new IllegalStateException("Group reference invariant violated");
    }

    public Group create(CommandPrincipal p, String name) {
        return tx.execute(
                s -> {
                    auth.check(p, true);
                    return insert(p.tenantId(), p.username(), name);
                });
    }

    private Group insert(long tenantId, String username, String name) {
        if (name == null || name.isBlank() || name.length() > 100)
            throw new IllegalArgumentException("Invalid group name");
        Long count =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM t_group WHERE username=? AND tenant_id=? AND"
                                + " del_flag=0",
                        Long.class,
                        username,
                        tenantId);
        if (count != null && count >= 20)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Group quota exceeded");
        String gid = UUID.randomUUID().toString().replace("-", "");
        jdbc.update(
                "INSERT INTO t_group(tenant_id,username,gid,name) VALUES (?,?,?,?)",
                tenantId,
                username,
                gid,
                name);
        return new Group(gid, name, 0, 0, 0, 1);
    }

    public String defaultGroup(long tenantId, String username, String commandId, String name) {
        if (!("account-default-group:" + tenantId).equals(commandId))
            throw new IllegalArgumentException("Invalid initialization identity");
        return tx.execute(
                s -> {
                    auth.checkAccount(tenantId, username, true);
                    var prior =
                            jdbc.queryForList(
                                    "SELECT gid FROM t_default_group_result WHERE tenant_id=?",
                                    String.class,
                                    tenantId);
                    if (!prior.isEmpty()) return prior.get(0);
                    Group created = insert(tenantId, username, name);
                    jdbc.update(
                            "INSERT INTO"
                                    + " t_default_group_result(tenant_id,command_id,gid,created_at)"
                                    + " VALUES (?,?,?,?)",
                            tenantId,
                            commandId,
                            created.gid(),
                            clock.millis());
                    return created.gid();
                });
    }

    public void rename(CommandPrincipal p, String gid, String name) {
        tx.executeWithoutResult(
                s -> {
                    auth.check(p, true);
                    lockActive(p, gid);
                    if (name == null || name.isBlank() || name.length() > 100)
                        throw new IllegalArgumentException("Invalid group name");
                    jdbc.update(
                            "UPDATE t_group SET name=?,revision=revision+1 WHERE username=? AND"
                                    + " tenant_id=? AND gid=?",
                            name,
                            p.username(),
                            p.tenantId(),
                            gid);
                });
    }

    public void delete(CommandPrincipal p, String gid) {
        tx.executeWithoutResult(
                s -> {
                    auth.check(p, true);
                    Group g = lockActive(p, gid);
                    if (g.linkCount() != 0 || g.jobRefs() != 0)
                        throw new ResponseStatusException(
                                HttpStatus.CONFLICT, "Group contains links or active jobs");
                    jdbc.update(
                            "UPDATE t_group SET del_flag=1,revision=revision+1 WHERE username=? AND"
                                    + " tenant_id=? AND gid=?",
                            p.username(),
                            p.tenantId(),
                            gid);
                });
    }

    public void sort(CommandPrincipal p, Map<String, Integer> ordering) {
        if (ordering == null || ordering.size() > 20)
            throw new IllegalArgumentException("Invalid ordering");
        tx.executeWithoutResult(
                s -> {
                    auth.check(p, true);
                    for (String gid : new TreeSet<>(ordering.keySet())) {
                        lockActive(p, gid);
                        jdbc.update(
                                "UPDATE t_group SET sort_order=?,revision=revision+1 WHERE"
                                        + " username=? AND tenant_id=? AND gid=?",
                                ordering.get(gid),
                                p.username(),
                                p.tenantId(),
                                gid);
                    }
                });
    }
}
