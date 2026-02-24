package app;

import app.tls.TlsValidationService;
import app.truststore.CertificateViewService;
import app.truststore.TruststoreLoader;
import app.ui.MainScreen;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.gui2.BasicWindow;
import com.googlecode.lanterna.gui2.MultiWindowTextGUI;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.screen.Screen;
import com.googlecode.lanterna.terminal.DefaultTerminalFactory;
import java.io.IOException;

public final class Main {
    private Main() {
    }

    public static void main(String[] args) {
        DefaultTerminalFactory terminalFactory = new DefaultTerminalFactory();
        terminalFactory.setInitialTerminalSize(new TerminalSize(140, 42));

        try {
            Screen screen = terminalFactory.createScreen();
            screen.startScreen();

            MultiWindowTextGUI gui = new MultiWindowTextGUI(screen);
            final MainScreen[] mainScreenRef = new MainScreen[1];
            BasicWindow window = new BasicWindow("Truststore TUI") {
                @Override
                public boolean handleInput(KeyStroke keyStroke) {
                    if (mainScreenRef[0] != null && mainScreenRef[0].handleGlobalKey(keyStroke)) {
                        return true;
                    }
                    return super.handleInput(keyStroke);
                }
            };

            MainScreen mainScreen = new MainScreen(
                gui,
                window,
                new TruststoreLoader(),
                new CertificateViewService(),
                new TlsValidationService()
            );
            mainScreenRef[0] = mainScreen;

            window.setComponent(mainScreen.create());
            gui.addWindowAndWait(window);
            screen.stopScreen();
        } catch (IOException e) {
            System.err.println("Failed to start TUI: " + e.getMessage());
            System.exit(1);
        }
    }
}
