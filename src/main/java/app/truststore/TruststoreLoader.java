package app.truststore;

import app.util.TarGzExtractor;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.List;

public class TruststoreLoader {
    private static final List<String> SUPPORTED_TYPES = List.of("PKCS12", "JKS");

    public LoadedTruststore load(StoreSourceType sourceType, String sourceValue, char[] password) throws Exception {
        if (password == null) {
            throw new IllegalArgumentException("Password must not be null");
        }
        if (sourceValue == null || sourceValue.isBlank()) {
            throw new IllegalArgumentException("Source value must not be empty");
        }

        byte[] storeBytes = switch (sourceType) {
            case FILE -> readStoreFile(sourceValue);
            case URL_TAR_GZ -> downloadFromUrl(sourceValue);
        };

        Exception lastLoadError = null;
        for (String type : SUPPORTED_TYPES) {
            try {
                KeyStore keyStore = KeyStore.getInstance(type);
                try (ByteArrayInputStream in = new ByteArrayInputStream(storeBytes)) {
                    keyStore.load(in, password);
                }
                return new LoadedTruststore(keyStore, sourceValue, type);
            } catch (Exception e) {
                lastLoadError = e;
            }
        }

        String details = lastLoadError == null || lastLoadError.getMessage() == null || lastLoadError.getMessage().isBlank()
            ? "unknown reason"
            : lastLoadError.getMessage();
        throw new IllegalArgumentException(
            "Failed to load truststore. Check password and store format (JKS/PKCS12). Details: " + details
        );
    }

    private byte[] readStoreFile(String path) throws IOException {
        Path sourcePath = Path.of(path);
        byte[] bytes = Files.readAllBytes(sourcePath);
        String normalized = sourcePath.getFileName() == null
            ? path.toLowerCase()
            : sourcePath.getFileName().toString().toLowerCase();
        if (normalized.endsWith(".tar.gz") || normalized.endsWith(".tgz")) {
            return TarGzExtractor.extractSingleFile(bytes);
        }
        return bytes;
    }

    private byte[] downloadFromUrl(String url) throws Exception {
        URI uri = validateHttpUrl(url);

        HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build();
        HttpRequest request = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(30))
            .GET()
            .build();
        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Failed to download truststore archive from URL. HTTP " + response.statusCode());
        }
        byte[] body = response.body();
        if (shouldTreatAsTarGz(uri, response, body)) {
            return TarGzExtractor.extractSingleFile(body);
        }
        return body;
    }

    private URI validateHttpUrl(String rawUrl) {
        URI uri;
        try {
            uri = URI.create(rawUrl == null ? "" : rawUrl.trim());
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid URL syntax: " + rawUrl);
        }
        if (uri.getScheme() == null || uri.getScheme().isBlank()) {
            throw new IllegalArgumentException("Invalid URL: missing scheme. Use full URL like https://host/path.tar.gz");
        }
        if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("Invalid URL scheme: " + uri.getScheme() + ". Only http/https supported");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("Invalid URL: missing host. Use FQDN or resolvable host with scheme");
        }
        return uri;
    }

    private boolean shouldTreatAsTarGz(URI uri, HttpResponse<byte[]> response, byte[] body) {
        String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase();
        if (path.endsWith(".tar.gz") || path.endsWith(".tgz")) {
            return true;
        }
        String contentType = response.headers().firstValue("Content-Type").orElse("").toLowerCase();
        if (contentType.contains("gzip") || contentType.contains("x-gzip") || contentType.contains("x-tar")) {
            return true;
        }
        return body != null
            && body.length >= 2
            && (body[0] & 0xFF) == 0x1F
            && (body[1] & 0xFF) == 0x8B;
    }
}
