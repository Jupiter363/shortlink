package com.jupiter.shortlink.agent;

import static org.junit.jupiter.api.Assertions.*;

import com.alibaba.cloud.ai.graph.checkpoint.savers.mysql.MysqlSaver;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.JdbcCampaignRunStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.CampaignStatisticsFixedRuntime;
import com.jupiter.shortlink.agent.harness.security.AgentPrincipal;
import com.jupiter.shortlink.agent.infrastructure.config.CampaignPublicWorkScheduler;
import com.jupiter.shortlink.contract.GroupMembersPage;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.jar.*;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

/** Full Spring processes, sequential single writer, dedicated real MySQL and local HTTP peers only. */
@Timeout(180)
@EnabledIfEnvironmentVariable(named="ISSUE201_SPRING_MYSQL_URL", matches=".+")
class CampaignSpringProcessFailoverIntegrationTest {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final Caller OWNER=new Caller("1001","fixture-analyst",7);
    private static final AgentPrincipal PRINCIPAL=new AgentPrincipal("1001","fixture-analyst",7,false);
    private static final String SESSION="spring-failover-session", KEY="spring-failover-ranking";
    private static final String START="2026-09-01", END="2026-09-02", JOB="spring-fixture-job-1";
    private static final String TOKEN="isolated-spring-fixture-token-only-201";
    private static final String JOBS="/internal/short-link-admin/v1/agent-tools/statistics/jobs";

