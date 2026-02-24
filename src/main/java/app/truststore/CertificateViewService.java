package app.truststore;

import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;

public class CertificateViewService {
    public List<CertificateInfo> listCertificates(KeyStore keyStore) throws Exception {
        List<CertificateInfo> result = new ArrayList<>();
        Enumeration<String> aliases = keyStore.aliases();
        while (aliases.hasMoreElements()) {
            String alias = aliases.nextElement();
            Certificate certificate = keyStore.getCertificate(alias);
            if (certificate instanceof X509Certificate x509) {
                Instant notBefore = x509.getNotBefore().toInstant();
                Instant notAfter = x509.getNotAfter().toInstant();
                result.add(new CertificateInfo(
                    alias,
                    x509.getSubjectX500Principal().getName(),
                    x509.getIssuerX500Principal().getName(),
                    x509.getSerialNumber().toString(16),
                    notBefore,
                    notAfter,
                    resolveStatus(notAfter),
                    extractAltNames(x509)
                ));
            }
        }
        result.sort(Comparator.comparing(CertificateInfo::notAfter));
        return result;
    }

    private String resolveStatus(Instant notAfter) {
        Instant now = Instant.now();
        if (notAfter.isBefore(now)) {
            return "expired";
        }
        long daysLeft = Duration.between(now, notAfter).toDays();
        if (daysLeft <= 30) {
            return "expiringSoon";
        }
        return "valid";
    }

    private List<String> extractAltNames(X509Certificate certificate) {
        try {
            Collection<List<?>> sanEntries = certificate.getSubjectAlternativeNames();
            if (sanEntries == null || sanEntries.isEmpty()) {
                return List.of();
            }
            List<String> result = new ArrayList<>();
            for (List<?> entry : sanEntries) {
                if (entry == null || entry.size() < 2) {
                    continue;
                }
                Object typeObj = entry.get(0);
                Object valueObj = entry.get(1);
                String type = typeObj == null ? "unknown" : typeObj.toString();
                String value = valueObj == null ? "" : valueObj.toString();
                result.add(type + ": " + value);
            }
            return result;
        } catch (Exception ignored) {
            return List.of();
        }
    }
}
