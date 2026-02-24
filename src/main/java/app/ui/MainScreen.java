package app.ui;

import app.tls.TlsValidationService;
import app.truststore.CertificateInfo;
import app.truststore.CertificateViewService;
import app.truststore.LoadedTruststore;
import app.truststore.StoreSourceType;
import app.truststore.TruststoreLoader;
import com.googlecode.lanterna.gui2.BasicWindow;
import com.googlecode.lanterna.gui2.Borders;
import com.googlecode.lanterna.gui2.Button;
import com.googlecode.lanterna.gui2.Direction;
import com.googlecode.lanterna.gui2.GridLayout;
import com.googlecode.lanterna.gui2.Label;
import com.googlecode.lanterna.gui2.LinearLayout;
import com.googlecode.lanterna.gui2.MultiWindowTextGUI;
import com.googlecode.lanterna.gui2.Panel;
import com.googlecode.lanterna.gui2.RadioBoxList;
import com.googlecode.lanterna.gui2.TextBox;
import com.googlecode.lanterna.gui2.Window;
import com.googlecode.lanterna.gui2.dialogs.MessageDialog;
import com.googlecode.lanterna.gui2.dialogs.MessageDialogButton;
import com.googlecode.lanterna.gui2.dialogs.TextInputDialogBuilder;
import com.googlecode.lanterna.input.KeyStroke;
import java.util.List;

public class MainScreen {
    private static final String ENV_PASSWORD = "TRUSTSTORE_PASSWORD";
    private static final String ENV_PATH = "TRUSTSTORE_PATH";
    private static final String ENV_URL = "TRUSTSTORE_URL";
    private static final String DEFAULT_PASSWORD = "changeit";

    private final MultiWindowTextGUI gui;
    private final Window window;
    private final TruststoreLoader truststoreLoader;
    private final CertificateViewService certificateViewService;
    private final TlsValidationService tlsValidationService;

    private final CertificateListScreen certificateListScreen;
    private LoadedTruststore loadedTruststore;
    private Label statusLabel;
    private Label loadedStoreLabel;

    public MainScreen(
        MultiWindowTextGUI gui,
        Window window,
        TruststoreLoader truststoreLoader,
        CertificateViewService certificateViewService,
        TlsValidationService tlsValidationService
    ) {
        this.gui = gui;
        this.window = window;
        this.truststoreLoader = truststoreLoader;
        this.certificateViewService = certificateViewService;
        this.tlsValidationService = tlsValidationService;
        this.certificateListScreen = new CertificateListScreen(gui, this::openTlsCheckForAliasOnly);
    }

    public Panel create() {
        Panel root = new Panel(new LinearLayout(Direction.VERTICAL));

        Panel inputGrid = new Panel(new GridLayout(2));
        RadioBoxList<String> sourceType = new RadioBoxList<>();
        sourceType.addItem("File");
        sourceType.addItem("URL (truststore/tar.gz)");
        sourceType.setCheckedItemIndex(0);

        TextBox sourceInput = new TextBox();
        loadedStoreLabel = new Label("No truststore loaded");
        statusLabel = new Label("");
        Panel sourceSelector = new Panel(new LinearLayout(Direction.HORIZONTAL));
        sourceSelector.addComponent(sourceInput);
        sourceSelector.addComponent(new Button("Browse...", () -> {
            if (sourceType.getCheckedItemIndex() != 0) {
                MessageDialog.showMessageDialog(
                    gui,
                    "Browse files",
                    "File browser is available only for 'File' source type",
                    MessageDialogButton.OK
                );
                return;
            }
            String selectedPath = FileSystemPickerDialog.show(gui, sourceInput.getText());
            if (selectedPath != null && !selectedPath.isBlank()) {
                sourceInput.setText(selectedPath);
            }
        }));

        inputGrid.addComponent(new Label("Source type"));
        inputGrid.addComponent(sourceType);
        inputGrid.addComponent(new Label("Path / URL"));
        inputGrid.addComponent(sourceSelector);

        Panel actionButtons = new Panel(new LinearLayout(Direction.HORIZONTAL));
        actionButtons.addComponent(new Button("Load truststore", () -> loadStore(
            sourceType,
            sourceInput.getText()
        )));
        actionButtons.addComponent(new Button("TLS check", this::openTlsCheck));
        actionButtons.addComponent(new Button("Exit", window::close));

        Panel topRow = new Panel(new LinearLayout(Direction.HORIZONTAL));
        topRow.addComponent(inputGrid.withBorder(Borders.singleLine("Truststore source")));
        topRow.addComponent(statusLabel.withBorder(Borders.singleLine("Status")));

        root.addComponent(topRow);
        root.addComponent(actionButtons);
        root.addComponent(loadedStoreLabel);
        root.addComponent(certificateListScreen.component().withBorder(Borders.singleLine("Certificates")));
        initializeSourceOnStartup(sourceType, sourceInput);
        return root;
    }

    public boolean handleGlobalKey(KeyStroke keyStroke) {
        return certificateListScreen.handleGlobalKey(keyStroke);
    }

    private void loadStore(RadioBoxList<String> sourceType, String sourceValue) {
        try {
            if (sourceValue == null || sourceValue.isBlank()) {
                throw new IllegalArgumentException("Path / URL is required");
            }
            String password = System.getenv(ENV_PASSWORD);
            if (password == null || password.isBlank()) {
                password = DEFAULT_PASSWORD;
            }

            StoreSourceType source = sourceType.getCheckedItemIndex() == 0
                ? StoreSourceType.FILE
                : StoreSourceType.URL_TAR_GZ;
            loadedTruststore = truststoreLoader.load(source, sourceValue.trim(), password.toCharArray());

            List<CertificateInfo> certificates = certificateViewService.listCertificates(loadedTruststore.keyStore());
            certificateListScreen.setCertificates(certificates);
            loadedStoreLabel.setText(
                "Loaded: " + loadedTruststore.sourceDescription()
                    + " (type=" + loadedTruststore.storeType()
                    + ", certs=" + certificates.size() + ")"
            );
            statusLabel.setText("Truststore loaded successfully");
        } catch (Exception e) {
            String error = buildErrorMessage(e);
            statusLabel.setText("Failed: " + shorten(error, 120));
            MessageDialog.showMessageDialog(gui, "Load error", error, MessageDialogButton.OK);
        }
    }