    @Test
    void sequentialFullSpringProcessesRecoverTheOriginalJobAfterOwnerExitWithoutResubmission() throws Exception {
        var fixture=fixture("failover");
        String url=fixture.url();Path evidence=fixture.evidence(),classpath=fixture.classpath();
        var jdbc=fixture.jdbc();var runs=fixture.runs();
        String domain="same-host-spring-fixture-"+UUID.randomUUID();
        Process first=null,second=null;
        try (var peers=new Peers()) {
            first=startProcess(classpath,url,domain,evidence,"a",peers.port());
            long startupDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(65);
            while (!peers.firstStatus.await(100,TimeUnit.MILLISECONDS)) {
                assertTrue(first.isAlive(),()->"A exited before the held status call; inspect "+evidence.resolve("a.log"));
                assertTrue(System.nanoTime()<startupDeadline,()->"A did not reach the held status call; inspect "+evidence.resolve("a.log"));
            }
            assertTrue(first.isAlive());
            Map<String,Object> reference=JSON.readValue(Files.readString(evidence.resolve("reference.json")),new TypeReference<>(){});
            String runId=(String)reference.get("runId");
            var original=runs.loadRun(OWNER,runId).orElseThrow();
            var before=runs.children(original.token());
            assertEquals(1,before.size());
            var child=before.get(0);
            assertEquals(ChildMode.ASYNC,child.spec().mode());
            assertEquals(ChildState.DISPATCHING,child.state());
            assertEquals(DispatchPurpose.RECONCILE,child.purpose(),"The held read is reconciliation of the acknowledged job, not a fresh submit");
            assertTrue(child.callbackActive(),"The real status HTTP request must own a durable callback at interruption");
            assertEquals(JOB,child.jobId());
            assertEquals(1,peers.submissions.get());
            var stale=new DispatchPermit(original.token(),child.spec().childId(),child.attemptId(),child.attemptVersion(),child.purpose());
            var proof=new LinkedHashMap<String,Object>();
            proof.put("runId",runId);proof.put("requestId",child.spec().requestId());proof.put("wireHash",child.spec().wire().hash());
            proof.put("jobId",child.jobId());proof.put("aPid",first.pid());proof.put("originalVersion",original.token().version());
            proof.put("frozenDefinitionHash",jdbc.queryForObject("SELECT definition_hash FROM campaign_run_ledger WHERE run_id=?",String.class,runId));
            JSON.writeValue(evidence.resolve("before-crash.json").toFile(),proof);
            // EOF targets only the process created above; halt deliberately bypasses callback finally.
            first.getOutputStream().close();
            assertTrue(first.waitFor(10,TimeUnit.SECONDS));
            assertEquals(0,first.exitValue());
            assertTrue(runs.children(original.token()).get(0).callbackActive());
            peers.releaseFirst.countDown();
            assertFalse(first.isAlive(),"B must not be started while A can still write the shared fixture");
            second=startProcess(classpath,url,domain,evidence,"b",peers.port());
            assertTrue(second.waitFor(75,TimeUnit.SECONDS),()->"B did not finish; inspect "+evidence.resolve("b.log"));
            assertEquals(0,second.exitValue(),()->"B failed; inspect "+evidence.resolve("b.log"));
            var current=runs.loadRun(OWNER,runId).orElseThrow();
            var ready=runs.children(current.token()).get(0);
            assertEquals(original.definition(),current.definition());
            assertTrue(current.token().version()>original.token().version());
            assertNotEquals(original.token().advanceToken(),current.token().advanceToken());
            assertEquals(child.spec(),ready.spec());assertEquals(JOB,ready.jobId());
            assertEquals(ChildState.READY,ready.state());assertFalse(ready.callbackActive());
            assertFalse(runs.mayDispatch(stale));
            assertThrows(IllegalStateException.class,()->runs.callbackExited(stale));
            assertEquals(1,peers.submissions.get());assertEquals(0,peers.recoveries.get());
            assertEquals(2,peers.statusReads.get());assertEquals(1,peers.pages.get());assertEquals(0,peers.models.get());
            assertNull(peers.failure.get(),()->Objects.toString(peers.failure.get()));
            assertEquals("SUCCEEDED",jdbc.queryForObject("SELECT step_status FROM campaign_step_ledger WHERE run_id=?",String.class,runId));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM campaign_statistics_receipt WHERE run_id=? AND published=TRUE",Integer.class,runId));
            var audits=jdbc.queryForList("SELECT callback_kind,proof_code,owner_instance_id,recovery_instance_id FROM campaign_callback_recovery WHERE run_id=?",runId);
            assertFalse(audits.isEmpty());
            assertTrue(audits.stream().allMatch(row->Set.of("PROCESS_ABSENT","PROCESS_EXITED").contains(row.get("proof_code"))));
            proof.put("bPid",second.pid());proof.put("recoveredVersion",current.token().version());proof.put("recoveryAudit",audits);
            proof.put("submissions",peers.submissions.get());proof.put("statusReads",peers.statusReads.get());
            proof.put("pageReads",peers.pages.get());proof.put("modelCalls",peers.models.get());
            proof.put("artifactId",ready.artifactId());
            proof.put("artifactPayloadHash",jdbc.queryForObject("SELECT payload_hash FROM campaign_artifact WHERE artifact_id=?",String.class,ready.artifactId()));
            proof.put("assertions","same frozen definition/request/wire/job; real process death; new token; old callback fenced; one publication");
            JSON.writeValue(evidence.resolve("result.json").toFile(),proof);
        } finally {
            stopOwned(first);stopOwned(second);
        }
    }

