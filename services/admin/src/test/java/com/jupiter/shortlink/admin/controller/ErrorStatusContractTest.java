package com.jupiter.shortlink.admin.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.jupiter.shortlink.admin.common.convention.web.GlobalExceptionHandler;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

class ErrorStatusContractTest {
    @RestController
    static class Errors {
        @GetMapping("/expired")
        public void expired() {
            throw new ResponseStatusException(HttpStatus.GONE, "Snapshot expired");
        }

        @GetMapping("/unavailable")
        public void unavailable() {
            throw new IllegalStateException("fixture internal failure");
        }
    }

    @Test
    void globalAdvicePreservesProtocolFailureInsteadOfReturningHttp200() throws Exception {
        var mvc =
                MockMvcBuilders.standaloneSetup(new Errors())
                        .setControllerAdvice(new GlobalExceptionHandler())
                        .build();
        mvc.perform(get("/expired"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("HTTP_410"));
        mvc.perform(get("/unavailable")).andExpect(status().isInternalServerError());
    }
}