    private void initializeSourceOnStartup(RadioBoxList<String> sourceType, TextBox sourceInput) {
        String envPath = getTrimmedEnv(ENV_PATH);
        String envUrl = getTrimmedEnv(ENV_URL);

        if (envPath != null && envUrl != null) {
            StoreSourceType selected = chooseSourceDialog(
                "Choose source",
                "Both " + ENV_PATH + " and " + ENV_URL + " are set.\nSelect which one to use now."
            );
            if (selected == StoreSourceType.FILE) {
                sourceType.setCheckedItemIndex(0);
                sourceInput.setText(envPath);
                loadStore(sourceType, envPath);
                return;
            }
            if (selected == StoreSourceType.URL_TAR_GZ) {
                sourceType.setCheckedItemIndex(1);
                sourceInput.setText(envUrl);
                loadStore(sourceType, envUrl);
                return;
            }
            statusLabel.setText("Source is not selected yet");
            return;
        }

        if (envPath != null) {
            sourceType.setCheckedItemIndex(0);
            sourceInput.setText(envPath);
            loadStore(sourceType, envPath);
            return;
        }

        if (envUrl != null) {
            sourceType.setCheckedItemIndex(1);
            sourceInput.setText(envUrl);
            loadStore(sourceType, envUrl);
            return;
        }

        StoreSourceType selected = chooseSourceDialog(
            "Choose source",
            "No source env is set.\nWhere do you want to load truststore from?"
        );
        if (selected == StoreSourceType.FILE) {
            sourceType.setCheckedItemIndex(0);
            String selectedPath = FileSystemPickerDialog.show(gui, sourceInput.getText());
            if (selectedPath != null && !selectedPath.isBlank()) {
                sourceInput.setText(selectedPath);
                loadStore(sourceType, selectedPath);
            } else {
                statusLabel.setText("File is not selected yet");
            }
            return;
        }
        if (selected == StoreSourceType.URL_TAR_GZ) {
            sourceType.setCheckedItemIndex(1);
            String url = promptUrlInput(sourceInput.getText());
            if (url != null && !url.isBlank()) {
                sourceInput.setText(url);
                loadStore(sourceType, url);
            } else {
                statusLabel.setText("URL is not entered yet");
            }
            return;
        }
        statusLabel.setText("Source is not selected yet");
    }

    private String promptUrlInput(String initialValue) {
        return new TextInputDialogBuilder()
            .setTitle("Truststore URL")
            .setDescription("Enter truststore URL (file or tar.gz):")
            .setInitialContent(initialValue == null ? "" : initialValue)
            .build()
            .showDialog(gui);
    }

    private String buildErrorMessage(Throwable error) {
        if (error == null) {
            return "Unknown error";
        }
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String primary = messageOrClass(error);
        String rootMessage = messageOrClass(root);
        if (root == error || rootMessage.equals(primary)) {
            return primary;
        }
        return primary + "\nRoot cause: " + rootMessage;
    }

    private String messageOrClass(Throwable error) {
        if (error == null) {
            return "Unknown error";
        }
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            return error.getClass().getSimpleName();
        }
        return message;
    }

    private String shorten(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, Math.max(0, maxLength - 3)) + "...";
    }

    private StoreSourceType chooseSourceDialog(String title, String text) {
        final StoreSourceType[] selected = new StoreSourceType[1];
        BasicWindow dialog = new BasicWindow(title);
        dialog.setHints(java.util.List.of(BasicWindow.Hint.MODAL));

        Panel root = new Panel(new LinearLayout(Direction.VERTICAL));
        root.addComponent(new Label(text));

        Panel actions = new Panel(new LinearLayout(Direction.HORIZONTAL));
        actions.addComponent(new Button("File", () -> {
            selected[0] = StoreSourceType.FILE;
            dialog.close();
        }));
        actions.addComponent(new Button("URL", () -> {
            selected[0] = StoreSourceType.URL_TAR_GZ;
            dialog.close();
        }));
        actions.addComponent(new Button("Later", dialog::close));

        root.addComponent(actions);
        dialog.setComponent(root);
        gui.addWindowAndWait(dialog);
        return selected[0];
    }

    private String getTrimmedEnv(String name) {
        String value = System.getenv(name);
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private void openTlsCheck() {
        if (loadedTruststore == null) {
            MessageDialog.showMessageDialog(gui, "TLS check", "Load a truststore first", MessageDialogButton.OK);
            return;
        }
        TlsCheckDialog.show(gui, loadedTruststore.keyStore(), null, tlsValidationService);
    }

    private void openTlsCheckForAliasOnly(String alias) {
        if (loadedTruststore == null) {
            MessageDialog.showMessageDialog(gui, "TLS check", "Load a truststore first", MessageDialogButton.OK);
            return;
        }
        if (alias == null || alias.isBlank()) {
            MessageDialog.showMessageDialog(gui, "TLS check", "Selected alias is empty", MessageDialogButton.OK);
            return;
        }
        TlsCheckDialog.showSingleAlias(gui, loadedTruststore.keyStore(), alias, tlsValidationService);
    }
}