    @Test
    void inFlightStatusResponseCannotPublishAfterCurrentAuthorityRevocation() throws Exception {
        var fixture=fixture("revocation");
        var runs=fixture.runs();var jdbc=fixture.jdbc();Path evidence=fixture.evidence();
        Process process=null;
        try (var peers=new Peers()) {
            process=startProcess(fixture.classpath(),fixture.url(),"same-host-revocation-"+UUID.randomUUID(),evidence,"revoke",peers.port());
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(65);
            while (!peers.firstStatus.await(100,TimeUnit.MILLISECONDS)) {
                assertTrue(process.isAlive(),()->"Spring exited; inspect "+evidence.resolve("revoke.log"));
                assertTrue(System.nanoTime()<deadline,"Spring did not reach the held original-job status request");
            }
            var reference=JSON.readValue(Files.readString(evidence.resolve("reference.json")),new TypeReference<Map<String,String>>(){});
            String runId=reference.get("runId");var original=runs.loadRun(OWNER,runId).orElseThrow();
            var child=runs.children(original.token()).get(0);
            assertEquals(DispatchPurpose.RECONCILE,child.purpose());assertTrue(child.callbackActive());assertEquals(JOB,child.jobId());
            var before=publicationCounts(jdbc,runId);assertTrue(before.values().stream().allMatch(value->value==0));
            peers.authorized.set(false);
            peers.releaseFirst.countDown();
            deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
            while (runs.children(runs.loadRun(OWNER,runId).orElseThrow().token()).get(0).callbackActive()) {
                assertTrue(process.isAlive());assertTrue(System.nanoTime()<deadline,"Revoked callback did not actually exit");
                Thread.sleep(25);
            }
            // The live application itself must reject both current-principal resolution and another advance.
            process.getOutputStream().write('v');process.getOutputStream().flush();
            assertTrue(process.waitFor(15,TimeUnit.SECONDS));assertEquals(0,process.exitValue(),()->"Inspect "+evidence.resolve("revoke.log"));
            var current=runs.loadRun(OWNER,runId).orElseThrow();var denied=runs.children(current.token()).get(0);
            assertEquals(original.definition(),current.definition());assertEquals(child.spec(),denied.spec());assertEquals(JOB,denied.jobId());
            assertFalse(denied.callbackActive());assertNotEquals(ChildState.READY,denied.state());
            assertEquals(before,publicationCounts(jdbc,runId));
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM campaign_statistics_receipt WHERE run_id=? AND published=TRUE",Integer.class,runId));
            assertEquals(1,peers.submissions.get());assertEquals(1,peers.statusReads.get());assertEquals(0,peers.pages.get());
            assertEquals(0,peers.recoveries.get());assertEquals(0,peers.releases.get());assertEquals(0,peers.models.get());
            assertTrue(peers.denials.get()>0);assertNull(peers.failure.get(),()->Objects.toString(peers.failure.get()));
            var proof=new LinkedHashMap<String,Object>();
            proof.put("runId",runId);proof.put("requestId",child.spec().requestId());proof.put("wireHash",child.spec().wire().hash());
            proof.put("jobId",JOB);proof.put("submissions",peers.submissions.get());proof.put("statusReads",peers.statusReads.get());
            proof.put("pageReads",peers.pages.get());proof.put("releaseCalls",peers.releases.get());proof.put("authority403",peers.denials.get());
            proof.put("publicationCounts",publicationCounts(jdbc,runId));proof.put("callbackActive",denied.callbackActive());proof.put("childState",denied.state());
            proof.put("boundary","Real Spring/HTTP client with mutable authority stub; no real Admin revoke or business report pipeline claim");
            JSON.writeValue(evidence.resolve("result.json").toFile(),proof);
        } finally { stopOwned(process); }
    }

    private static Map<String,Integer> publicationCounts(JdbcTemplate jdbc,String runId) {
        return Map.of("artifact",jdbc.queryForObject("SELECT COUNT(*) FROM campaign_artifact WHERE run_id=?",Integer.class,runId),
                "release",jdbc.queryForObject("SELECT COUNT(*) FROM campaign_statistics_release WHERE producer_run_id=?",Integer.class,runId),
                "report",jdbc.queryForObject("SELECT COUNT(*) FROM campaign_report_lifecycle WHERE run_id=?",Integer.class,runId));
    }

