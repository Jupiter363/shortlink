package com.nageoffer.shortlink.agent.harness.action.api.dto;

import com.nageoffer.shortlink.agent.harness.action.model.AgentActionPage;
import com.nageoffer.shortlink.agent.harness.action.model.AgentPendingActionView;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public record AgentActionPageRespDTO(
        List<AgentPendingActionRespDTO> records,
        long total,
        int pageNo,
        int pageSize
) {

    public AgentActionPageRespDTO {
        records = records == null ? List.of() : List.copyOf(records);
    }

    public static AgentActionPageRespDTO from(AgentActionPage<AgentPendingActionView> page) {
        Objects.requireNonNull(page, "page must not be null");
        List<AgentPendingActionRespDTO> records = new ArrayList<>(page.records().size());
        for (AgentPendingActionView view : page.records()) {
            records.add(AgentPendingActionRespDTO.from(view));
        }
        return new AgentActionPageRespDTO(
                records,
                page.total(),
                page.pageNo(),
                page.pageSize()
        );
    }
}
