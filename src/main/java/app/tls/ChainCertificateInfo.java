package app.tls;

import java.time.Instant;

public record ChainCertificateInfo(String subject, String issuer, Instant notAfter) {
}
