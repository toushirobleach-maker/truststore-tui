package app.tls;

import java.util.List;

public record ValidationResult(boolean success, String message, List<ChainCertificateInfo> peerChain) {
}
