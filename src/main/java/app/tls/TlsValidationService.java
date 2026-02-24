package app.tls;

import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
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
import java.util.function.Consumer;

public class TlsValidationService {
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 5000;

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
}
