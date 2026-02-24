package app.ui;

import app.tls.AliasScanProgress;
import app.tls.AliasScanResult;
import app.tls.ChainCertificateInfo;
import app.tls.TlsValidationService;
import app.tls.ValidationResult;
import app.truststore.CertificateInfo;
import app.truststore.CertificateViewService;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.gui2.BasicWindow;
import com.googlecode.lanterna.gui2.Borders;
import com.googlecode.lanterna.gui2.Button;
import com.googlecode.lanterna.gui2.CheckBox;
import com.googlecode.lanterna.gui2.Direction;
import com.googlecode.lanterna.gui2.GridLayout;
import com.googlecode.lanterna.gui2.Label;
import com.googlecode.lanterna.gui2.LinearLayout;
import com.googlecode.lanterna.gui2.MultiWindowTextGUI;
import com.googlecode.lanterna.gui2.Panel;
import com.googlecode.lanterna.gui2.table.Table;
import com.googlecode.lanterna.gui2.Window;
import com.googlecode.lanterna.gui2.TextBox;
import com.googlecode.lanterna.gui2.dialogs.MessageDialogBuilder;
import com.googlecode.lanterna.gui2.dialogs.MessageDialog;
import com.googlecode.lanterna.gui2.dialogs.MessageDialogButton;
import com.googlecode.lanterna.gui2.dialogs.TextInputDialogBuilder;
import java.security.KeyStore;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class TlsCheckDialog {
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        .withZone(ZoneId.systemDefault());

    private TlsCheckDialog() {
    }

    public static void show(
        MultiWindowTextGUI gui,
        KeyStore keyStore,
        String selectedAlias,
        TlsValidationService validationService
    ) {
        showInternal(gui, keyStore, selectedAlias, validationService, false);
    }

    public static void showSingleAlias(
        MultiWindowTextGUI gui,
        KeyStore keyStore,
        String selectedAlias,
        TlsValidationService validationService
    ) {
        showInternal(gui, keyStore, selectedAlias, validationService, true);
    }

    private static void showInternal(
        MultiWindowTextGUI gui,
        KeyStore keyStore,
        String selectedAlias,
        TlsValidationService validationService,
        boolean aliasOnlyMode
    ) {
        BasicWindow dialog = ModalWindows.escClosable("TLS Check");
        dialog.setHints(java.util.List.of(BasicWindow.Hint.MODAL));

        Panel root = new Panel(new GridLayout(2));
        TextBox hostInput = new TextBox(new TerminalSize(48, 1));
        TextBox portInput = new TextBox("443");
        TextBox certificatePathInput = new TextBox(new TerminalSize(48, 1));
        Label resultLabel = new Label("");
        CheckBox findMatchingCerts = new CheckBox("Find matching certificates");

        root.addComponent(new Label("Host"));
        root.addComponent(hostInput);
        root.addComponent(new Label("Port"));
        root.addComponent(portInput);
        root.addComponent(new Label("Certificate file"));
        Panel certificatePathRow = new Panel(new LinearLayout(Direction.HORIZONTAL));
        certificatePathRow.addComponent(certificatePathInput);
        certificatePathRow.addComponent(new Button("Browse...", () -> {
            String selectedPath = FileSystemPickerDialog.show(gui, certificatePathInput.getText());
            if (selectedPath != null && !selectedPath.isBlank()) {
                certificatePathInput.setText(selectedPath);
            }
        }));
        root.addComponent(certificatePathRow);
        if (aliasOnlyMode) {
            root.addComponent(new Label("Mode"));
            root.addComponent(new Label("selected alias: " + selectedAlias));
        } else {
            root.addComponent(new Label("Options"));
            root.addComponent(findMatchingCerts);
        }

        Panel actions = new Panel(new LinearLayout(Direction.HORIZONTAL));
        actions.addComponent(new Button("Run check", () -> {
            String host = hostInput.getText().trim();
            Integer port = parsePort(host, portInput.getText().trim(), resultLabel);
            if (port == null) {
                return;
            }

            boolean needAliasScan = !aliasOnlyMode && findMatchingCerts.isChecked();
            if (!needAliasScan) {
                String alias = aliasOnlyMode ? selectedAlias : null;
                ValidationResult result = validationService.validate(host, port, keyStore, alias);
                resultLabel.setText(result.success() ? "OK" : "FAIL");
                showDetails(gui, result, host, port, null);
                return;
            }

            resultLabel.setText("Checking aliases: 0/?");
            Thread worker = new Thread(() -> {
                ValidationResult result = validationService.validate(host, port, keyStore, null);
                AliasScanResult scanResult = validationService.findValidAliases(host, port, keyStore, progress -> {
                    gui.getGUIThread().invokeLater(() -> showProgress(resultLabel, progress));
                });
                Map<String, CertificateInfo> aliasDetails = buildAliasDetailsMap(keyStore);
                gui.getGUIThread().invokeLater(() -> {
                    resultLabel.setText(
                        "Valid aliases: " + scanResult.validAliases().size() + "/" + scanResult.checkedAliases()
                    );
                    showResultWithAliasesDialog(
                        gui,
                        result,
                        host,
                        port,
                        scanResult,
                        aliasDetails,
                        keyStore,
                        validationService
                    );
                });
            }, "alias-scan-worker");
            worker.setDaemon(true);
            worker.start();
        }));
        actions.addComponent(new Button("Check cert file", () -> {
            String certificatePath = certificatePathInput.getText().trim();
            String password = null;
            ValidationResult baseResult = validationService.validateCertificateFile(certificatePath, keyStore, null);
            if (!baseResult.success() && isPkcs12PasswordRequired(baseResult.message())) {
                password = promptPkcs12Password(gui);
                if (password == null) {
                    resultLabel.setText("Cancelled");
                    return;
                }
                baseResult = validationService.validateCertificateFile(certificatePath, keyStore, null, password);
            }
            boolean needAliasScan = !aliasOnlyMode && findMatchingCerts.isChecked();
            if (!needAliasScan) {
                ValidationResult result;
                if (aliasOnlyMode) {
                    result = validationService.validateCertificateFile(certificatePath, keyStore, selectedAlias, password);
                } else {
                    result = baseResult;
                }
                resultLabel.setText(result.success() ? "OK" : "FAIL");
                showCertificateFileDetails(gui, result, certificatePath);
                return;
            }

            String finalPassword = password;
            ValidationResult finalBaseResult = baseResult;
            resultLabel.setText("Checking aliases: 0/?");
            Thread worker = new Thread(() -> {
                AliasScanResult scanResult = validationService.findValidAliasesForCertificateFile(
                    certificatePath,
                    keyStore,
                    finalPassword,
                    progress -> gui.getGUIThread().invokeLater(() -> showProgress(resultLabel, progress))
                );
                Map<String, CertificateInfo> aliasDetails = buildAliasDetailsMap(keyStore);
                gui.getGUIThread().invokeLater(() -> {
                    resultLabel.setText(
                        "Valid aliases: " + scanResult.validAliases().size() + "/" + scanResult.checkedAliases()
                    );
                    showCertificateFileResultWithAliasesDialog(
                        gui,
                        finalBaseResult,
                        certificatePath,
                        scanResult,
                        aliasDetails,
                        keyStore,
                        validationService
                    );
                });
            }, "cert-file-alias-scan-worker");
            worker.setDaemon(true);
            worker.start();
        }));
        actions.addComponent(new Button("Close", dialog::close));

        Panel wrapper = new Panel(new LinearLayout(Direction.VERTICAL));
        wrapper.addComponent(root.withBorder(Borders.singleLine("Request")));
        wrapper.addComponent(actions);
        wrapper.addComponent(resultLabel.withBorder(Borders.singleLine("Status")));

        dialog.setComponent(wrapper);
        gui.addWindowAndWait(dialog);
    }

    private static Integer parsePort(String host, String portText, Label resultLabel) {
        if (host == null || host.isBlank()) {
            resultLabel.setText("Host is required");
            return null;
        }
        try {
            return Integer.parseInt(portText);
        } catch (NumberFormatException ex) {
            resultLabel.setText("Port must be a valid number");
            return null;
        }
    }

    private static void showProgress(Label resultLabel, AliasScanProgress progress) {
        resultLabel.setText(
            "Checking aliases: " + progress.checkedAliases() + "/" + progress.totalAliases()
                + " (valid: " + progress.validAliases() + ")"
        );
    }

    private static Map<String, CertificateInfo> buildAliasDetailsMap(KeyStore keyStore) {
        try {
            List<CertificateInfo> certificates = new CertificateViewService().listCertificates(keyStore);
            Map<String, CertificateInfo> byAlias = new HashMap<>();
            for (CertificateInfo cert : certificates) {
                byAlias.put(cert.alias(), cert);
            }
            return byAlias;
        } catch (Exception e) {
            return new HashMap<>();
        }
    }

    private static void showMatchingAliasesDialog(
        MultiWindowTextGUI gui,
        AliasScanResult scanResult,
        Map<String, CertificateInfo> aliasDetails,
        KeyStore keyStore,
        TlsValidationService validationService
    ) {
        if (scanResult.validAliases().isEmpty()) {
            MessageDialog.showMessageDialog(gui, "Matching aliases", "No valid aliases found.", MessageDialogButton.OK);
            return;
        }
        BasicWindow dialog = ModalWindows.escClosable("Matching aliases");
        dialog.setHints(java.util.List.of(BasicWindow.Hint.MODAL));
        Table<String> table = new Table<>("Alias");
        for (String alias : scanResult.validAliases()) {
            table.getTableModel().addRow(alias);
        }
        table.setPreferredSize(new TerminalSize(64, 12));
        if (!scanResult.validAliases().isEmpty()) {
            table.setSelectedRow(0);
        }
        table.setSelectAction(() -> openAliasDetailsFromSelection(
            gui, table, scanResult, aliasDetails, keyStore, validationService
        ));

        Panel actions = new Panel(new LinearLayout(Direction.HORIZONTAL));
        actions.addComponent(new Button("View details", () -> {
            openAliasDetailsFromSelection(gui, table, scanResult, aliasDetails, keyStore, validationService);
        }));
        actions.addComponent(new Button("Close", dialog::close));

        Panel root = new Panel(new LinearLayout(Direction.VERTICAL));
        root.addComponent(table.withBorder(Borders.singleLine("Valid aliases")));
        root.addComponent(actions);
        dialog.setComponent(root);
        dialog.setFocusedInteractable(table);
        gui.addWindowAndWait(dialog);
    }

    private static void showResultWithAliasesDialog(
        MultiWindowTextGUI gui,
        ValidationResult result,
        String host,
        int port,
        AliasScanResult scanResult,
        Map<String, CertificateInfo> aliasDetails,
        KeyStore keyStore,
        TlsValidationService validationService
    ) {
        BasicWindow dialog = ModalWindows.escClosable("TLS Result");
        dialog.setHints(java.util.List.of(Window.Hint.MODAL));

        StringBuilder summary = new StringBuilder();
        summary.append(result.success() ? "OK\n" : "FAIL\n");
        summary.append(result.message()).append('\n');
        if (!result.peerChain().isEmpty()) {
            summary.append("\nPeer chain:\n");
            for (int i = 0; i < result.peerChain().size(); i++) {
                ChainCertificateInfo cert = result.peerChain().get(i);
                summary.append(i + 1)
                    .append(") subject=").append(cert.subject()).append('\n')
                    .append("   issuer=").append(cert.issuer()).append('\n')
                    .append("   notAfter=").append(DATE_FORMATTER.format(cert.notAfter())).append('\n');
            }
        }
        summary.append("\nMatching certificates for ").append(host).append(':').append(port).append('\n');
        summary.append("Checked: ").append(scanResult.checkedAliases()).append('\n');
        summary.append("Valid: ").append(scanResult.validAliases().size()).append('\n');
        summary.append("Failed: ").append(scanResult.failedAliases()).append('\n');
        if (scanResult.error() != null && !scanResult.error().isBlank()) {
            summary.append("Error: ").append(scanResult.error()).append('\n');
        }

        Panel root = new Panel(new LinearLayout(Direction.VERTICAL));
        root.addComponent(new Label(summary.toString()).withBorder(Borders.singleLine("Summary")));

        if (scanResult.validAliases().isEmpty()) {
            root.addComponent(new Label("No aliases validated successfully."));
            root.addComponent(new Button("Close", dialog::close));
            dialog.setComponent(root);
            gui.addWindowAndWait(dialog);
            return;
        }

        Table<String> table = new Table<>("Valid aliases");
        table.setPreferredSize(new TerminalSize(64, 12));
        for (String alias : scanResult.validAliases()) {
            table.getTableModel().addRow(alias);
        }
        table.setSelectedRow(0);
        table.setSelectAction(() -> openAliasDetailsFromSelection(
            gui, table, scanResult, aliasDetails, keyStore, validationService
        ));

        Panel actions = new Panel(new LinearLayout(Direction.HORIZONTAL));
        actions.addComponent(new Button("View details", () -> openAliasDetailsFromSelection(
            gui, table, scanResult, aliasDetails, keyStore, validationService
        )));
        actions.addComponent(new Button("Close", dialog::close));

        root.addComponent(table.withBorder(Borders.singleLine("Matching aliases")));
        root.addComponent(actions);
        dialog.setComponent(root);
        dialog.setFocusedInteractable(table);
        gui.addWindowAndWait(dialog);
    }

    private static void showCertificateFileResultWithAliasesDialog(
        MultiWindowTextGUI gui,
        ValidationResult result,
        String certificatePath,
        AliasScanResult scanResult,
        Map<String, CertificateInfo> aliasDetails,
        KeyStore keyStore,
        TlsValidationService validationService
    ) {
        BasicWindow dialog = ModalWindows.escClosable("Certificate File Result");
        dialog.setHints(java.util.List.of(Window.Hint.MODAL));

        StringBuilder summary = new StringBuilder();
        summary.append(result.success() ? "OK\n" : "FAIL\n");
        summary.append(normalizeCertificateValidationMessage(result.message())).append('\n');
        if (certificatePath != null && !certificatePath.isBlank()) {
            summary.append("File: ").append(certificatePath).append('\n');
        }
        summary.append("\nMatching certificates for file:\n");
        summary.append("Checked: ").append(scanResult.checkedAliases()).append('\n');
        summary.append("Valid: ").append(scanResult.validAliases().size()).append('\n');
        summary.append("Failed: ").append(scanResult.failedAliases()).append('\n');
        if (scanResult.error() != null && !scanResult.error().isBlank()) {
            summary.append("Error: ").append(scanResult.error()).append('\n');
        }

        Panel root = new Panel(new LinearLayout(Direction.VERTICAL));
        root.addComponent(new Label(summary.toString()).withBorder(Borders.singleLine("Summary")));

        if (scanResult.validAliases().isEmpty()) {
            root.addComponent(new Label("No aliases validated successfully."));
            root.addComponent(new Button("Close", dialog::close));
            dialog.setComponent(root);
            gui.addWindowAndWait(dialog);
            return;
        }

        Table<String> table = new Table<>("Valid aliases");
        table.setPreferredSize(new TerminalSize(64, 12));
        for (String alias : scanResult.validAliases()) {
            table.getTableModel().addRow(alias);
        }
        table.setSelectedRow(0);
        table.setSelectAction(() -> openAliasDetailsFromSelection(
            gui, table, scanResult, aliasDetails, keyStore, validationService
        ));

        Panel actions = new Panel(new LinearLayout(Direction.HORIZONTAL));
        actions.addComponent(new Button("View details", () -> openAliasDetailsFromSelection(
            gui, table, scanResult, aliasDetails, keyStore, validationService
        )));
        actions.addComponent(new Button("Close", dialog::close));

        root.addComponent(table.withBorder(Borders.singleLine("Matching aliases")));
        root.addComponent(actions);
        dialog.setComponent(root);
        dialog.setFocusedInteractable(table);
        gui.addWindowAndWait(dialog);
    }

    private static void openAliasDetailsFromSelection(
        MultiWindowTextGUI gui,
        Table<String> table,
        AliasScanResult scanResult,
        Map<String, CertificateInfo> aliasDetails,
        KeyStore keyStore,
        TlsValidationService validationService
    ) {
        int row = table.getSelectedRow();
        if (row < 0 || row >= scanResult.validAliases().size()) {
            return;
        }
        String alias = scanResult.validAliases().get(row);
        CertificateInfo cert = aliasDetails.get(alias);
        if (cert == null) {
            MessageDialog.showMessageDialog(
                gui,
                "Certificate details",
                "Details for alias '" + alias + "' are not available.",
                MessageDialogButton.OK
            );
            return;
        }
        CertificateDetailsDialog.show(gui, cert, () -> showSingleAlias(gui, keyStore, alias, validationService));
    }

    private static void showDetails(
        MultiWindowTextGUI gui,
        ValidationResult result,
        String host,
        int port,
        AliasScanResult scanResult
    ) {
        StringBuilder details = new StringBuilder();
        details.append(result.success() ? "OK\n" : "FAIL\n");
        details.append(result.message()).append('\n');
        if (!result.peerChain().isEmpty()) {
            details.append("\nPeer chain:\n");
            for (int i = 0; i < result.peerChain().size(); i++) {
                ChainCertificateInfo cert = result.peerChain().get(i);
                details.append(i + 1)
                    .append(") subject=").append(cert.subject()).append('\n')
                    .append("   issuer=").append(cert.issuer()).append('\n')
                    .append("   notAfter=").append(DATE_FORMATTER.format(cert.notAfter())).append('\n');
            }
        }
        if (scanResult != null) {
            details.append("\nMatching certificates for ").append(host).append(':').append(port).append('\n');
            details.append("Checked: ").append(scanResult.checkedAliases()).append('\n');
            details.append("Valid: ").append(scanResult.validAliases().size()).append('\n');
            details.append("Failed: ").append(scanResult.failedAliases()).append('\n');
            if (scanResult.error() != null && !scanResult.error().isBlank()) {
                details.append("Error: ").append(scanResult.error()).append('\n');
            }
            if (scanResult.validAliases().isEmpty()) {
                details.append("No aliases validated successfully.\n");
            } else {
                details.append("Valid aliases:\n");
                for (String alias : scanResult.validAliases()) {
                    details.append("- ").append(alias).append('\n');
                }
            }
        }

        new MessageDialogBuilder()
            .setTitle("TLS Result")
            .setText(details.toString())
            .addButton(MessageDialogButton.OK)
            .build()
            .showDialog(gui);
    }

    private static void showCertificateFileDetails(
        MultiWindowTextGUI gui,
        ValidationResult result,
        String certificatePath
    ) {
        StringBuilder details = new StringBuilder();
        details.append(result.success() ? "OK\n" : "FAIL\n");
        details.append(normalizeCertificateValidationMessage(result.message())).append('\n');
        if (certificatePath != null && !certificatePath.isBlank()) {
            details.append("\nFile: ").append(certificatePath).append('\n');
        }
        if (!result.peerChain().isEmpty()) {
            details.append("\nParsed certificate chain:\n");
            for (int i = 0; i < result.peerChain().size(); i++) {
                ChainCertificateInfo cert = result.peerChain().get(i);
                details.append(i + 1)
                    .append(") subject=").append(cert.subject()).append('\n')
                    .append("   issuer=").append(cert.issuer()).append('\n')
                    .append("   notAfter=").append(DATE_FORMATTER.format(cert.notAfter())).append('\n');
            }
        }
        new MessageDialogBuilder()
            .setTitle("Certificate File Validation")
            .setText(details.toString())
            .addButton(MessageDialogButton.OK)
            .build()
            .showDialog(gui);
    }

    private static boolean isPkcs12PasswordRequired(String message) {
        return message != null && message.startsWith(TlsValidationService.PKCS12_PASSWORD_REQUIRED_PREFIX);
    }

    private static String normalizeCertificateValidationMessage(String message) {
        if (!isPkcs12PasswordRequired(message)) {
            return message;
        }
        return message.substring(TlsValidationService.PKCS12_PASSWORD_REQUIRED_PREFIX.length()).trim();
    }

    private static String promptPkcs12Password(MultiWindowTextGUI gui) {
        return new TextInputDialogBuilder()
            .setTitle("PKCS12 password")
            .setDescription("Enter password for PKCS12 container:")
            .setInitialContent("")
            .build()
            .showDialog(gui);
    }
}
