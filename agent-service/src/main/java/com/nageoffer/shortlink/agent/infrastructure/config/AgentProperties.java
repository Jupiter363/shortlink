package com.nageoffer.shortlink.agent.infrastructure.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "short-link.agent")
@Validated
public class AgentProperties {

    private boolean enabled = true;

    private Graph graph = new Graph();

    private Console console = new Console();

    private Business business = new Business();

    private Security security = new Security();

    @Valid
    @NotNull
    private Action action = new Action();

    private Risk risk = new Risk();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Graph getGraph() {
        return graph;
    }

    public void setGraph(Graph graph) {
        this.graph = graph;
    }

    public Console getConsole() {
        return console;
    }

    public void setConsole(Console console) {
        this.console = console;
    }

    public Business getBusiness() {
        return business;
    }

    public void setBusiness(Business business) {
        this.business = business;
    }

    public Security getSecurity() {
        return security;
    }

    public void setSecurity(Security security) {
        this.security = security;
    }

    public Action getAction() {
        return action;
    }

    public void setAction(Action action) {
        this.action = action;
    }

    public Risk getRisk() {
        return risk;
    }

    public void setRisk(Risk risk) {
        this.risk = risk;
    }

    public static class Graph {

        private String name = "campaign-analysis-graph";

        private String version = "v1";

        private boolean checkpointEnabled = true;

        private boolean streamEnabled = false;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getVersion() {
            return version;
        }

        public void setVersion(String version) {
            this.version = version;
        }

        public boolean isCheckpointEnabled() {
            return checkpointEnabled;
        }

        public void setCheckpointEnabled(boolean checkpointEnabled) {
            this.checkpointEnabled = checkpointEnabled;
        }

        public boolean isStreamEnabled() {
            return streamEnabled;
        }

        public void setStreamEnabled(boolean streamEnabled) {
            this.streamEnabled = streamEnabled;
        }
    }

    public static class Console {

        private boolean enabled = true;

        private String basePath = "/agent-console";

        private boolean devMode = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getBasePath() {
            return basePath;
        }

        public void setBasePath(String basePath) {
            this.basePath = basePath;
        }

        public boolean isDevMode() {
            return devMode;
        }

        public void setDevMode(boolean devMode) {
            this.devMode = devMode;
        }
    }

    public static class Business {

        private String baseUrl = "http://127.0.0.1:8002";

        private String internalToken = "";

        private String username = "";

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getInternalToken() {
            return internalToken;
        }

        public void setInternalToken(String internalToken) {
            this.internalToken = internalToken;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }
    }

    public static class Security {

        private String internalToken = "";

        private boolean internalTokenDevMode = false;

        public String getInternalToken() {
            return internalToken;
        }

        public void setInternalToken(String internalToken) {
            this.internalToken = internalToken;
        }

        public boolean isInternalTokenDevMode() {
            return internalTokenDevMode;
        }

        public void setInternalTokenDevMode(boolean internalTokenDevMode) {
            this.internalTokenDevMode = internalTokenDevMode;
        }
    }

    public static class Action {

        public static final int MIN_EXECUTION_LEASE_SECONDS = 1;

        public static final int MAX_EXECUTION_LEASE_SECONDS = 86_400;

        public static final long MIN_RECOVERY_INTERVAL_MILLIS = 1_000L;

        public static final long MAX_RECOVERY_INTERVAL_MILLIS = 3_600_000L;

        @Min(MIN_EXECUTION_LEASE_SECONDS)
        @Max(MAX_EXECUTION_LEASE_SECONDS)
        private int executionLeaseSeconds = 300;

        @Min(MIN_RECOVERY_INTERVAL_MILLIS)
        @Max(MAX_RECOVERY_INTERVAL_MILLIS)
        private long recoveryIntervalMillis = 60_000L;

        public int getExecutionLeaseSeconds() {
            return executionLeaseSeconds;
        }

        public void setExecutionLeaseSeconds(int executionLeaseSeconds) {
            this.executionLeaseSeconds = executionLeaseSeconds;
        }

        public long getRecoveryIntervalMillis() {
            return recoveryIntervalMillis;
        }

        public void setRecoveryIntervalMillis(long recoveryIntervalMillis) {
            this.recoveryIntervalMillis = recoveryIntervalMillis;
        }
    }

    public static class Risk {

        private String hashSalt = "";

        private Profile profile = new Profile();

        private Analysis analysis = new Analysis();

        private AutoAction autoAction = new AutoAction();

        private ManualAction manualAction = new ManualAction();

        private Redis redis = new Redis();

        public String getHashSalt() {
            return hashSalt;
        }

        public void setHashSalt(String hashSalt) {
            this.hashSalt = hashSalt;
        }

        public Profile getProfile() {
            return profile;
        }

        public void setProfile(Profile profile) {
            this.profile = profile;
        }

        public Analysis getAnalysis() {
            return analysis;
        }

        public void setAnalysis(Analysis analysis) {
            this.analysis = analysis;
        }

        public AutoAction getAutoAction() {
            return autoAction;
        }

        public void setAutoAction(AutoAction autoAction) {
            this.autoAction = autoAction;
        }

