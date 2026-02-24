package app.tls;

public record AliasScanProgress(
    int checkedAliases,
    int totalAliases,
    String currentAlias,
    int validAliases
) {
}
