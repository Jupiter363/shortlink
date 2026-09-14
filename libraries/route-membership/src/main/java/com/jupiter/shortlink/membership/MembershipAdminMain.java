package com.jupiter.shortlink.membership;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Explicit local administration. Credentials are accepted from the environment and never printed. */
public final class MembershipAdminMain {
    private MembershipAdminMain() { }

    public static void main(String[] args) {
        try {
            run(args, System.getenv());
        } catch (Exception failure) {
            // JDBC exceptions may embed credentials or connection URLs. Do not print their text.
            System.err.println("Membership operation failed (" + failure.getClass().getSimpleName()
                    + "). Check the catalog and database access; interrupted transitions remain DRAINING.");
            System.exit(1);
        }
    }

    static void run(String[] args, Map<String, String> environment) throws Exception {
        Arguments parsed = Arguments.parse(args);
        DriverManagerDataSource source = new DriverManagerDataSource();
        String url = required(environment, "MEMBERSHIP_DB_URL");
        if (!url.startsWith("jdbc:mysql:")) throw new IllegalArgumentException("A MySQL primary URL is required");
        source.setUrl(url);
        source.setUsername(required(environment, "MEMBERSHIP_DB_USER"));
        source.setPassword(required(environment, "MEMBERSHIP_DB_PASSWORD"));
        Properties connectionProperties = new Properties();
        connectionProperties.setProperty("connectTimeout", "5000");
        connectionProperties.setProperty("socketTimeout", "5000");
        source.setConnectionProperties(connectionProperties);
        try (Connection connection = source.getConnection()) {
            if (!parsed.catalog.equals(connection.getCatalog()))
                throw new IllegalArgumentException("Explicit catalog confirmation does not match the connection");
            if (connection.isReadOnly()) throw new IllegalStateException("Membership requires the writable primary");
            try (var statement = connection.createStatement();
                 var result = statement.executeQuery("SELECT @@global.read_only,@@global.super_read_only")) {
                if (!result.next() || result.getBoolean(1) || result.getBoolean(2))
                    throw new IllegalStateException("Membership administration requires the writable primary");
            }
        }
        JdbcTemplate jdbc = new JdbcTemplate(source);
        jdbc.setQueryTimeout(5);
        JdbcRouteMembershipStore store = new JdbcRouteMembershipStore(jdbc, new DataSourceTransactionManager(source));
        if (parsed.action.equals("status")) {
            print(store.readControl());
            return;
        }
        if (Set.of("init", "enforce").contains(parsed.action) && !parsed.writersUpgraded)
            throw new IllegalArgumentException("All route writers must use the membership protocol");
        DrainPermit drain = store.beginDrain();
        waitForDrain(store, drain);
        ControlSnapshot result;
        if (parsed.action.equals("init")) {
            RouteAddress cursor = null;
            while (true) {
                List<RouteAddress> page = routePage(jdbc, cursor);
                if (page.isEmpty()) break;
                store.bootstrapPage(drain, page);
                cursor = page.get(page.size() - 1);
            }
            result = store.completeBaseline(drain);
        } else {
            result = store.finishDrain(drain, Mode.valueOf(parsed.action.toUpperCase(java.util.Locale.ROOT)));
        }
        print(result);
    }

    private static List<RouteAddress> routePage(JdbcTemplate jdbc, RouteAddress cursor) {
        String select = "SELECT domain_norm,short_uri FROM t_link_route";
        if (cursor == null) {
            return jdbc.query(select + " ORDER BY domain_norm,short_uri LIMIT 500",
                    (rs, row) -> new RouteAddress(rs.getString(1), rs.getString(2)));
        }
        return jdbc.query(select + " WHERE domain_norm>? OR (domain_norm=? AND short_uri>?)"
                        + " ORDER BY domain_norm,short_uri LIMIT 500",
                (rs, row) -> new RouteAddress(rs.getString(1), rs.getString(2)),
                cursor.domainNorm(), cursor.domainNorm(), cursor.shortUri());
    }

    private static void waitForDrain(JdbcRouteMembershipStore store, DrainPermit drain) throws InterruptedException {
        long started = System.nanoTime();
        long remaining;
        while ((remaining = store.remainingDrainNanos(drain)) > 0) {
            if (System.nanoTime() - started > TimeUnit.SECONDS.toNanos(10))
                throw new IllegalStateException("Membership drain timed out");
            TimeUnit.NANOSECONDS.sleep(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(100)));
        }
    }

    private static void print(ControlSnapshot snapshot) {
        System.out.println("namespace=" + snapshot.namespace() + " generation=" + snapshot.generation()
                + " revision=" + snapshot.revision() + " members=" + snapshot.memberCount()
                + " mode=" + snapshot.mode() + " baselineReady=" + snapshot.baselineReady());
    }

    private static String required(Map<String, String> environment, String name) {
        String value = environment.get(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing " + name);
        return value;
    }

    record Arguments(String action, String catalog, boolean writersUpgraded) {
        static Arguments parse(String[] args) {
            if (args.length < 3 || !Set.of("status", "init", "shadow", "enforce", "off").contains(args[0]))
                throw new IllegalArgumentException("Expected action and --confirm-catalog <catalog>");
            String catalog = null;
            boolean writers = false;
            for (int i = 1; i < args.length; i++) {
                if (args[i].equals("--confirm-catalog") && catalog == null && i + 1 < args.length)
                    catalog = args[++i];
                else if (args[i].equals("--confirm-writers-upgraded") && !writers) writers = true;
                else throw new IllegalArgumentException("Unknown or duplicate membership administration argument");
            }
            if (catalog == null || !catalog.matches("[A-Za-z0-9_]{1,64}"))
                throw new IllegalArgumentException("A concrete catalog confirmation is required");
            return new Arguments(args[0], catalog, writers);
        }
    }
}
