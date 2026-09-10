package com.jupiter.shortlink.command.security;

import jakarta.servlet.*;
import jakarta.servlet.http.*;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Semaphore;

@Component
@Order(2)
public class RequestBudgetFilter extends OncePerRequestFilter {
    private final Semaphore small = new Semaphore(64), large = new Semaphore(8);

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        String method = request.getMethod();
        if (!method.equals("POST") && !method.equals("PUT") && !method.equals("PATCH")) {
            chain.doFilter(request, response);
            return;
        }
        String encoding = request.getHeader("Content-Encoding");
        if (encoding != null && !encoding.equalsIgnoreCase("identity")) {
            response.sendError(415);
            return;
        }
        boolean bulk =
                request.getRequestURI().endsWith("/create/batch")
                        || request.getRequestURI().equals("/internal/command/batches");
        int max = bulk ? 8 * 1024 * 1024 : 256 * 1024;
        if (request.getContentLengthLong() > max) {
            response.sendError(413);
            return;
        }
        Semaphore slots = bulk ? large : small;
        if (!slots.tryAcquire()) {
            response.setHeader("Retry-After", "1");
            response.sendError(503);
            return;
        }
        try {
            byte[] bytes = request.getInputStream().readNBytes(max + 1);
            if (bytes.length > max) {
                response.sendError(413);
                return;
            }
            chain.doFilter(
                    new HttpServletRequestWrapper(request) {
                        @Override
                        public int getContentLength() {
                            return bytes.length;
                        }

                        @Override
                        public long getContentLengthLong() {
                            return bytes.length;
                        }

                        @Override
                        public ServletInputStream getInputStream() {
                            ByteArrayInputStream input = new ByteArrayInputStream(bytes);
                            return new ServletInputStream() {
                                @Override
                                public int read() {
                                    return input.read();
                                }

                                @Override
                                public int read(byte[] b, int off, int len) {
                                    return input.read(b, off, len);
                                }

                                @Override
                                public boolean isFinished() {
                                    return input.available() == 0;
                                }

                                @Override
                                public boolean isReady() {
                                    return true;
                                }

                                @Override
                                public void setReadListener(ReadListener listener) {
                                    throw new IllegalStateException(
                                            "Only bounded blocking request bodies are supported");
                                }
                            };
                        }

                        @Override
                        public BufferedReader getReader() {
                            return new BufferedReader(
                                    new InputStreamReader(
                                            getInputStream(), StandardCharsets.UTF_8));
                        }
                    },
                    response);
        } finally {
            slots.release();
        }
    }
}
