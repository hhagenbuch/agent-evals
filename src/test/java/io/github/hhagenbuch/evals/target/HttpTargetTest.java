package io.github.hhagenbuch.evals.target;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpTargetTest {

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private int startServer(com.sun.net.httpserver.HttpHandler handler) throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/chat", handler);
        server.start();
        return server.getAddress().getPort();
    }

    private static void reply(com.sun.net.httpserver.HttpExchange exchange, int status, String json)
            throws java.io.IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    @Test
    void returnsTheReplyAndToolsOnA200() throws Exception {
        int port = startServer(ex -> reply(ex, 200,
                "{\"reply\":\"hello\",\"toolsUsed\":[\"clock\"]}"));

        TargetResponse response = new HttpTarget("http://localhost:" + port + "/api/chat")
                .respond("hi");

        assertThat(response.reply()).isEqualTo("hello");
        assertThat(response.toolsUsed()).containsExactly("clock");
    }

    @Test
    void retriesA5xxWhileTheCanaryIsStillRollingThenSucceeds() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        int port = startServer(ex -> {
            if (calls.getAndIncrement() < 2) {
                reply(ex, 503, "starting");
            } else {
                reply(ex, 200, "{\"reply\":\"ready now\",\"toolsUsed\":[]}");
            }
        });

        TargetResponse response = new HttpTarget("http://localhost:" + port + "/api/chat",
                Duration.ofSeconds(30), millis -> { /* no real waiting */ }).respond("hi");

        assertThat(response.reply()).isEqualTo("ready now");
        assertThat(calls.get()).isEqualTo(3);
    }

    @Test
    void retriesAConnectionRefusalUntilTheBudgetThenFails() {
        // Nothing is listening on this port: every attempt is connection-refused.
        HttpTarget target = new HttpTarget("http://localhost:1/api/chat",
                Duration.ofMillis(50), millis -> { });

        assertThatThrownBy(() -> target.respond("hi"))
                .hasMessageContaining("never became ready");
    }

    @Test
    void a4xxIsARealFailureAndIsNotRetried() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        int port = startServer(ex -> {
            calls.incrementAndGet();
            reply(ex, 400, "bad request");
        });

        assertThatThrownBy(() -> new HttpTarget("http://localhost:" + port + "/api/chat",
                Duration.ofSeconds(30), millis -> { }).respond("hi"))
                .hasMessageContaining("HTTP 400");
        assertThat(calls.get()).isEqualTo(1); // not retried
    }
}
