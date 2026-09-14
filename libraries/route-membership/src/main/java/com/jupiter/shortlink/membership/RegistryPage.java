package com.jupiter.shortlink.membership;

import java.util.List;

public record RegistryPage(ControlSnapshot control, List<RegistryEntry> entries,
                           long nextOrdinal, boolean complete) {
    public RegistryPage { entries = List.copyOf(entries); }
}
