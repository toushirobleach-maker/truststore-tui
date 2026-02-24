package app.ui;

import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.gui2.BasicWindow;
import com.googlecode.lanterna.gui2.Borders;
import com.googlecode.lanterna.gui2.Button;
import com.googlecode.lanterna.gui2.Direction;
import com.googlecode.lanterna.gui2.LinearLayout;
import com.googlecode.lanterna.gui2.MultiWindowTextGUI;
import com.googlecode.lanterna.gui2.Panel;
import com.googlecode.lanterna.gui2.Label;
import com.googlecode.lanterna.gui2.table.Table;
import com.googlecode.lanterna.gui2.table.TableModel;
import com.googlecode.lanterna.gui2.dialogs.MessageDialog;
import com.googlecode.lanterna.gui2.dialogs.MessageDialogButton;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class FileSystemPickerDialog {
    private static final int MAX_ENTRIES_TO_SCAN = 5000;
    private static final String TYPE_DIR = "DIR";
    private static final String TYPE_FILE = "FILE";
    private static final DateTimeFormatter MODIFIED_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        .withZone(ZoneId.systemDefault());

    private FileSystemPickerDialog() {
    }

    public static String show(MultiWindowTextGUI gui, String startPath) {
        PickerState state = new PickerState(resolveStartPath(startPath));
        Label currentPathLabel = new Label("");
        Label sortLabel = new Label("");
        Table<String> entriesTable = new Table<>("Name", "Type", "Modified");
        entriesTable.setPreferredSize(new TerminalSize(120, 20));
        BasicWindow dialog = new BasicWindow("Choose truststore file") {
            @Override
            public boolean handleInput(KeyStroke keyStroke) {
                if (applySortHotkey(keyStroke, state, currentPathLabel, sortLabel, entriesTable)) {
                    return true;
                }
                if (keyStroke != null && keyStroke.getKeyType() == KeyType.Escape) {
                    Path parent = state.currentDirectory.getParent();
                    if (parent != null) {
                        state.currentDirectory = parent;
                        refreshEntries(state, currentPathLabel, sortLabel, entriesTable);
                    } else {
                        close();
                    }
                    return true;
                }
                return super.handleInput(keyStroke);
            }
        };
        dialog.setHints(java.util.List.of(BasicWindow.Hint.MODAL));
        refreshEntries(state, currentPathLabel, sortLabel, entriesTable);
        entriesTable.setSelectAction(() -> {
            Entry selected = selectedEntry(state, entriesTable);
            if (selected == null) {
                return;
            }
            if (selected.parentLink || selected.directory) {
                openSelected(state, entriesTable, currentPathLabel, sortLabel);
                return;
            }
            state.selectedFile = selected.path;
            dialog.close();
        });

        Button upButton = new Button("Up", () -> {
            Path parent = state.currentDirectory.getParent();
            if (parent != null) {
                state.currentDirectory = parent;
                refreshEntries(state, currentPathLabel, sortLabel, entriesTable);
            }
        });

        Button openButton = new Button("Open", () -> openSelected(state, entriesTable, currentPathLabel, sortLabel));
        Button selectButton = new Button("Select", () -> selectAndClose(state, entriesTable, gui, dialog));
        Button cancelButton = new Button("Cancel", dialog::close);

        Panel actions = new Panel(new LinearLayout(Direction.HORIZONTAL));
        actions.addComponent(upButton);
        actions.addComponent(openButton);
        actions.addComponent(selectButton);
        actions.addComponent(cancelButton);

        Panel root = new Panel(new LinearLayout(Direction.VERTICAL));
        root.addComponent(currentPathLabel.withBorder(Borders.singleLine("Current directory")));
        root.addComponent(sortLabel.withBorder(Borders.singleLine("Sort (Ctrl+N name, Ctrl+D date)")));
        root.addComponent(entriesTable.withBorder(Borders.singleLine("Entries")));
        root.addComponent(actions);

        dialog.setComponent(root);
        dialog.setFocusedInteractable(entriesTable);
        gui.addWindowAndWait(dialog);
        return state.selectedFile == null ? null : state.selectedFile.toString();
    }

    private static void openSelected(
        PickerState state,
        Table<String> entriesTable,
        Label currentPathLabel,
        Label sortLabel
    ) {
        Entry selected = selectedEntry(state, entriesTable);
        if (selected == null) {
            return;
        }
        if (selected.parentLink) {
            Path parent = state.currentDirectory.getParent();
            if (parent != null) {
                state.currentDirectory = parent;
                refreshEntries(state, currentPathLabel, sortLabel, entriesTable);
            }
            return;
        }
        if (selected.directory) {
            state.currentDirectory = selected.path;
            refreshEntries(state, currentPathLabel, sortLabel, entriesTable);
        }
    }

    private static void selectAndClose(
        PickerState state,
        Table<String> entriesTable,
        MultiWindowTextGUI gui,
        BasicWindow dialog
    ) {
        Entry selected = selectedEntry(state, entriesTable);
        if (selected == null) {
            MessageDialog.showMessageDialog(gui, "Select file", "Choose a file first", MessageDialogButton.OK);
            return;
        }
        if (selected.parentLink || selected.directory) {
            MessageDialog.showMessageDialog(gui, "Select file", "Selected entry is a directory", MessageDialogButton.OK);
            return;
        }
        state.selectedFile = selected.path;
        dialog.close();
    }

    private static Entry selectedEntry(PickerState state, Table<String> entriesTable) {
        int selectedRow = entriesTable.getSelectedRow();
        if (selectedRow < 0 || selectedRow >= state.entries.size()) {
            return null;
        }
        return state.entries.get(selectedRow);
    }

    private static boolean applySortHotkey(
        KeyStroke keyStroke,
        PickerState state,
        Label currentPathLabel,
        Label sortLabel,
        Table<String> entriesTable
    ) {
        if (keyStroke == null || !keyStroke.isCtrlDown() || keyStroke.getKeyType() != KeyType.Character) {
            return false;
        }
        char key = Character.toLowerCase(keyStroke.getCharacter());
        if (key == 'n') {
            toggleSort(state, SortMode.NAME);
            refreshEntries(state, currentPathLabel, sortLabel, entriesTable);
            return true;
        }
        if (key == 'd') {
            toggleSort(state, SortMode.MODIFIED);
            refreshEntries(state, currentPathLabel, sortLabel, entriesTable);
            return true;
        }
        return false;
    }

    private static void toggleSort(PickerState state, SortMode mode) {
        if (state.sortMode == mode) {
            state.ascending = !state.ascending;
        } else {
            state.sortMode = mode;
            state.ascending = true;
        }
    }

    private static void refreshEntries(
        PickerState state,
        Label currentPathLabel,
        Label sortLabel,
        Table<String> entriesTable
    ) {
        try {
            Entry selectedBeforeRefresh = selectedEntry(state, entriesTable);
            Path selectedPath = selectedBeforeRefresh == null ? null : selectedBeforeRefresh.path;
            ReadResult readResult = readEntries(state.currentDirectory, state.sortMode, state.ascending);
            state.entries = readResult.entries;
            state.truncated = readResult.truncated;
            currentPathLabel.setText(state.currentDirectory.toString());
            sortLabel.setText(sortLabelText(state));
            TableModel<String> model = entriesTable.getTableModel();
            model.clear();
            for (Entry entry : state.entries) {
                model.addRow(
                    entry.name,
                    entry.directory || entry.parentLink ? TYPE_DIR : TYPE_FILE,
                    formatModified(entry)
                );
            }
            int restoredRow = indexOfPath(state.entries, selectedPath);
            if (restoredRow >= 0) {
                entriesTable.setSelectedRow(restoredRow);
            } else if (!state.entries.isEmpty()) {
                entriesTable.setSelectedRow(0);
            }
        } catch (IOException e) {
            state.entries = List.of();
            currentPathLabel.setText("Failed to read " + state.currentDirectory + ": " + e.getMessage());
            sortLabel.setText(sortLabelText(state));
            entriesTable.getTableModel().clear();
        }
    }

    private static int indexOfPath(List<Entry> entries, Path path) {
        if (path == null) {
            return -1;
        }
        for (int i = 0; i < entries.size(); i++) {
            if (path.equals(entries.get(i).path)) {
                return i;
            }
        }
        return -1;
    }

    private static String sortLabelText(PickerState state) {
        String direction = state.ascending ? "ASC" : "DESC";
        String by = state.sortMode == SortMode.NAME ? "name" : "date";
        if (state.truncated) {
            return "Sorted by " + by + " " + direction + " | showing first " + MAX_ENTRIES_TO_SCAN + " entries";
        }
        return "Sorted by " + by + " " + direction;
    }

    private static String formatModified(Entry entry) {
        if (entry.parentLink || entry.modified == null) {
            return "";
        }
        return MODIFIED_FORMATTER.format(entry.modified);
    }

    private static ReadResult readEntries(Path directory, SortMode sortMode, boolean ascending) throws IOException {
        List<Entry> rawEntries = new ArrayList<>();
        Path parent = directory.getParent();
        if (parent != null) {
            rawEntries.add(new Entry("..", parent, true, true, null));
        }
        boolean truncated = false;
        int scanned = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path path : stream) {
                if (scanned >= MAX_ENTRIES_TO_SCAN) {
                    truncated = true;
                    break;
                }
                boolean isDirectory = Files.isDirectory(path);
                Instant modified;
                try {
                    modified = Files.getLastModifiedTime(path).toInstant();
                } catch (IOException ex) {
                    modified = Instant.EPOCH;
                }
                rawEntries.add(new Entry(
                    path.getFileName().toString(),
                    path,
                    isDirectory,
                    false,
                    modified
                ));
                scanned++;
            }
        }
        return new ReadResult(sortEntries(rawEntries, sortMode, ascending), truncated);
    }

    private static List<Entry> sortEntries(List<Entry> entries, SortMode sortMode, boolean ascending) {
        Entry parentLink = null;
        List<Entry> regularEntries = new ArrayList<>();
        for (Entry entry : entries) {
            if (entry.parentLink) {
                parentLink = entry;
            } else {
                regularEntries.add(entry);
            }
        }

        Comparator<Entry> keyComparator = switch (sortMode) {
            case NAME -> Comparator.comparing(entry -> entry.name.toLowerCase());
            case MODIFIED -> Comparator.<Entry, Instant>comparing(entry -> entry.modified)
                .thenComparing(entry -> entry.name.toLowerCase());
        };
        if (!ascending) {
            keyComparator = keyComparator.reversed();
        }

        regularEntries.sort(
            Comparator.<Entry, Boolean>comparing(entry -> !entry.directory)
                .thenComparing(keyComparator)
        );

        List<Entry> sorted = new ArrayList<>();
        if (parentLink != null) {
            sorted.add(parentLink);
        }
        sorted.addAll(regularEntries);
        return sorted;
    }

    private static Path resolveStartPath(String startPath) {
        if (startPath != null && !startPath.isBlank()) {
            try {
                Path candidate = Path.of(startPath.trim());
                if (Files.isDirectory(candidate)) {
                    return candidate.toAbsolutePath().normalize();
                }
                Path parent = candidate.toAbsolutePath().normalize().getParent();
                if (parent != null && Files.isDirectory(parent)) {
                    return parent;
                }
            } catch (Exception ignored) {
                // Fallback to current directory.
            }
        }
        return Path.of(".").toAbsolutePath().normalize();
    }

    private static final class PickerState {
        private Path currentDirectory;
        private List<Entry> entries;
        private Path selectedFile;
        private SortMode sortMode;
        private boolean ascending;
        private boolean truncated;

        private PickerState(Path currentDirectory) {
            this.currentDirectory = currentDirectory;
            this.entries = List.of();
            this.sortMode = SortMode.NAME;
            this.ascending = true;
            this.truncated = false;
        }
    }

    private enum SortMode {
        NAME,
        MODIFIED
    }

    private record Entry(String name, Path path, boolean directory, boolean parentLink, Instant modified) {
    }

    private record ReadResult(List<Entry> entries, boolean truncated) {
    }
}
