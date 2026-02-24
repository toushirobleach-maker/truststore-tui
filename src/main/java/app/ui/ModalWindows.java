package app.ui;

import com.googlecode.lanterna.gui2.BasicWindow;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;

public final class ModalWindows {
    private ModalWindows() {
    }

    public static BasicWindow escClosable(String title) {
        return new BasicWindow(title) {
            @Override
            public boolean handleInput(KeyStroke keyStroke) {
                if (keyStroke != null && keyStroke.getKeyType() == KeyType.Escape) {
                    close();
                    return true;
                }
                return super.handleInput(keyStroke);
            }
        };
    }
}
