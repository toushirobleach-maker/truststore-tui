package app.truststore;

import java.security.KeyStore;

public record LoadedTruststore(KeyStore keyStore, String sourceDescription, String storeType) {
}
