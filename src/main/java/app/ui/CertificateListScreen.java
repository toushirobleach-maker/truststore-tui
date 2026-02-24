package app.ui;

import app.truststore.CertificateInfo;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.gui2.Interactable;
import com.googlecode.lanterna.gui2.MultiWindowTextGUI;
import com.googlecode.lanterna.gui2.Button;
import com.googlecode.lanterna.gui2.Direction;
import com.googlecode.lanterna.gui2.Label;
import com.googlecode.lanterna.gui2.LinearLayout;
import com.googlecode.lanterna.gui2.Panel;
import com.googlecode.lanterna.gui2.TextGUIGraphics;
import com.googlecode.lanterna.gui2.dialogs.MessageDialog;
import com.googlecode.lanterna.gui2.dialogs.MessageDialogButton;
import com.googlecode.lanterna.gui2.dialogs.TextInputDialogBuilder;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.graphics.ThemeDefinition;
import com.googlecode.lanterna.gui2.table.Table;
import com.googlecode.lanterna.gui2.table.TableCellRenderer;
import com.googlecode.lanterna.gui2.table.TableModel;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

public class CertificateListScreen {
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        .withZone(ZoneId.systemDefault());
    private static final int MIN_ALIAS_VIEW_WIDTH = 14;
    private static final int EXPIRY_VIEW_WIDTH = 10;
    private static final int MIN_SUBJECT_VIEW_WIDTH = 20;
    private static final int MIN_ISSUER_VIEW_WIDTH = 20;
    private static final int STATUS_VIEW_WIDTH = 12;
    private static final int MIN_TABLE_WIDTH = 80;
    private static final int MIN_TABLE_HEIGHT = 8;
    private static final int RESERVED_TOP_ROWS = 18;
    private static final int SUBJECT_SCROLL_STEP = 12;

    private final MultiWindowTextGUI gui;
    private final Consumer<String> aliasTlsRequestHandler;
    private final Runnable onEscFromTable;
    private final Table<String> table;
    private final Panel panel;
    private final Label hotkeysLabel;
    private final Label sortedLabel;
    private List<CertificateInfo> allCertificates = new ArrayList<>();
    private List<CertificateInfo> visibleCertificates = new ArrayList<>();
    private boolean hideExpired = false;
    private SortMode sortMode = SortMode.EXPIRY;
    private boolean sortAscending = true;
    private int subjectOffset = 0;
    private String lastSearchQuery = "";
    private String activeSearchQuery = "";
    private int aliasViewWidth = MIN_ALIAS_VIEW_WIDTH;
    private int subjectViewWidth = MIN_SUBJECT_VIEW_WIDTH;
    private int issuerViewWidth = MIN_ISSUER_VIEW_WIDTH;

    public CertificateListScreen(
        MultiWindowTextGUI gui,
        Consumer<String> aliasTlsRequestHandler,
        Runnable onEscFromTable
    ) {
        this.gui = gui;
        this.aliasTlsRequestHandler = aliasTlsRequestHandler;
        this.onEscFromTable = onEscFromTable;
        this.table = new Table<>("Alias", "Expiry", "Subject", "Issuer", "Status") {
            @Override
            public synchronized Interactable.Result handleKeyStroke(KeyStroke keyStroke) {
                if (handleGlobalKey(keyStroke)) {
                    return Interactable.Result.HANDLED;
                }
                return super.handleKeyStroke(keyStroke);
            }
        };
        this.table.setSelectAction(this::openSelectedCertificateDialog);
        this.table.setTableCellRenderer(new SearchHighlightCellRenderer());
        this.panel = new Panel(new LinearLayout(Direction.VERTICAL));
        this.hotkeysLabel = new Label("");
        this.sortedLabel = new Label("");
        this.sortedLabel.setForegroundColor(TextColor.ANSI.RED);

        Panel hotkeysPanel = new Panel(new LinearLayout(Direction.HORIZONTAL));
        hotkeysPanel.addComponent(hotkeysLabel);
        hotkeysPanel.addComponent(new Label(" | "));
        hotkeysPanel.addComponent(sortedLabel);

        this.panel.addComponent(hotkeysPanel);
        this.panel.addComponent(table);
        updateHotkeysLabel();
    }

