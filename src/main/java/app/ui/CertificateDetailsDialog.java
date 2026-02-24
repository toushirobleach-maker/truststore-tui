package app.ui;

import app.truststore.CertificateInfo;
import com.googlecode.lanterna.gui2.BasicWindow;
import com.googlecode.lanterna.gui2.Borders;
import com.googlecode.lanterna.gui2.Button;
import com.googlecode.lanterna.gui2.Direction;
import com.googlecode.lanterna.gui2.Label;
import com.googlecode.lanterna.gui2.LinearLayout;
import com.googlecode.lanterna.gui2.MultiWindowTextGUI;
import com.googlecode.lanterna.gui2.Panel;
import com.googlecode.lanterna.gui2.Window;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public final class CertificateDetailsDialog {
    private static final DateTimeFormatter DETAILS_DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        .withZone(ZoneId.systemDefault());

    private CertificateDetailsDialog() {
    }

    public static void show(MultiWindowTextGUI gui, CertificateInfo cert, Runnable onTlsOnlyThisCert) {
        BasicWindow detailsWindow = ModalWindows.escClosable("Certificate details");
        detailsWindow.setHints(java.util.List.of(Window.Hint.MODAL));

        Panel root = new Panel(new LinearLayout(Direction.VERTICAL));
        root.addComponent(new Label("Alias: " + safe(cert.alias())));
        root.addComponent(new Label("Status: " + safe(cert.status())));
        root.addComponent(new Label("Serial: " + safe(cert.serialNumberHex())));
        root.addComponent(new Label("NotBefore: " + DETAILS_DATE_FORMATTER.format(cert.notBefore())));
        root.addComponent(new Label("NotAfter: " + DETAILS_DATE_FORMATTER.format(cert.notAfter())));
        root.addComponent(new Label("Subject: " + safe(cert.subject())));
        root.addComponent(new Label("Issuer: " + safe(cert.issuer())));
        root.addComponent(new Label("AltNames:"));
        if (cert.altNames() == null || cert.altNames().isEmpty()) {
            root.addComponent(new Label("  <none>"));
        } else {
            for (String altName : cert.altNames()) {
                root.addComponent(new Label("  - " + safe(altName)));
            }
        }

        Panel actions = new Panel(new LinearLayout(Direction.HORIZONTAL));
        actions.addComponent(new Button("TLS only this cert", () -> {
            detailsWindow.close();
            onTlsOnlyThisCert.run();
        }));
        actions.addComponent(new Button("Close", detailsWindow::close));

        root.addComponent(actions.withBorder(Borders.singleLine("Actions")));
        detailsWindow.setComponent(root.withBorder(Borders.singleLine("Full certificate information")));
        gui.addWindowAndWait(detailsWindow);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
