package com.linetranslate.bot;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.mongodb.ConnectionString;
import com.mongodb.ServerAddress;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("test")
class MongoDelayedAvailabilityIntegrationTests {

    private static final DelayedTcpProxy MONGO_PROXY = DelayedTcpProxy.create();

    @Autowired
    private TestRestTemplate restTemplate;

    @DynamicPropertySource
    static void mongoProperties(DynamicPropertyRegistry registry) {
        registry.add(
                "mongodb.uri",
                () -> "mongodb://127.0.0.1:" + MONGO_PROXY.port() + "/linebot_recovery_test");
        registry.add("mongodb.database", () -> "linebot_recovery_test");
        registry.add("mongodb.connect-timeout-ms", () -> 200);
        registry.add("mongodb.read-timeout-ms", () -> 500);
        registry.add("mongodb.server-selection-timeout-ms", () -> 300);
        registry.add("mongodb.heartbeat-frequency-ms", () -> 250);
        registry.add("mongodb.min-heartbeat-frequency-ms", () -> 100);
        registry.add("minio.enabled", () -> false);
        registry.add("openrouter.api.key", () -> "test-openrouter-key");
    }

    @AfterAll
    static void closeProxy() {
        MONGO_PROXY.close();
    }

    @Test
    void applicationStartsBeforeMongoAndBecomesReadyAfterRecovery() throws Exception {
        assertThat(health("/actuator/health/liveness").getStatusCode())
                .isEqualTo(HttpStatus.OK);

        ResponseEntity<Map<String, Object>> unavailable = health("/actuator/health/readiness");
        assertThat(unavailable.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(unavailable.getBody()).containsEntry("status", "DOWN");

        MONGO_PROXY.forwardTo(upstreamMongoAddress());

        ResponseEntity<Map<String, Object>> recovered = awaitReady(Duration.ofSeconds(15));
        assertThat(recovered.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(recovered.getBody()).containsEntry("status", "UP");
    }

    private ResponseEntity<Map<String, Object>> awaitReady(Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        ResponseEntity<Map<String, Object>> response = health("/actuator/health/readiness");
        while (response.getStatusCode() != HttpStatus.OK && System.nanoTime() < deadline) {
            Thread.sleep(250);
            response = health("/actuator/health/readiness");
        }
        return response;
    }

    private ResponseEntity<Map<String, Object>> health(String path) {
        return restTemplate.exchange(
                path,
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() { });
    }

    private static InetSocketAddress upstreamMongoAddress() {
        String configuredUri = System.getenv().getOrDefault(
                "MONGODB_RECOVERY_UPSTREAM_URI",
                System.getenv().getOrDefault(
                        "MONGODB_URI",
                        "mongodb://127.0.0.1:27018/linebot_translator_test"));
        List<String> hosts = new ConnectionString(configuredUri).getHosts();
        ServerAddress address = new ServerAddress(hosts.get(0));
        return new InetSocketAddress(address.getHost(), address.getPort());
    }

    private static final class DelayedTcpProxy implements AutoCloseable {

        private final ServerSocket serverSocket;
        private final ExecutorService executor;
        private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
        private volatile InetSocketAddress target;

        private DelayedTcpProxy(ServerSocket serverSocket) {
            this.serverSocket = serverSocket;
            this.executor = Executors.newCachedThreadPool(runnable -> {
                Thread thread = new Thread(runnable, "mongo-recovery-proxy");
                thread.setDaemon(true);
                return thread;
            });
            executor.submit(this::acceptConnections);
        }

        static DelayedTcpProxy create() {
            try {
                ServerSocket serverSocket = new ServerSocket();
                serverSocket.bind(new InetSocketAddress("127.0.0.1", 0));
                return new DelayedTcpProxy(serverSocket);
            } catch (IOException exception) {
                throw new IllegalStateException("Unable to create delayed Mongo proxy", exception);
            }
        }

        int port() {
            return serverSocket.getLocalPort();
        }

        void forwardTo(InetSocketAddress target) {
            this.target = target;
        }

        private void acceptConnections() {
            while (!serverSocket.isClosed()) {
                try {
                    Socket client = serverSocket.accept();
                    InetSocketAddress currentTarget = target;
                    if (currentTarget == null) {
                        client.close();
                        continue;
                    }
                    Socket upstream = new Socket();
                    upstream.connect(currentTarget, 1000);
                    sockets.add(client);
                    sockets.add(upstream);
                    executor.submit(() -> relay(client, upstream));
                    executor.submit(() -> relay(upstream, client));
                } catch (IOException exception) {
                    if (!serverSocket.isClosed()) {
                        closeOpenSockets();
                    }
                }
            }
        }

        private void relay(Socket source, Socket destination) {
            try (InputStream input = source.getInputStream();
                    OutputStream output = destination.getOutputStream()) {
                input.transferTo(output);
            } catch (IOException ignored) {
                // Connection churn is expected while the Mongo driver recovers.
            } finally {
                closeSocket(source);
                closeSocket(destination);
            }
        }

        @Override
        public void close() {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
                // Already closed.
            }
            closeOpenSockets();
            executor.shutdownNow();
        }

        private void closeOpenSockets() {
            for (Socket socket : sockets) {
                closeSocket(socket);
            }
        }

        private void closeSocket(Socket socket) {
            sockets.remove(socket);
            try {
                socket.close();
            } catch (IOException ignored) {
                // Already closed.
            }
        }
    }
}
