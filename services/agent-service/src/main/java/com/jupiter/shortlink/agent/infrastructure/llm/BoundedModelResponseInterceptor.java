package com.jupiter.shortlink.agent.infrastructure.llm;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/** Limits the response stream before either Jackson or RestTemplate's error handler reads it. */
public final class BoundedModelResponseInterceptor implements ClientHttpRequestInterceptor {
    private final int maximumBytes;

    public BoundedModelResponseInterceptor(int maximumBytes) {
        if (maximumBytes < 1) throw new IllegalArgumentException("Model response byte limit must be positive");
        this.maximumBytes = maximumBytes;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body,
                                        ClientHttpRequestExecution execution) throws IOException {
        ClientHttpResponse response = execution.execute(request, body);
        InputStream stream;
        try {
            stream = response.getBody();
        } catch (IOException | RuntimeException failure) {
            response.close();
            throw failure;
        }
        try {
            if (response.getHeaders().getContentLength() > maximumBytes) throw exceeded();
        } catch (RuntimeException failure) {
            closeBody(stream, response);
            throw failure;
        }
        InputStream bounded = new LimitedStream(stream, maximumBytes);
        return new ClientHttpResponse() {
            @Override public HttpStatusCode getStatusCode() throws IOException { return response.getStatusCode(); }
            @Override public int getRawStatusCode() throws IOException { return response.getStatusCode().value(); }
            @Override public String getStatusText() throws IOException { return response.getStatusText(); }
            @Override public HttpHeaders getHeaders() { return response.getHeaders(); }
            @Override public InputStream getBody() { return bounded; }
            @Override public void close() { closeBody(bounded, response); }
        };
    }

    private static void closeBody(InputStream body, ClientHttpResponse response) {
        // Close the stream first: a transport must not drain the rest of an oversized body.
        try { body.close(); } catch (IOException ignored) { }
        finally { response.close(); }
    }

    private static LlmChatClientException exceeded() {
        return new LlmChatClientException("DeepSeek response exceeded the configured byte limit");
    }

    private static final class LimitedStream extends FilterInputStream {
        private final long limit;
        private long count;

        private LimitedStream(InputStream delegate, int limit) {
            super(delegate);
            this.limit = limit;
        }

        @Override
        public int read() throws IOException {
            if (count > limit) throw exceeded();
            int value = in.read();
            if (value != -1) consumed(1);
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, buffer.length);
            if (length == 0) return 0;
            if (count > limit) throw exceeded();
            int read = in.read(buffer, offset, (int) Math.min(length, limit - count + 1));
            if (read > 0) consumed(read);
            return read;
        }

        @Override
        public long skip(long requested) throws IOException {
            if (requested <= 0) return 0;
            byte[] buffer = new byte[(int) Math.min(requested, 8192)];
            long skipped = 0;
            while (skipped < requested) {
                int read = read(buffer, 0, (int) Math.min(buffer.length, requested - skipped));
                if (read == -1) break;
                skipped += read;
            }
            return skipped;
        }

        // RestTemplate's empty-body probe uses pushback instead of rereading/counting the first byte.
        @Override public boolean markSupported() { return false; }
        @Override public synchronized void mark(int readLimit) { }
        @Override public synchronized void reset() throws IOException { throw new IOException("Mark/reset is unsupported"); }

        private void consumed(int bytes) throws IOException {
            count += bytes;
            if (count > limit) {
                try { in.close(); } catch (IOException ignored) { }
                throw exceeded();
            }
        }
    }
}