    public boolean handleGlobalKey(KeyStroke keyStroke) {
        if (!table.isFocused()) {
            return false;
        }
        if (keyStroke != null && keyStroke.getKeyType() == KeyType.Escape) {
            onEscFromTable.run();
            return true;
        }
        if (isSearchShortcut(keyStroke)) {
            openSearchDialog();
            return true;
        }
        if (isCtrlShortcut(keyStroke, 'e')) {
            toggleExpiredFilter();
            return true;
        }
        if (isCtrlShortcut(keyStroke, 'a')) {
            applySort(SortMode.ALIAS);
            return true;
        }
        if (isCtrlShortcut(keyStroke, 'x')) {
            applySort(SortMode.EXPIRY);
            return true;
        }
        if (isHorizontalScrollShortcut(keyStroke)) {
            if (keyStroke.getKeyType() == KeyType.ArrowLeft) {
                scrollSubject(-SUBJECT_SCROLL_STEP);
            } else {
                scrollSubject(SUBJECT_SCROLL_STEP);
            }
            return true;
        }
        return false;
    }

    private void openSelectedCertificateDialog() {
        int row = table.getSelectedRow();
        if (row < 0 || row >= visibleCertificates.size()) {
            return;
        }
        CertificateInfo cert = visibleCertificates.get(row);
        CertificateDetailsDialog.show(gui, cert, () -> aliasTlsRequestHandler.accept(cert.alias()));
    }

    public Panel component() {
        return panel;
    }

    public void setCertificates(List<CertificateInfo> certificates) {
        this.allCertificates = new ArrayList<>(certificates);
        int selectedRow = table.getSelectedRow();
        if (selectedRow < 0) {
            selectedRow = 0;
        }
        rebuildVisibleCertificates();
        refreshRowsByIndex(selectedRow);
    }

    private void scrollSubject(int delta) {
        int maxOffset = maxSubjectOffset();
        int nextOffset = subjectOffset + delta;
        if (nextOffset < 0) {
            nextOffset = 0;
        }
        if (nextOffset > maxOffset) {
            nextOffset = maxOffset;
        }
        if (nextOffset == subjectOffset) {
            return;
        }
        subjectOffset = nextOffset;
        refreshRowsByIndex(table.getSelectedRow());
    }

    private int maxSubjectOffset() {
        int maxLen = 0;
        for (CertificateInfo cert : visibleCertificates) {
            maxLen = Math.max(maxLen, cert.subject().length());
        }
        return Math.max(0, maxLen - subjectViewWidth);
    }

    private String subjectWindow(String subject) {
        if (subject == null) {
            return "";
        }
        if (subjectOffset >= subject.length()) {
            return "";
        }
        int endExclusive = Math.min(subject.length(), subjectOffset + subjectViewWidth);
        return subject.substring(subjectOffset, endExclusive);
    }

    private void refreshRowsByIndex(int selectedRow) {
        updateViewportSizing();
        TableModel<String> model = table.getTableModel();
        model.clear();
        for (CertificateInfo cert : this.visibleCertificates) {
            model.addRow(
                clip(safe(cert.alias()), aliasViewWidth),
                clip(DATE_FORMATTER.format(cert.notAfter()), EXPIRY_VIEW_WIDTH),
                clip(subjectWindow(cert.subject()), subjectViewWidth),
                clip(safe(cert.issuer()), issuerViewWidth),
                clip(safe(cert.status()), STATUS_VIEW_WIDTH)
            );
        }
        if (!visibleCertificates.isEmpty()) {
            int safeRow = Math.max(0, Math.min(selectedRow, visibleCertificates.size() - 1));
            table.setSelectedRow(safeRow);
        }
        updateHotkeysLabel();
    }

