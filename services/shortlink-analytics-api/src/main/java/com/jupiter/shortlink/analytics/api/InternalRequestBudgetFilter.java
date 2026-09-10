package com.jupiter.shortlink.analytics.api;

import jakarta.servlet.*;
import jakarta.servlet.http.*;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.Semaphore;

/** Authenticate before JSON decoding; fixed in-flight capacity has no application queue. */
@Component
@Order(-100)
public final class InternalRequestBudgetFilter extends OncePerRequestFilter {
    private final byte[] token;
    private final Semaphore slots = new Semaphore(16);

    public InternalRequestBudgetFilter(ApiSettings settings) {
        token = settings.token().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        String path = request.getRequestURI();
        if ("GET".equals(request.getMethod()) && "/actuator/health".equals(path)) {
            chain.doFilter(request, response);
            return;
        }
        String supplied = request.getHeader("X-Internal-Token");
        if (supplied == null
                || !MessageDigest.isEqual(token, supplied.getBytes(StandardCharsets.UTF_8))) {
            response.sendError(403);
            return;
        }
        if (!path.startsWith("/internal/analytics/v1/")) {
            response.sendError(404);
            return;
        }
        String encoding = request.getHeader("Content-Encoding");
        if (encoding != null && !"identity".equalsIgnoreCase(encoding)) {
            response.sendError(415);
            return;
        }
        int max = path.endsWith("/query") || path.endsWith("/jobs") ? 262144 : 16384;
        if (request.getContentLengthLong() > max) {
            response.sendError(413);
            return;
        }
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
                                            "Asynchronous request bodies are unsupported");
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