    private record Fixture(String url,Path evidence,Path classpath,JdbcTemplate jdbc,JdbcCampaignRunStore runs) {}
    private static Fixture fixture(String name) throws Exception {
        String base=System.getenv("ISSUE201_SPRING_MYSQL_URL");
        assertNotNull(base,"Requires a newly initialized, dedicated MySQL fixture; never use project credentials");
        assertTrue(base.matches("jdbc:mysql://127\\.0\\.0\\.1:[2-6][0-9]{4}/issue201_spring_[a-z0-9]+(?:\\?.*)?"));
        Path root=Path.of(Objects.requireNonNull(System.getenv("ISSUE201_SPRING_EVIDENCE"))).toAbsolutePath().normalize();
        assertTrue(root.toString().replace('\\','/').contains("/.work/issue201/spring-pair/"));
        Path evidence=root.resolve(name);Files.createDirectories(evidence);
        var bootstrap=new JdbcTemplate(new DriverManagerDataSource(base,"root",""));
        assertEquals(0,bootstrap.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()",Integer.class),
                "The orchestration schema must remain empty; each method gets a new schema on the same dedicated server");
        String schema="issue201_spring_"+UUID.randomUUID().toString().replace("-","");
        bootstrap.execute("CREATE DATABASE "+schema+" CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        String url=base.replaceFirst("/issue201_spring_[a-z0-9]+(?=\\?|$)","/"+schema);
        assertNotEquals(base,url);
        JSON.writeValue(evidence.resolve("database.json").toFile(),Map.of("schema",schema,"dedicatedServer",true));
        var source=new DriverManagerDataSource(url,"root","");var jdbc=new JdbcTemplate(source);
        new ResourceDatabasePopulator(new ClassPathResource("sql/agent_service_schema.sql")).execute(source);
        Resource[] migrations=new PathMatchingResourcePatternResolver().getResources("classpath*:sql/migration/V202609*.sql");
        Arrays.sort(migrations,Comparator.comparing(resource->migrationKey(resource.getFilename())));
        for (int index=0;index<migrations.length;index++) {
            if ("V20260920_21__campaign_replan_receipt.sql".equals(migrations[index].getFilename())) {
                // Historical H2 dialect only: match deployed MySQL without rewriting migration history.
                // This fixture does NOT certify unadapted migration bootstrap on MySQL.
                String sql;
                try (var input=migrations[index].getInputStream()) { sql=new String(input.readAllBytes(),StandardCharsets.UTF_8); }
                String declaration="CLOB NOT NULL";assertTrue(sql.contains(declaration));
                assertEquals(sql.indexOf(declaration),sql.lastIndexOf(declaration));
                migrations[index]=new ByteArrayResource(sql.replace(declaration,"LONGTEXT NOT NULL").getBytes(StandardCharsets.UTF_8),
                        "fixture-only MySQL dialect for V20260920_21 (one exact CLOB declaration)");
            }
        }
        new ResourceDatabasePopulator(migrations).execute(source);
        var runs=new JdbcCampaignRunStore(jdbc,new TransactionTemplate(new DataSourceTransactionManager(source)),Clock.systemUTC());
        return new Fixture(url,evidence,classpathJar(evidence),jdbc,runs);
    }

    private static String migrationKey(String filename) {
        String[] parts=filename.substring(1,filename.indexOf("__")).split("_");
        return parts[0]+String.format("%04d",parts.length==1?0:Integer.parseInt(parts[1]));
    }
    private static Process startProcess(Path classpath,String url,String domain,Path evidence,String side,int port) throws Exception {
        String java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
        return new ProcessBuilder(java,"-Xms64m","-Xmx512m","-cp",classpath.toString(),SpringProcess.class.getName(),
                url,domain,evidence.toString(),side,Integer.toString(port)).redirectErrorStream(true)
                .redirectOutput(evidence.resolve(side+".log").toFile()).start();
    }
    private static void stopOwned(Process process) throws Exception {
        if (process!=null && process.isAlive()) { process.destroyForcibly();assertTrue(process.waitFor(10,TimeUnit.SECONDS)); }
    }
    private static Path classpathJar(Path evidence) throws Exception {
        String path=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
        var manifest=new Manifest();manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION,"1.0");
        manifest.getMainAttributes().put(Attributes.Name.CLASS_PATH,Arrays.stream(path.split(java.io.File.pathSeparator))
                .filter(value->!Path.of(value).getFileName().toString().equals("test-classes"))
                .map(value->Path.of(value).toAbsolutePath().toUri().toASCIIString()).collect(Collectors.joining(" ")));
        // A production component scan must not discover unrelated test @Configuration classes.
        // Package this fixture's launcher only, rather than granting child processes all test classes.
        Path compiled=Path.of(CampaignSpringProcessFailoverIntegrationTest.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Path packageDirectory=compiled.resolve("com/jupiter/shortlink/agent");
        Path jar=evidence.resolve("classpath.jar");
        try (var output=new JarOutputStream(Files.newOutputStream(jar),manifest);
             var files=Files.list(packageDirectory)) {
            for (Path file:files.filter(value->value.getFileName().toString().startsWith("CampaignSpringProcessFailoverIntegrationTest")
                    && value.getFileName().toString().endsWith(".class")).toList()) {
                output.putNextEntry(new JarEntry("com/jupiter/shortlink/agent/"+file.getFileName()));
                Files.copy(file,output);output.closeEntry();
            }
        }
        return jar;
    }

    public static final class SpringProcess {
        public static void main(String[] args) throws Exception {
            Path evidence=Path.of(args[2]);String side=args[3];String peers="http://127.0.0.1:"+args[4];
            String skills=new ClassPathResource("campaign-skills").getFile().getAbsolutePath();
            try (var context=new SpringApplicationBuilder(ShortLinkAgentApplication.class).run(
                    "--spring.profiles.active=production,campaign-plan-v2",
                    "--spring.config.location=classpath:application-production.properties",
                    "--server.address=127.0.0.1","--server.port=0","--management.server.port=0",
                    "--spring.datasource.url="+args[0],"--spring.datasource.username=root","--spring.datasource.password=",
                    "--spring.sql.init.mode=never","--spring.data.redis.host=127.0.0.1","--spring.data.redis.port=65534",
                    "--spring.data.redis.password=fixture-unused","--management.health.redis.enabled=false",
                    "--short-link.agent.business.base-url="+peers,"--short-link.agent.business.username=fixture-analyst",
                    "--short-link.agent.business.internal-token="+TOKEN,"--short-link.agent.security.internal-token="+TOKEN,
                    "--short-link.agent.deepseek.api-key=fixture-unused","--short-link.agent.deepseek.base-url="+peers+"/model",
                    "--short-link.agent.deepseek.model=fixture-unused","--short-link.agent.risk.analysis.worker-interval-millis=3600000",
                    "--short-link.agent.risk.profile.schedule-cron=-","--short-link.agent.campaign-statistics.process-domain="+args[1],
                    "--short-link.agent.campaign-statistics.dependency-skills-root="+skills,
                    "--short-link.agent.campaign-statistics.active-advances=1","--short-link.agent.campaign-statistics.models=1",
                    "--short-link.agent.campaign-statistics.large-payloads=1","--short-link.agent.campaign-statistics.max-queued=2",
                    "--logging.level.root=INFO")) {
                assertInstanceOf(MysqlSaver.class,context.getBean("mysqlGraphSaver"));
                assertNotNull(context.getBean(CampaignPublicWorkScheduler.class));
                var runtime=context.getBean(CampaignStatisticsFixedRuntime.class);
                runtime.extension(); // Both full campaign-plan-v2 composition and the public scheduler must exist.
                JSON.writeValue(evidence.resolve(side+"-spring.json").toFile(),Map.of("pid",ProcessHandle.current().pid(),
                        "serverPort",((ServletWebServerApplicationContext)context).getWebServer().getPort(),
                        "profiles",List.of(context.getEnvironment().getActiveProfiles()),"mysqlSaver",true,"publicScheduler",true));
                if ("a".equals(side) || "revoke".equals(side)) {
                    runtime.principals().bindCurrent(PRINCIPAL,SESSION);
                    var plan=runtime.plans().ranking(OWNER,SESSION,KEY,"alpha",START,END,"pv",2);
                    var reference=runtime.intake().register(PRINCIPAL,SESSION,KEY,CampaignStatisticsFixedRuntime.PROFILE_REF,
                            CampaignStatisticsFixedRuntime.PROFILE_VERSION,plan.definition());
                    JSON.writeValue(evidence.resolve("reference.json").toFile(),Map.of("runId",reference.runId(),"workId",reference.workId()));
                    // Production scheduler discovers the typed intake row and executes it; no direct manual advance.
                    if ("a".equals(side)) {
                        while (System.in.read()!=-1) {}
                        Runtime.getRuntime().halt(0);
                    } else {
                        assertEquals('v',System.in.read());
                        assertThrows(SecurityException.class,()->runtime.principals().resolve(OWNER,SESSION));
                        assertThrows(SecurityException.class,()->runtime.intake().submit(PRINCIPAL,reference));
                        JSON.writeValue(evidence.resolve("current-access-denied.json").toFile(),Map.of(
                                "currentPrincipalRejected",true,"newAdvanceRejected",true,"runId",reference.runId()));
                    }
                } else {
                    var ref=JSON.readValue(Files.readString(evidence.resolve("reference.json")),new TypeReference<Map<String,String>>(){});
                    String runId=ref.get("runId");long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(45);
                    JdbcTemplate jdbc=context.getBean(JdbcTemplate.class);
                    while (!"SUCCEEDED".equals(jdbc.queryForObject("SELECT step_status FROM campaign_step_ledger WHERE run_id=?",String.class,runId))) {
                        if (System.nanoTime()>deadline) throw new IllegalStateException("SPRING_RECOVERY_DID_NOT_COMPLETE");
                        Thread.sleep(50);
                    }
                    var current=runtime.runs().loadRun(OWNER,runId).orElseThrow();
                    var ranking=runtime.projector().rank(OWNER,current.token(),"collect-1","alpha",START,END,"pv",2,runtime.artifactAuthorizer());
                    assertEquals("READY",ranking.get("status"));assertEquals(2,((List<?>)ranking.get("rows")).size());
                    JSON.writeValue(evidence.resolve("ranking.json").toFile(),ranking);
                }
            }
        }
    }

    private static final class Peers implements AutoCloseable {
        final AtomicInteger submissions=new AtomicInteger(),recoveries=new AtomicInteger(),statusReads=new AtomicInteger(),pages=new AtomicInteger(),models=new AtomicInteger(),releases=new AtomicInteger(),denials=new AtomicInteger();
        final AtomicBoolean authorized=new AtomicBoolean(true);
        final CountDownLatch firstStatus=new CountDownLatch(1),releaseFirst=new CountDownLatch(1);
        final AtomicReference<Throwable> failure=new AtomicReference<>();
        final long created=System.currentTimeMillis(),expires=created+3_600_000;
        final HttpServer server;
        final ExecutorService workers=Executors.newFixedThreadPool(4);
        Peers() throws Exception {
            server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setExecutor(workers);
            server.createContext("/",exchange->{
                try { handle(exchange); }
                catch (Throwable failed) { failure.compareAndSet(null,failed);try { send(exchange,500,Map.of("code","FIXTURE_FAILED")); } catch (Exception ignored) {} }
                finally { exchange.close(); }
            });server.start();
        }
        int port() { return server.getAddress().getPort(); }
        void handle(HttpExchange exchange) throws Exception {
            String path=exchange.getRequestURI().getPath();
            byte[] body=exchange.getRequestBody().readAllBytes();
            if (path.startsWith("/model")) { models.incrementAndGet();throw new AssertionError("No model belongs to this frozen statistics recovery test"); }
            assertEquals(TOKEN,exchange.getRequestHeaders().getFirst("X-Agent-Internal-Token"));
            if (!authorized.get() && (path.endsWith("/authorization/current-principal") || path.endsWith("/authorization/group-members-page"))) {
                denials.incrementAndGet();send(exchange,403,Map.of("code","FORBIDDEN"));return;
            }
            Object data;
            if (path.endsWith("/authorization/current-principal")) data=Map.of("tenantId","1001","username","fixture-analyst","authVersion",7);
            else if (path.endsWith("/authorization/group-members-page")) {
                var request=JSON.readValue(body,new TypeReference<Map<String,Object>>(){});assertEquals("alpha",request.get("gid"));
                data=new GroupMembersPage(GroupMembersPage.SCHEMA,"1001","fixture-analyst",7,"alpha","a".repeat(64),null,List.of(1L,2L),null).asMap();
            } else if (path.endsWith("/authorization/resolve")) data=Map.of("tenantId","1001","ownershipVersion","fixture-v1","links",List.of());
            else if (path.equals("/internal/short-link-admin/v1/agent-tools/risk/scheduled-scopes")) data=Map.of("items",List.of());
            else if (path.endsWith("/release-result")) { releases.incrementAndGet();throw new AssertionError("This fixed-scope fixture must not release a frozen job"); }
            else if (JOBS.equals(path) && "POST".equals(exchange.getRequestMethod())) {
                assertEquals(1,submissions.incrementAndGet());var request=JSON.readValue(body,new TypeReference<Map<String,Object>>(){});
                assertEquals("alpha",request.get("gid"));assertEquals("LINK_METRICS",request.get("queryKind"));
                assertEquals(START,request.get("startDate"));assertEquals(END,request.get("endDate"));assertNotNull(request.get("requestId"));
                data=Map.of("jobId",JOB,"state","SUCCEEDED");
            } else if ((JOBS+"/recover-existing").equals(path)) { recoveries.incrementAndGet();throw new AssertionError("Known job must not be recreated or recovered by request"); }
            else if ((JOBS+"/"+JOB).equals(path)) {
                if (statusReads.incrementAndGet()==1) { firstStatus.countDown();assertTrue(releaseFirst.await(15,TimeUnit.SECONDS)); }
                data=Map.of("jobId",JOB,"state","SUCCEEDED","rowCount",2,"pageCount",1,"expiresAt",expires);
            } else if ((JOBS+"/"+JOB+"/page").equals(path)) { pages.incrementAndGet();data=page(); }
            else throw new AssertionError("Unexpected isolated peer route "+path);
            try { send(exchange,200,Map.of("code","0","data",data)); }
            catch (java.io.IOException disconnected) { if (!path.equals(JOBS+"/"+JOB) || statusReads.get()!=1) throw disconnected; }
        }
        Map<String,Object> page() {
            var meta=new LinkedHashMap<String,Object>();
            meta.put("snapshotId",JOB);meta.put("queryKind","LINK_METRICS");meta.put("gid","alpha");meta.put("linkIds",List.of(1L,2L));
            meta.put("groupScopeComplete",true);meta.put("metricVersion","click-v1");meta.put("recoveryEpoch","epoch-1");
            meta.put("sourceCut",Map.of("manifestSelectionHash","selection-1"));meta.put("manifestVersion",Map.of("selectionHash","selection-1"));
            meta.put("snapshotCreatedAt",created);meta.put("snapshotExpiresAt",expires);meta.put("requestedStart",day(START));
            meta.put("requestedEnd",day("2026-09-03"));meta.put("effectiveEnd",day("2026-09-03"));meta.put("businessTimezone","Asia/Shanghai");
            meta.put("pageIndex",0);meta.put("nextPageIndex",null);meta.put("totalRows",2);meta.put("aggregationLevel","LINK_WINDOW");
            meta.put("availability","AVAILABLE");meta.put("completeness","COMPLETE");meta.put("freshness","FRESH");meta.put("provisional",false);
            meta.put("collectionQuality",Map.of("status","UNKNOWN"));meta.put("missingMetrics",List.of());
            meta.put("approximation",Map.of("pv",Map.of("type","EXACT","algorithm","COUNT","version","v1"),
                    "uv",Map.of("type","APPROXIMATE","algorithm","HLL","version","v1"),"uip",Map.of("type","APPROXIMATE","algorithm","HLL","version","v1")));
            var one=new LinkedHashMap<String,Object>(counts(2,1,1));one.put("linkId",1L);
            var two=new LinkedHashMap<String,Object>(counts(5,1,1));two.put("linkId",2L);
            return Map.of("items",List.of(one,two),"metrics",Map.of("requested",counts(7,2,2)),"meta",meta);
        }
        static Map<String,Object> counts(int pv,int uv,int uip) {
            return Map.of("pv",pv,"uv",uv,"uip",uip,"denied",0,"window","requested","startInclusive",day(START),"endExclusive",day("2026-09-03"));
        }
        static long day(String date) { return LocalDate.parse(date).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli(); }
        static void send(HttpExchange exchange,int status,Object value) throws Exception {
            byte[] body=JSON.writeValueAsBytes(value);exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(status,body.length);try (var out=exchange.getResponseBody()) { out.write(body); }
        }
        @Override public void close() throws Exception { releaseFirst.countDown();server.stop(0);workers.shutdownNow();assertTrue(workers.awaitTermination(5,TimeUnit.SECONDS)); }
    }
}