    private void rebuildVisibleCertificates() {
        if (!hideExpired) {
            visibleCertificates = new ArrayList<>(allCertificates);
        } else {
            List<CertificateInfo> filtered = new ArrayList<>();
            for (CertificateInfo cert : allCertificates) {
                if (!"expired".equalsIgnoreCase(cert.status())) {
                    filtered.add(cert);
                }
            }
            visibleCertificates = filtered;
        }
        Comparator<CertificateInfo> comparator = switch (sortMode) {
            case ALIAS -> Comparator.comparing(cert -> cert.alias().toLowerCase());
            case EXPIRY -> Comparator.comparing(CertificateInfo::notAfter)
                .thenComparing(cert -> cert.alias().toLowerCase());
        };
        if (!sortAscending) {
            comparator = comparator.reversed();
        }
        visibleCertificates.sort(comparator);
    }

    private void toggleExpiredFilter() {
        String selectedAlias = getSelectedAlias();
        hideExpired = !hideExpired;
        rebuildVisibleCertificates();
        int nextRow = indexOfAlias(selectedAlias);
        if (nextRow < 0) {
            nextRow = 0;
        }
        refreshRowsByIndex(nextRow);
    }

    private int indexOfAlias(String alias) {
        if (alias == null) {
            return -1;
        }
        for (int i = 0; i < visibleCertificates.size(); i++) {
            if (alias.equals(visibleCertificates.get(i).alias())) {
                return i;
            }
        }
        return -1;
    }

    private void applySort(SortMode requestedMode) {
        String selectedAlias = getSelectedAlias();
        if (sortMode == requestedMode) {
            sortAscending = !sortAscending;
        } else {
            sortMode = requestedMode;
            sortAscending = true;
        }
        rebuildVisibleCertificates();
        int nextRow = indexOfAlias(selectedAlias);
        if (nextRow < 0) {
            nextRow = 0;
        }
        refreshRowsByIndex(nextRow);
    }

    private void updateHotkeysLabel() {
        String expiredAction = hideExpired ? "show expired" : "hide expired";
        String direction = sortAscending ? "ASC" : "DESC";
        String mode = sortMode == SortMode.ALIAS ? "alias" : "expiry";
        hotkeysLabel.setText(
            "Hotkeys: / search | Ctrl+E " + expiredAction
                + " | Ctrl+A sort alias"
                + " | Ctrl+X sort expiry"
        );
        sortedLabel.setText("sorted: " + mode + " " + direction);
    }

    private boolean isSearchShortcut(KeyStroke keyStroke) {
        return keyStroke != null
            && !keyStroke.isCtrlDown()
            && keyStroke.getKeyType() == KeyType.Character
            && keyStroke.getCharacter() != null
            && keyStroke.getCharacter() == '/';
    }

    private boolean isHorizontalScrollShortcut(KeyStroke keyStroke) {
        return keyStroke != null
            && !keyStroke.isCtrlDown()
            && !keyStroke.isAltDown()
            && (keyStroke.getKeyType() == KeyType.ArrowLeft || keyStroke.getKeyType() == KeyType.ArrowRight);
    }

    private boolean isCtrlShortcut(KeyStroke keyStroke, char expectedChar) {
        return keyStroke != null
            && keyStroke.isCtrlDown()
            && keyStroke.getKeyType() == KeyType.Character
            && keyStroke.getCharacter() != null
            && Character.toLowerCase(keyStroke.getCharacter()) == Character.toLowerCase(expectedChar);
    }

    private void openSearchDialog() {
        String query = new TextInputDialogBuilder()
            .setTitle("Search certificates")
            .setDescription("Search in table (case-insensitive):")
            .setInitialContent(lastSearchQuery)
            .build()
            .showDialog(gui);
        if (query == null) {
            return;
        }
        String normalized = query.trim();
        if (normalized.isEmpty()) {
            return;
        }
        lastSearchQuery = normalized;
        activeSearchQuery = normalized;

        int startIndex = table.getSelectedRow() + 1;
        if (startIndex < 0 || startIndex >= visibleCertificates.size()) {
            startIndex = 0;
        }
        int match = findMatch(normalized, startIndex);
        if (match < 0 && startIndex > 0) {
            match = findMatch(normalized, 0);
        }
        if (match >= 0) {
            refreshRowsByIndex(match);
            return;
        }
        refreshRowsByIndex(table.getSelectedRow());
        MessageDialog.showMessageDialog(
            gui,
            "Search",
            "No matches for: " + normalized,
            MessageDialogButton.OK
        );
    }

