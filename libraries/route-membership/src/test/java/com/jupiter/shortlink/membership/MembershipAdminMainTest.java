package com.jupiter.shortlink.membership;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MembershipAdminMainTest {
    @Test void requiresAnExplicitConcreteCatalogAndKnownAction() {
        assertThrows(IllegalArgumentException.class, () -> MembershipAdminMain.Arguments.parse(new String[]{"init"}));
        assertThrows(IllegalArgumentException.class, () -> MembershipAdminMain.Arguments.parse(
                new String[]{"delete", "--confirm-catalog", "test"}));
        assertThrows(IllegalArgumentException.class, () -> MembershipAdminMain.Arguments.parse(
                new String[]{"init", "--confirm-catalog", "*"}));
        assertThrows(IllegalArgumentException.class, () -> MembershipAdminMain.Arguments.parse(
                new String[]{"init", "--confirm-catalog", "test", "--force"}));
    }

    @Test void capturesAnExplicitAllWritersUpgradedAssertion() {
        MembershipAdminMain.Arguments arguments = MembershipAdminMain.Arguments.parse(new String[]{
                "init", "--confirm-catalog", "shortlink_business", "--confirm-writers-upgraded"});
        assertEquals("init", arguments.action());
        assertEquals("shortlink_business", arguments.catalog());
        assertTrue(arguments.writersUpgraded());
    }
}
