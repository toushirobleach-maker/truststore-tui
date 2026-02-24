package app.tls;

import java.util.List;

public record AliasScanResult(
    List<String> validAliases,
    int checkedAliases,
    int failedAliases,
    String error
) {
}
