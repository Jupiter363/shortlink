package com.nageoffer.shortlink.agent.riskcenter;

import com.nageoffer.shortlink.agent.riskcenter.repository.JdbcRiskEventRepository;
import com.nageoffer.shortlink.agent.riskcenter.repository.JdbcRiskReviewRepository;
import com.nageoffer.shortlink.agent.riskcenter.repository.JdbcRiskSnapshotRepository;
import com.nageoffer.shortlink.agent.riskcenter.service.RiskCenterService;
import com.nageoffer.shortlink.agent.riskpolicy.service.RiskPolicyService;
import com.nageoffer.shortlink.agent.riskprofile.repository.JdbcGroupRiskProfileRepository;
import com.nageoffer.shortlink.agent.riskprofile.repository.JdbcShortLinkRiskProfileRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RiskCenterServiceTest {

    @Test
    void groupOverviewFailsWhenRiskProfileHasNotBeenEvaluated() {
        JdbcGroupRiskProfileRepository groupProfileRepository = mock(JdbcGroupRiskProfileRepository.class);
        when(groupProfileRepository.findLatestByGid("gid-missing")).thenReturn(Optional.empty());
        RiskCenterService service = new RiskCenterService(
                mock(JdbcRiskEventRepository.class),
                mock(JdbcRiskSnapshotRepository.class),
                mock(JdbcRiskReviewRepository.class),
                mock(JdbcShortLinkRiskProfileRepository.class),
                groupProfileRepository,
                mock(RiskPolicyService.class)
        );

        assertThatThrownBy(() -> service.getGroupOverview("gid-missing"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Group risk profile is not available");
    }
}
