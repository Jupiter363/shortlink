package com.jupiter.shortlink.id;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.time.Duration;

class StepControllerTest {
    private StepPolicy policy(boolean enabled) {
        return new StepPolicy(
                enabled,
                100,
                25,
                400,
                Duration.ofNanos(10),
                Duration.ofNanos(100),
                2,
                Duration.ofNanos(1000));
    }

    @Test
    void requiresConsecutiveSamplesAndHonorsCooldownAndBounds() {
        StepController controller = new StepController(policy(true));
        controller.consumed(1, 1, 100);
        assertEquals(100, controller.currentStep());
        controller.consumed(50, 2, 100); // neutral resets hysteresis
        controller.consumed(1, 3, 100);
        assertEquals(100, controller.currentStep());
        controller.consumed(1, 4, 100);
        assertEquals(200, controller.currentStep());
        controller.consumed(1, 5, 200);
        controller.consumed(1, 6, 200);
        assertEquals(200, controller.currentStep());
        controller.consumed(1, 1004, 200);
        assertEquals(400, controller.currentStep());
        controller.consumed(1, 3000, 400);
        controller.consumed(1, 3001, 400);
        assertEquals(400, controller.currentStep());
        controller.consumed(101, 5000, 400);
        controller.consumed(101, 5001, 400);
        assertEquals(200, controller.currentStep());
        for (int i = 0; i < 10; i++) controller.consumed(101, 7000L + i * 1001L, 200);
        assertEquals(25, controller.currentStep());
        assertTrue(controller.changes() >= 4);
    }

    @Test
    void disabledOrShortTailCannotDriveFeedbackAndReconfigureResetsDemand() {
        StepController controller = new StepController(policy(false));
        for (int i = 0; i < 5; i++) controller.consumed(1, i, 100);
        assertEquals(100, controller.currentStep());
        controller.configure(policy(true));
        for (int i = 0; i < 5; i++) controller.consumed(1, i, 2);
        assertEquals(100, controller.currentStep());
        controller.consumed(1, 100, 100);
        controller.consumed(1, 101, 100);
        assertEquals(200, controller.currentStep());
        controller.configure(policy(false));
        assertEquals(100, controller.currentStep());
    }

    @Test
    void invalidPoliciesFailAtConstruction() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new StepPolicy(
                                true,
                                100,
                                200,
                                400,
                                Duration.ofSeconds(1),
                                Duration.ofSeconds(2),
                                2,
                                Duration.ZERO));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new StepPolicy(
                                true,
                                100,
                                25,
                                400,
                                Duration.ofSeconds(2),
                                Duration.ofSeconds(1),
                                2,
                                Duration.ZERO));
    }
}
