package com.nageoffer.shortlink.agent.infrastructure.config;

import com.nageoffer.shortlink.agent.riskanalysis.job.JdbcRiskAnalysisJobRepository;
import com.nageoffer.shortlink.agent.riskanalysis.job.RiskAnalysisJobPublicationService;
import com.nageoffer.shortlink.agent.riskanalysis.job.RiskAnalysisJobService;
import com.nageoffer.shortlink.agent.riskcenter.repository.JdbcRiskEventRepository;
import com.nageoffer.shortlink.agent.riskcenter.repository.JdbcRiskReviewRepository;
import com.nageoffer.shortlink.agent.riskcenter.repository.JdbcRiskSnapshotRepository;
import com.nageoffer.shortlink.agent.riskcenter.service.RiskCenterService;
import com.nageoffer.shortlink.agent.riskpolicy.service.RiskPolicyRedisPublisher;
import com.nageoffer.shortlink.agent.riskpolicy.service.RiskPolicyService;
import com.nageoffer.shortlink.agent.riskprofile.api.RiskProfileInternalController;
import com.nageoffer.shortlink.agent.riskprofile.batch.RiskProfileBatchCoordinator;
import com.nageoffer.shortlink.agent.riskprofile.repository.JdbcGroupRiskProfileRepository;
import com.nageoffer.shortlink.agent.riskprofile.repository.JdbcRiskProfileBatchRepository;
import com.nageoffer.shortlink.agent.riskprofile.repository.JdbcShortLinkRiskProfileRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;
import java.time.ZoneId;
import java.util.List;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AgentDefaultClockContractTest {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    @Test
    void productionConstructorsUseShanghaiClockIndependentlyOfJvmDefaultTimeZone() {
        TimeZone originalTimeZone = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"));

            JdbcRiskProfileBatchRepository batchRepository = mock(JdbcRiskProfileBatchRepository.class);
            JdbcRiskAnalysisJobRepository jobRepository = mock(JdbcRiskAnalysisJobRepository.class);
            RiskAnalysisJobService jobService = new RiskAnalysisJobService(jobRepository);
            List<Object> clockOwners = List.of(
                    jobService,
                    new RiskAnalysisJobPublicationService(
                            batchRepository,
                            jobRepository,
                            jobService,
                            mock(PlatformTransactionManager.class)
                    ),
                    new RiskCenterService(
                            mock(JdbcRiskEventRepository.class),
                            mock(JdbcRiskSnapshotRepository.class),
                            mock(JdbcRiskReviewRepository.class),
                            mock(JdbcShortLinkRiskProfileRepository.class),
                            mock(JdbcGroupRiskProfileRepository.class),
                            mock(RiskPolicyService.class)
                    ),
                    new RiskPolicyRedisPublisher(mock(StringRedisTemplate.class)),
                    new RiskProfileInternalController(mock(RiskProfileBatchCoordinator.class))
            );

            assertThat(clockOwners)
                    .allSatisfy(owner -> assertThat(clock(owner).getZone()).isEqualTo(SHANGHAI));
        } finally {
            TimeZone.setDefault(originalTimeZone);
        }
    }

    private Clock clock(Object owner) {
        return (Clock) ReflectionTestUtils.getField(owner, "clock");
    }
}
