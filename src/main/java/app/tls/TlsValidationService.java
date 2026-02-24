package app.tls;

import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.UnrecoverableKeyException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.util.function.Consumer;

public class TlsValidationService {
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 5000;
    public static final String PKCS12_PASSWORD_REQUIRED_PREFIX = "PKCS12_PASSWORD_REQUIRED:";

    public ValidationResult validate(String host, int port, KeyStore sourceStore, String alias) {
        try {
            KeyStore effectiveStore = alias == null || alias.isBlank()
                ? sourceStore
                : singleAliasStore(sourceStore, alias);

            SSLContext context = SSLContext.getInstance("TLS");
            TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm()
            );
            trustManagerFactory.init(effectiveStore);
            context.init(null, trustManagerFactory.getTrustManagers(), null);

            try (SSLSocket socket = (SSLSocket) context.getSocketFactory().createSocket()) {
                socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
                socket.setSoTimeout(READ_TIMEOUT_MS);
                socket.startHandshake();
                SSLSession session = socket.getSession();
                List<ChainCertificateInfo> peerChain = extractPeerChain(session);
                String mode = alias == null || alias.isBlank() ? "full truststore" : "alias=" + alias;
                return new ValidationResult(true, "TLS validation succeeded using " + mode, peerChain);
            }
        } catch (UnknownHostException e) {
            return new ValidationResult(
                false,
                "TLS validation failed: host is unreachable or DNS name is invalid (" + host + ")",
                List.of()
            );
        } catch (ConnectException e) {
            return new ValidationResult(
                false,
                "TLS validation failed: unable to reach " + host + ":" + port + " (connection refused/unreachable)",
                List.of()
            );
        } catch (SocketTimeoutException e) {
            return new ValidationResult(
                false,
                "TLS validation failed: network timeout while connecting or during TLS handshake to " + host + ":" + port,
                List.of()
            );
        } catch (SSLHandshakeException e) {
            return new ValidationResult(
                false,
                "TLS validation failed: connected to server, but certificate validation failed (" + safeMessage(e) + ")",
                List.of()
            );
        } catch (SSLException e) {
            return new ValidationResult(
                false,
                "TLS validation failed: connected to server, but TLS negotiation failed (" + safeMessage(e) + ")",
                List.of()
            );
        } catch (IllegalArgumentException e) {
            return new ValidationResult(false, "TLS validation failed: " + safeMessage(e), List.of());
        } catch (Exception e) {
            return new ValidationResult(false, "TLS validation failed: unexpected error (" + safeMessage(e) + ")", List.of());
        }
    }

    public ValidationResult validateCertificateFile(String certificatePath, KeyStore sourceStore, String alias) {
        return validateCertificateFile(certificatePath, sourceStore, alias, null);
    }

    public ValidationResult validateCertificateFile(
        String certificatePath,
        KeyStore sourceStore,
        String alias,
        String pkcs12Password
    ) {
        if (certificatePath == null || certificatePath.isBlank()) {
            return new ValidationResult(false, "Certificate file path is required", List.of());
        }
        try {
            KeyStore effectiveStore = alias == null || alias.isBlank()
                ? sourceStore
                : singleAliasStore(sourceStore, alias);
            Path path = Path.of(certificatePath.trim());
            if (!Files.exists(path)) {
                return new ValidationResult(false, "Certificate file is not found: " + path, List.of());
            }
            if (!Files.isRegularFile(path)) {
                return new ValidationResult(false, "Certificate path is not a file: " + path, List.of());
            }

            List<X509Certificate> certificates = parseX509FromFile(path, pkcs12Password);
            if (certificates.isEmpty()) {
                return new ValidationResult(false, "No X.509 certificates found in file: " + path, List.of());
            }

            X509TrustManager trustManager = buildTrustManager(effectiveStore);
            ValidationAttempt bestAttempt = null;
            for (X509Certificate candidateLeaf : certificates) {
                X509Certificate[] chain = buildCandidateChain(candidateLeaf, certificates);
                Exception validationError = validateChainAgainstTrustManager(trustManager, chain, candidateLeaf);
                if (validationError == null) {
                    String mode = alias == null || alias.isBlank() ? "full truststore" : "alias=" + alias;
                    return new ValidationResult(
                        true,
                        "Certificate is trusted using " + mode + " (subject=" + candidateLeaf.getSubjectX500Principal().getName() + ")",
                        toChainInfo(chain)
                    );
                }
                if (validationError instanceof CertificateException certError) {
                    bestAttempt = new ValidationAttempt(chain, certError);
                } else {
                    String errorMessage = validationError == null ? "unknown validation error" : safeMessage(validationError);
                    return new ValidationResult(
                        false,
                        "Certificate validation failed: " + errorMessage,
                        toChainInfo(chain)
                    );
                }
            }

            if (bestAttempt == null) {
                return new ValidationResult(false, "Certificate is not trusted: no valid validation attempt", toChainInfo(certificates));
            }
            return new ValidationResult(
                false,
                "Certificate is not trusted (" + safeMessage(bestAttempt.error()) + ")",
                toChainInfo(bestAttempt.chain())
            );
        } catch (IllegalArgumentException e) {
            String message = safeMessage(e);
            if (message.startsWith(PKCS12_PASSWORD_REQUIRED_PREFIX)) {
                return new ValidationResult(false, message, List.of());
            }
            return new ValidationResult(false, "Certificate validation failed: " + message, List.of());
        } catch (Exception e) {
            return new ValidationResult(
                false,
                "Certificate validation failed: unexpected error (" + safeMessage(e) + ")",
                List.of()
            );
        }
    }

    private KeyStore singleAliasStore(KeyStore sourceStore, String alias) throws Exception {
        if (!sourceStore.containsAlias(alias)) {
            throw new IllegalArgumentException("Alias not found: " + alias);
        }
        Certificate cert = sourceStore.getCertificate(alias);
        if (cert == null) {
            throw new IllegalArgumentException("No certificate found for alias: " + alias);
        }
        KeyStore single = KeyStore.getInstance(KeyStore.getDefaultType());
        single.load(null, null);
        single.setCertificateEntry(alias, cert);
        return single;
    }

    private List<ChainCertificateInfo> extractPeerChain(SSLSession session) throws SSLPeerUnverifiedException {
        Certificate[] peerCertificates = session.getPeerCertificates();
        List<ChainCertificateInfo> chain = new ArrayList<>();
        for (Certificate cert : peerCertificates) {
            if (cert instanceof X509Certificate x509) {
                chain.add(new ChainCertificateInfo(
                    x509.getSubjectX500Principal().getName(),
                    x509.getIssuerX500Principal().getName(),
                    x509.getNotAfter().toInstant()
                ));
            }
        }
        return chain;
    }

    private X509TrustManager buildTrustManager(KeyStore trustStore) throws Exception {
        TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(
            TrustManagerFactory.getDefaultAlgorithm()
        );
        trustManagerFactory.init(trustStore);
        for (var manager : trustManagerFactory.getTrustManagers()) {
            if (manager instanceof X509TrustManager x509TrustManager) {
                return x509TrustManager;
            }
        }
        throw new IllegalStateException("X509TrustManager is not available");
    }

    private List<X509Certificate> parseX509FromFile(Path path, String pkcs12Password) throws Exception {
        byte[] bytes = Files.readAllBytes(path);
        try {
            List<X509Certificate> parsed = parseAsCertificateFactory(bytes);
            if (!parsed.isEmpty()) {
                return parsed;
            }
        } catch (CertificateException ignored) {
            // Fallback to certificate containers (PKCS12/JKS).
        }
        List<X509Certificate> parsed = parseFromPkcs12(bytes, path, pkcs12Password);
        if (!parsed.isEmpty()) {
            return parsed;
        }
        return parseFromKeystoreContainer(bytes, "JKS", path);
    }

    private List<X509Certificate> parseAsCertificateFactory(byte[] bytes) throws CertificateException {
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        Collection<? extends Certificate> generated = factory.generateCertificates(new java.io.ByteArrayInputStream(bytes));
        List<X509Certificate> certificates = new ArrayList<>();
        for (Certificate cert : generated) {
            if (cert instanceof X509Certificate x509) {
                certificates.add(x509);
            }
        }
        if (!certificates.isEmpty()) {
            return certificates;
        }
        Certificate single = factory.generateCertificate(new java.io.ByteArrayInputStream(bytes));
        if (single instanceof X509Certificate x509) {
            certificates.add(x509);
        }
        return certificates;
    }

    private List<X509Certificate> parseFromKeystoreContainer(byte[] bytes, String type, Path path) {
        List<X509Certificate> result = new ArrayList<>();
        List<char[]> candidatePasswords = List.of(
            new char[0],
            "changeit".toCharArray()
        );
        for (char[] password : candidatePasswords) {
            try {
                KeyStore keyStore = KeyStore.getInstance(type);
                keyStore.load(new java.io.ByteArrayInputStream(bytes), password);
                return extractAllX509FromStore(keyStore);
            } catch (Exception ignored) {
                // Continue trying common passwords for certificate containers.
            }
        }
        if (type.equalsIgnoreCase("PKCS12")) {
            String fileName = path.getFileName() == null ? "" : path.getFileName().toString().toLowerCase();
            if (fileName.endsWith(".p12") || fileName.endsWith(".pfx")) {
                throw new IllegalArgumentException(
                    "PKCS12 container is detected, but it cannot be opened. Try exporting certificate as PEM/CRT/DER."
                );
            }
        }
        return result;
    }

    private List<X509Certificate> parseFromPkcs12(byte[] bytes, Path path, String providedPassword) {
        List<char[]> candidatePasswords = new ArrayList<>();
        if (providedPassword != null) {
            candidatePasswords.add(providedPassword.toCharArray());
        } else {
            candidatePasswords.add(new char[0]);
            candidatePasswords.add("changeit".toCharArray());
        }

        Exception lastError = null;
        for (char[] password : candidatePasswords) {
            try {
                KeyStore keyStore = KeyStore.getInstance("PKCS12");
                keyStore.load(new java.io.ByteArrayInputStream(bytes), password);
                return extractAllX509FromStore(keyStore);
            } catch (Exception e) {
                lastError = e;
            }
        }

        if (isLikelyPkcs12(path, lastError)) {
            if (providedPassword == null) {
                throw new IllegalArgumentException(
                    PKCS12_PASSWORD_REQUIRED_PREFIX
                        + " PKCS12 container is detected. Enter password and retry."
                );
            }
            throw new IllegalArgumentException(
                "PKCS12 container is detected, but password is incorrect or file is corrupted."
            );
        }
        return List.of();
    }

    private boolean isLikelyPkcs12(Path path, Exception error) {
        String fileName = path.getFileName() == null ? "" : path.getFileName().toString().toLowerCase();
        if (fileName.endsWith(".p12") || fileName.endsWith(".pfx")) {
            return true;
        }
        String message = safeMessage(error).toLowerCase();
        return message.contains("keystore password was incorrect")
            || message.contains("mac invalid")
            || message.contains("failed to decrypt")
            || message.contains("unrecoverablekeyexception")
            || error instanceof UnrecoverableKeyException;
    }

    private Exception validateChainAgainstTrustManager(
        X509TrustManager trustManager,
        X509Certificate[] chain,
        X509Certificate leaf
    ) {
        Exception lastError = null;
        for (String authType : candidateAuthTypes(leaf)) {
            try {
                trustManager.checkServerTrusted(chain, authType);
                return null;
            } catch (CertificateException | IllegalArgumentException e) {
                lastError = e;
            }
        }
        return lastError;
    }

    private List<String> candidateAuthTypes(X509Certificate leaf) {
        LinkedHashSet<String> authTypes = new LinkedHashSet<>();
        String keyAlgorithm = leaf.getPublicKey().getAlgorithm();
        if (keyAlgorithm != null && !keyAlgorithm.isBlank()) {
            authTypes.add(keyAlgorithm.toUpperCase());
        }
        if ("EC".equalsIgnoreCase(keyAlgorithm) || "ECDSA".equalsIgnoreCase(keyAlgorithm)) {
            authTypes.add("ECDHE_ECDSA");
            authTypes.add("ECDSA");
        } else if ("RSA".equalsIgnoreCase(keyAlgorithm)) {
            authTypes.add("ECDHE_RSA");
            authTypes.add("RSA");
        } else if ("DSA".equalsIgnoreCase(keyAlgorithm)) {
            authTypes.add("DHE_DSS");
            authTypes.add("DSA");
        }
        authTypes.add("UNKNOWN");
        return new ArrayList<>(authTypes);
    }


    private List<X509Certificate> extractAllX509FromStore(KeyStore keyStore) throws KeyStoreException {
        List<X509Certificate> certificates = new ArrayList<>();
        Enumeration<String> aliases = keyStore.aliases();
        while (aliases.hasMoreElements()) {
            String alias = aliases.nextElement();
            Certificate cert = keyStore.getCertificate(alias);
            if (cert instanceof X509Certificate x509) {
                certificates.add(x509);
            }
            Certificate[] chain = keyStore.getCertificateChain(alias);
            if (chain == null) {
                continue;
            }
            for (Certificate chainCert : chain) {
                if (chainCert instanceof X509Certificate x509) {
                    certificates.add(x509);
                }
            }
        }
        return certificates;
    }

    private X509Certificate[] buildCandidateChain(X509Certificate leaf, List<X509Certificate> certificates) {
        List<X509Certificate> chain = new ArrayList<>();
        chain.add(leaf);
        X509Certificate current = leaf;
        while (true) {
            X509Certificate next = findIssuer(current, certificates);
            if (next == null || chain.contains(next)) {
                break;
            }
            chain.add(next);
            if (isSelfSigned(next)) {
                break;
            }
            current = next;
        }
        return chain.toArray(X509Certificate[]::new);
    }

    private X509Certificate findIssuer(X509Certificate certificate, List<X509Certificate> pool) {
        for (X509Certificate candidate : pool) {
            if (candidate.equals(certificate)) {
                continue;
            }
            if (certificate.getIssuerX500Principal().equals(candidate.getSubjectX500Principal())) {
                return candidate;
            }
        }
        return null;
    }

    private boolean isSelfSigned(X509Certificate certificate) {
        return certificate.getIssuerX500Principal().equals(certificate.getSubjectX500Principal());
    }

    private List<ChainCertificateInfo> toChainInfo(List<X509Certificate> certificates) {
        List<ChainCertificateInfo> chain = new ArrayList<>();
        for (X509Certificate certificate : certificates) {
            chain.add(toChainInfo(certificate));
        }
        return chain;
    }

    private List<ChainCertificateInfo> toChainInfo(X509Certificate[] certificates) {
        List<ChainCertificateInfo> chain = new ArrayList<>();
        for (X509Certificate certificate : certificates) {
            chain.add(toChainInfo(certificate));
        }
        return chain;
    }

    private ChainCertificateInfo toChainInfo(X509Certificate certificate) {
        return new ChainCertificateInfo(
            certificate.getSubjectX500Principal().getName(),
            certificate.getIssuerX500Principal().getName(),
            certificate.getNotAfter().toInstant()
        );
    }

    public AliasScanResult findValidAliases(String host, int port, KeyStore sourceStore) {
        return findValidAliases(host, port, sourceStore, null);
    }

    public AliasScanResult findValidAliases(
        String host,
        int port,
        KeyStore sourceStore,
        Consumer<AliasScanProgress> progressCallback
    ) {
        List<String> validAliases = new ArrayList<>();
        int checkedAliases = 0;
        int failedAliases = 0;
        try {
            int totalAliases = countAliasesWithCertificates(sourceStore);
            Enumeration<String> aliases = sourceStore.aliases();
            while (aliases.hasMoreElements()) {
                String alias = aliases.nextElement();
                Certificate cert = sourceStore.getCertificate(alias);
                if (cert == null) {
                    continue;
                }
                checkedAliases++;
                ValidationResult result = validate(host, port, sourceStore, alias);
                if (result.success()) {
                    validAliases.add(alias);
                } else {
                    failedAliases++;
                }
                if (progressCallback != null) {
                    progressCallback.accept(new AliasScanProgress(
                        checkedAliases,
                        totalAliases,
                        alias,
                        validAliases.size()
                    ));
                }
            }
            return new AliasScanResult(validAliases, checkedAliases, failedAliases, null);
        } catch (Exception e) {
            return new AliasScanResult(validAliases, checkedAliases, failedAliases, e.getMessage());
        }
    }

    public AliasScanResult findValidAliasesForCertificateFile(
        String certificatePath,
        KeyStore sourceStore,
        String pkcs12Password,
        Consumer<AliasScanProgress> progressCallback
    ) {
        List<String> validAliases = new ArrayList<>();
        int checkedAliases = 0;
        int failedAliases = 0;
        try {
            int totalAliases = countAliasesWithCertificates(sourceStore);
            Enumeration<String> aliases = sourceStore.aliases();
            while (aliases.hasMoreElements()) {
                String alias = aliases.nextElement();
                Certificate cert = sourceStore.getCertificate(alias);
                if (cert == null) {
                    continue;
                }
                checkedAliases++;
                ValidationResult result = validateCertificateFile(certificatePath, sourceStore, alias, pkcs12Password);
                if (result.success()) {
                    validAliases.add(alias);
                } else {
                    failedAliases++;
                }
                if (progressCallback != null) {
                    progressCallback.accept(new AliasScanProgress(
                        checkedAliases,
                        totalAliases,
                        alias,
                        validAliases.size()
                    ));
                }
            }
            return new AliasScanResult(validAliases, checkedAliases, failedAliases, null);
        } catch (Exception e) {
            return new AliasScanResult(validAliases, checkedAliases, failedAliases, safeMessage(e));
        }
    }

    private String safeMessage(Throwable t) {
        if (t == null || t.getMessage() == null || t.getMessage().isBlank()) {
            return t == null ? "unknown error" : t.getClass().getSimpleName();
        }
        return t.getMessage();
    }

    private int countAliasesWithCertificates(KeyStore sourceStore) throws Exception {
        int count = 0;
        Enumeration<String> aliases = sourceStore.aliases();
        while (aliases.hasMoreElements()) {
            String alias = aliases.nextElement();
            if (sourceStore.getCertificate(alias) != null) {
                count++;
            }
        }
        return count;
    }

    private record ValidationAttempt(X509Certificate[] chain, CertificateException error) {
    }
}