    private int findMatch(String query, int fromIndex) {
        String needle = query.toLowerCase(Locale.ROOT);
        for (int i = Math.max(0, fromIndex); i < visibleCertificates.size(); i++) {
            if (matchesRow(visibleCertificates.get(i), needle)) {
                return i;
            }
        }
        return -1;
    }

    private boolean matchesRow(CertificateInfo cert, String needleLower) {
        return containsIgnoreCase(cert.alias(), needleLower)
            || containsIgnoreCase(cert.subject(), needleLower)
            || containsIgnoreCase(cert.issuer(), needleLower)
            || containsIgnoreCase(DATE_FORMATTER.format(cert.notAfter()), needleLower)
            || containsIgnoreCase(cert.status(), needleLower);
    }

    private boolean containsIgnoreCase(String value, String needleLower) {
        if (value == null) {
            return false;
        }
        return value.toLowerCase(Locale.ROOT).contains(needleLower);
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String clip(String value, int maxWidth) {
        if (value == null || value.length() <= maxWidth) {
            return value == null ? "" : value;
        }
        return value.substring(0, Math.max(0, maxWidth));
    }

    private void updateViewportSizing() {
        TerminalSize terminalSize = gui.getScreen().getTerminalSize();
        int tableWidth = Math.max(MIN_TABLE_WIDTH, terminalSize.getColumns() - 6);
        int tableHeight = Math.max(MIN_TABLE_HEIGHT, terminalSize.getRows() - RESERVED_TOP_ROWS);
        table.setPreferredSize(new TerminalSize(tableWidth, tableHeight));

        int fixed = EXPIRY_VIEW_WIDTH + STATUS_VIEW_WIDTH;
        int remaining = Math.max(0, tableWidth - fixed);
        aliasViewWidth = Math.max(MIN_ALIAS_VIEW_WIDTH, remaining / 4);
        int rest = Math.max(0, remaining - aliasViewWidth);
        subjectViewWidth = Math.max(MIN_SUBJECT_VIEW_WIDTH, rest / 2);
        issuerViewWidth = Math.max(MIN_ISSUER_VIEW_WIDTH, rest - subjectViewWidth);
    }

    private final class SearchHighlightCellRenderer implements TableCellRenderer<String> {
        @Override
        public TerminalSize getPreferredSize(Table<String> table, String cell, int columnIndex, int rowIndex) {
            return new TerminalSize(cell == null ? 0 : cell.length(), 1);
        }

        @Override
        public void drawCell(
            Table<String> table,
            String cell,
            int columnIndex,
            int rowIndex,
            TextGUIGraphics graphics
        ) {
            ThemeDefinition themeDefinition = table.getThemeDefinition();
            boolean selected = table.getSelectedRow() == rowIndex;
            if (selected) {
                graphics.applyThemeStyle(themeDefinition.getActive());
            } else {
                graphics.applyThemeStyle(themeDefinition.getNormal());
            }

            String value = cell == null ? "" : cell;
            if (!selected && isSearchCellMatch(value)) {
                graphics.setBackgroundColor(TextColor.ANSI.YELLOW);
                graphics.setForegroundColor(TextColor.ANSI.BLACK);
            }

            graphics.fill(' ');
            String drawn = value;
            int max = graphics.getSize().getColumns();
            if (drawn.length() > max) {
                drawn = drawn.substring(0, max);
            }
            graphics.putString(0, 0, drawn);
        }
    }

    private boolean isSearchCellMatch(String value) {
        if (value == null || activeSearchQuery == null || activeSearchQuery.isBlank()) {
            return false;
        }
        return value.toLowerCase(Locale.ROOT).contains(activeSearchQuery.toLowerCase(Locale.ROOT));
    }

    public String getSelectedAlias() {
        int row = table.getSelectedRow();
        if (row < 0 || row >= visibleCertificates.size()) {
            return null;
        }
        return visibleCertificates.get(row).alias();
    }

    public int size() {
        return visibleCertificates.size();
    }

    private enum SortMode {
        ALIAS,
        EXPIRY
    }
}
