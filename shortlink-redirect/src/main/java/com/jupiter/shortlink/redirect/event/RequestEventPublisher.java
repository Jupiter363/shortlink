package com.jupiter.shortlink.redirect.event;

import com.jupiter.shortlink.contract.ClickEventV1;
import com.jupiter.shortlink.contract.GatewayRequestEventV1;

public interface RequestEventPublisher {
    default java.util.Map<String, Object> quality() {
        return java.util.Map.of("available", false);
    }

    boolean click(ClickEventV1 event);

    boolean result(GatewayRequestEventV1 event);
}
