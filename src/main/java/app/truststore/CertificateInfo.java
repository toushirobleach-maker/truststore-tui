package app.truststore;

import java.time.Instant;
import java.util.List;

public record CertificateInfo(
    String alias,
    String subject,
    String issuer,
    String serialNumberHex,
    Instant notBefore,
    Instant notAfter,
    String status,
    List<String> altNames
) {
}
