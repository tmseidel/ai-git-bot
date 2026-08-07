package org.remus.giteabot.webhook;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

/**
 * Hard size cap for the unauthenticated {@code /api/**} surface (webhooks and
 * workflow callbacks). Without it, anonymous callers could POST arbitrarily
 * large JSON bodies and force full in-memory parsing before any secret check
 * runs - a cheap memory/CPU DoS.
 *
 * <p>Requests with a declared {@code Content-Length} above the limit get a
 * {@code 413}. Chunked requests (unknown length) are wrapped in a bounded
 * input stream that aborts once the limit is exceeded.</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ApiPayloadSizeLimitFilter extends OncePerRequestFilter {

    private final long maxBodyBytes;

    public ApiPayloadSizeLimitFilter(
            @Value("${giteabot.api.max-body-bytes:2097152}") long maxBodyBytes) {
        this.maxBodyBytes = maxBodyBytes;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        long declared = request.getContentLengthLong();
        if (declared > maxBodyBytes) {
            response.sendError(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, "Payload too large");
            return;
        }
        if (declared < 0) {
            // Chunked transfer encoding - enforce the cap while reading.
            request = new BoundedBodyRequestWrapper(request, maxBodyBytes);
        }
        filterChain.doFilter(request, response);
    }

    private static final class BoundedBodyRequestWrapper extends HttpServletRequestWrapper {
        private final long maxBytes;

        BoundedBodyRequestWrapper(HttpServletRequest request, long maxBytes) {
            super(request);
            this.maxBytes = maxBytes;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            return new BoundedServletInputStream(super.getInputStream(), maxBytes);
        }

        @Override
        public BufferedReader getReader() throws IOException {
            return new BufferedReader(new InputStreamReader(getInputStream(), getCharacterEncoding()));
        }
    }

    private static final class BoundedServletInputStream extends ServletInputStream {
        private final InputStream delegate;
        private final long maxBytes;
        private long consumed;

        BoundedServletInputStream(InputStream delegate, long maxBytes) {
            this.delegate = delegate;
            this.maxBytes = maxBytes;
        }

        @Override
        public int read() throws IOException {
            int value = delegate.read();
            if (value != -1) {
                count(1);
            }
            return value;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int read = delegate.read(b, off, len);
            if (read > 0) {
                count(read);
            }
            return read;
        }

        private void count(long n) throws IOException {
            consumed += n;
            if (consumed > maxBytes) {
                throw new IOException("Request body exceeds the configured limit of " + maxBytes + " bytes");
            }
        }

        @Override
        public boolean isFinished() {
            try {
                return delegate.available() == 0;
            } catch (IOException e) {
                return true;
            }
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            // async reads not used here
        }
    }
}