        public ManualAction getManualAction() {
            return manualAction;
        }

        public void setManualAction(ManualAction manualAction) {
            this.manualAction = manualAction;
        }

        public Redis getRedis() {
            return redis;
        }

        public void setRedis(Redis redis) {
            this.redis = redis;
        }
    }

    public static class Profile {

        private int batchIntervalMinutes = 120;

        private int activeScanDays = 7;

        private int topCandidateSize = 10;

        private int failedRecoveryLimit = 3;

        public int getBatchIntervalMinutes() {
            return batchIntervalMinutes;
        }

        public void setBatchIntervalMinutes(int batchIntervalMinutes) {
            this.batchIntervalMinutes = batchIntervalMinutes;
        }

        public int getActiveScanDays() {
            return activeScanDays;
        }

        public void setActiveScanDays(int activeScanDays) {
            this.activeScanDays = activeScanDays;
        }

        public int getTopCandidateSize() {
            return topCandidateSize;
        }

        public void setTopCandidateSize(int topCandidateSize) {
            this.topCandidateSize = topCandidateSize;
        }

        public int getFailedRecoveryLimit() {
            return failedRecoveryLimit;
        }

        public void setFailedRecoveryLimit(int failedRecoveryLimit) {
            this.failedRecoveryLimit = failedRecoveryLimit;
        }
    }

    public static class Analysis {

        private int jobLeaseMinutes = 5;

        private int maxAttempts = 3;

        private int retryInitialSeconds = 30;

        private int retryMaxSeconds = 600;

        private long workerIntervalMillis = 5000L;

        public int getJobLeaseMinutes() {
            return jobLeaseMinutes;
        }

        public void setJobLeaseMinutes(int jobLeaseMinutes) {
            this.jobLeaseMinutes = jobLeaseMinutes;
        }

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        public int getRetryInitialSeconds() {
            return retryInitialSeconds;
        }

        public void setRetryInitialSeconds(int retryInitialSeconds) {
            this.retryInitialSeconds = retryInitialSeconds;
        }

        public int getRetryMaxSeconds() {
            return retryMaxSeconds;
        }

        public void setRetryMaxSeconds(int retryMaxSeconds) {
            this.retryMaxSeconds = retryMaxSeconds;
        }

        public long getWorkerIntervalMillis() {
            return workerIntervalMillis;
        }

        public void setWorkerIntervalMillis(long workerIntervalMillis) {
            this.workerIntervalMillis = workerIntervalMillis;
        }
    }

    public static class AutoAction {

        private boolean limitRateEnabled = true;

        private int limitRateMinScore = 80;

        private int limitRateLimit = 60;

        private int limitRateWindowSeconds = 60;

        public boolean isLimitRateEnabled() {
            return limitRateEnabled;
        }

        public void setLimitRateEnabled(boolean limitRateEnabled) {
            this.limitRateEnabled = limitRateEnabled;
        }

        public int getLimitRateMinScore() {
            return limitRateMinScore;
        }

        public void setLimitRateMinScore(int limitRateMinScore) {
            this.limitRateMinScore = limitRateMinScore;
        }

        public int getLimitRateLimit() {
            return limitRateLimit;
        }

        public void setLimitRateLimit(int limitRateLimit) {
            this.limitRateLimit = limitRateLimit;
        }

        public int getLimitRateWindowSeconds() {
            return limitRateWindowSeconds;
        }

        public void setLimitRateWindowSeconds(int limitRateWindowSeconds) {
            this.limitRateWindowSeconds = limitRateWindowSeconds;
        }
    }

    public static class ManualAction {

        private int expireHours = 24;

        private int disableMinScore = 90;

        private int disableMinStrongReasonCount = 3;

        private int ignoreSuppressionHours = 24;

        private int falsePositiveSuppressionHours = 168;

        public int getExpireHours() {
            return expireHours;
        }

        public void setExpireHours(int expireHours) {
            this.expireHours = expireHours;
        }

        public int getDisableMinScore() {
            return disableMinScore;
        }

        public void setDisableMinScore(int disableMinScore) {
            this.disableMinScore = disableMinScore;
        }

        public int getDisableMinStrongReasonCount() {
            return disableMinStrongReasonCount;
        }

        public void setDisableMinStrongReasonCount(int disableMinStrongReasonCount) {
            this.disableMinStrongReasonCount = disableMinStrongReasonCount;
        }

        public int getIgnoreSuppressionHours() {
            return ignoreSuppressionHours;
        }

        public void setIgnoreSuppressionHours(int ignoreSuppressionHours) {
            this.ignoreSuppressionHours = ignoreSuppressionHours;
        }

        public int getFalsePositiveSuppressionHours() {
            return falsePositiveSuppressionHours;
        }

        public void setFalsePositiveSuppressionHours(int falsePositiveSuppressionHours) {
            this.falsePositiveSuppressionHours = falsePositiveSuppressionHours;
        }
    }

    public static class Redis {

        private String keyPrefix = "risk";

        public String getKeyPrefix() {
            return keyPrefix;
        }

        public void setKeyPrefix(String keyPrefix) {
            this.keyPrefix = keyPrefix;
        }
    }
}
