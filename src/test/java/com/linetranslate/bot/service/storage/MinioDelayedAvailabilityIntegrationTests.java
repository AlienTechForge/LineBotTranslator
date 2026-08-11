package com.linetranslate.bot.service.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import io.minio.MinioClient;
import io.minio.RemoveBucketArgs;
import io.minio.RemoveObjectArgs;

@EnabledIfEnvironmentVariable(named = "MINIO_RECOVERY_UPSTREAM_ENDPOINT", matches = ".+")
class MinioDelayedAvailabilityIntegrationTests {

    @Test
    void unavailableStorageRecoversAndUploadsWithoutRecreatingTheService() throws Exception {
        String upstreamEndpoint = System.getenv("MINIO_RECOVERY_UPSTREAM_ENDPOINT");
        String accessKey = System.getenv().getOrDefault("MINIO_ACCESS_KEY", "linebot-test");
        String secretKey = System.getenv().getOrDefault("MINIO_SECRET_KEY", "linebot-test-secret");
        String bucketName = "linebot-recovery-" + UUID.randomUUID().toString().replace("-", "");
        AtomicLong clock = new AtomicLong(1_000);

        try (DelayedTcpProxy proxy = DelayedTcpProxy.create()) {
            MinioClient proxiedClient = client(
                    "http://127.0.0.1:" + proxy.port(), accessKey, secretKey);
            MinioClient publicUrlSigner = client(
                    "https://s3.example.com", accessKey, secretKey);
            MinioStorageService service = new MinioStorageService(
                    proxiedClient,
                    publicUrlSigner,
                    bucketName,
                    true,
                    1_000,
                    clock::get);

            ImageStorageResult unavailable = service.uploadImage(new byte[] {1}, "image/jpeg");
            int connectionsAfterFailure = proxy.acceptedConnections();
            ImageStorageResult suppressed = service.uploadImage(new byte[] {2}, "image/jpeg");

            assertThat(unavailable.stored()).isFalse();
            assertThat(suppressed.stored()).isFalse();
            assertThat(proxy.acceptedConnections()).isEqualTo(connectionsAfterFailure);

            proxy.forwardTo(address(upstreamEndpoint));
            clock.addAndGet(1_001);
            ImageStorageResult recovered = service.uploadImage(new byte[] {3, 4}, "image/png");

            assertThat(recovered.stored()).isTrue();
            assertThat(recovered.url()).isPresent();
            assertThat(URI.create(recovered.url().orElseThrow()).getHost())
                    .isEqualTo("s3.example.com");
            assertThat(service.isAvailable()).isTrue();

            removeUploadedObject(upstreamEndpoint, accessKey, secretKey, bucketName, recovered);
        }
    }

    private static MinioClient client(String endpoint, String accessKey, String secretKey) {
        MinioClient client = MinioClient.builder()
                .endpoint(endpoint)
                .region("us-east-1")
                .credentials(accessKey, secretKey)
                .build();
        client.setTimeout(300, 1_000, 1_000);
        return client;
    }

    private static void removeUploadedObject(
            String upstreamEndpoint,
            String accessKey,
            String secretKey,
            String bucketName,
            ImageStorageResult result) throws Exception {
        URI signedUrl = URI.create(result.url().orElseThrow());
        String prefix = "/" + bucketName + "/";
        String objectName = signedUrl.getPath().substring(prefix.length());
        MinioClient cleanupClient = client(upstreamEndpoint, accessKey, secretKey);
        cleanupClient.removeObject(
                RemoveObjectArgs.builder().bucket(bucketName).object(objectName).build());
        cleanupClient.removeBucket(RemoveBucketArgs.builder().bucket(bucketName).build());
    }

    private static InetSocketAddress address(String endpoint) {
        URI uri = URI.create(endpoint);
        int port = uri.getPort() >= 0 ? uri.getPort() : 80;
        return new InetSocketAddress(uri.getHost(), port);
    }

    private static final class DelayedTcpProxy implements AutoCloseable {

        private final ServerSocket serverSocket;
        private final ExecutorService executor;
        private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
        private final AtomicInteger acceptedConnections = new AtomicInteger();
        private volatile InetSocketAddress target;

        private DelayedTcpProxy(ServerSocket serverSocket) {
            this.serverSocket = serverSocket;
            this.executor = Executors.newCachedThreadPool(runnable -> {
                Thread thread = new Thread(runnable, "minio-recovery-proxy");
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
                throw new IllegalStateException("Unable to create delayed MinIO proxy", exception);
            }
        }

        int port() {
            return serverSocket.getLocalPort();
        }

        int acceptedConnections() {
            return acceptedConnections.get();
        }

        void forwardTo(InetSocketAddress target) {
            this.target = target;
        }

        private void acceptConnections() {
            while (!serverSocket.isClosed()) {
                try {
                    Socket client = serverSocket.accept();
                    acceptedConnections.incrementAndGet();
                    InetSocketAddress currentTarget = target;
                    if (currentTarget == null) {
                        client.close();
                        continue;
                    }
                    Socket upstream = new Socket();
                    upstream.connect(currentTarget, 1_000);
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
                // Connection churn is expected while availability changes.
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
