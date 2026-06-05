package dev.dreiling.YoCoder.controller;

import dev.dreiling.YoCoder.service.BackendClient;
import dev.dreiling.YoCoder.utils.EnvLoader;
import dev.dreiling.YoCoder.utils.OutputRenderer;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.web.WebView;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;

import java.io.File;
import java.net.URL;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

public class MainController implements Initializable {

    private static final String API_KEY = EnvLoader.get("API_KEY");
    private static final String API_URL = EnvLoader.get("API_URL");

    // ── FXML fields ──────────────────────────────────────────────────────────

    @FXML private Label backendStatusLabel;
    @FXML private ComboBox<String> providerCombo;
    @FXML private ComboBox<String> modelCombo;

    // Left panel
    @FXML private TextField projectRootField;
    @FXML private Button scanBtn;
    @FXML private TextField fileFilterField;
    @FXML private TreeView<String> fileTree;
    @FXML private Label fileCountLabel;
    @FXML private Label contextCountLabel;

    // Middle panel
    @FXML private Label selectedFileLabel;
    @FXML private Label fileSizeLabel;
    @FXML private TextArea originalCodeArea;
    @FXML private TextArea promptArea;
    @FXML private Label charCountLabel;
    @FXML private Button optimizeBtn;

    // Right panel
    @FXML private WebView outputWebView;
    @FXML private VBox thinkingOverlay;
    @FXML private Label contextFilesLabel;
    @FXML private Button copyBtn;
    @FXML private Button formatBtn;

    // Status bar
    @FXML private Label statusLabel;

    // ── State ─────────────────────────────────────────────────────────────────

    private final BackendClient backend = new BackendClient(API_URL, API_KEY);
    private List<String> allProjectFiles = new ArrayList<>();
    private final Set<String> contextFiles = new LinkedHashSet<>();
    private String selectedFilePath = null;
    private String lastRawOutput = null;

    // ── Others ────────────────────────────────────────────────────────────────

    private final StringBuilder streamBuffer = new StringBuilder();
    private OutputRenderer outputRenderer;

    // ── Initialise ────────────────────────────────────────────────────────────

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        providerCombo.getItems().addAll("Gemini", "Claude", "OpenAI");
        providerCombo.setValue("Gemini");
        onProviderChanged();

        promptArea.textProperty().addListener((obs, o, n) ->
                charCountLabel.setText(n.length() + " chars")
        );

        fileTree.getSelectionModel().selectedItemProperty().addListener((obs, oldItem, newItem) -> {
            if (newItem != null && newItem.isLeaf()) {
                onFileSelected(getFullPath(newItem));
            }
        });

        checkBackendHealth();

        outputRenderer = new OutputRenderer(outputWebView);

        setStatus("Ready — scan a project to begin");
    }

    // ── Backend Health ────────────────────────────────────────────────────────

    private void checkBackendHealth() {
        backendStatusLabel.setText("Connecting...");
        backendStatusLabel.getStyleClass().removeAll("status-ok", "status-error");

        backend.checkHealth().thenAccept(ok -> Platform.runLater(() -> {
            if (ok) {
                backendStatusLabel.setText("● Connected (localhost:8765)");
                backendStatusLabel.getStyleClass().add("status-ok");
            } else {
                backendStatusLabel.setText("● Offline — start the backend first");
                backendStatusLabel.getStyleClass().add("status-error");
            }
        }));
    }

    // ── Project Browsing & Scanning ──────────────────────────────────────────

    @FXML
    private void browseProject() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Select Project Root");

        String current = projectRootField.getText();
        if (current != null && !current.isBlank()) {
            File f = new File(current);
            if (f.isDirectory()) chooser.setInitialDirectory(f);
        }

        File selected = chooser.showDialog(projectRootField.getScene().getWindow());
        if (selected != null) {
            projectRootField.setText(selected.getAbsolutePath());
            scanProject();
        }
    }

    @FXML
    private void scanProject() {
        String root = projectRootField.getText().trim();
        if (root.isBlank()) {
            showError("Please enter or browse to a project root directory.");
            return;
        }

        setStatus("Scanning project...");
        scanBtn.setDisable(true);
        fileTree.setRoot(null);
        fileCountLabel.setText("Scanning...");
        contextFiles.clear();
        updateContextLabel();

        backend.scanProject(root).thenAccept(result -> Platform.runLater(() -> {
            scanBtn.setDisable(false);
            if (!result.success()) {
                showError("Scan failed: " + result.error());
                fileCountLabel.setText("Error");
                setStatus("Scan failed");
                return;
            }

            allProjectFiles = result.files();
            buildFileTree(allProjectFiles);
            fileCountLabel.setText(allProjectFiles.size() + " files");
            setStatus("Project scanned — " + allProjectFiles.size() + " files found");
        }));
    }

    // ── File Tree ─────────────────────────────────────────────────────────────

    private void buildFileTree(List<String> files) {
        TreeItem<String> root = new TreeItem<>("Project");
        root.setExpanded(true);

        Map<String, TreeItem<String>> dirMap = new TreeMap<>();

        for (String file : files) {
            String[] parts = file.split("/");
            StringBuilder pathBuilder = new StringBuilder();
            TreeItem<String> parent = root;

            for (int i = 0; i < parts.length - 1; i++) {
                pathBuilder.append(parts[i]);
                String dirPath = pathBuilder.toString();
                int finalI = i;
                TreeItem<String> dirItem = dirMap.computeIfAbsent(dirPath, k -> {
                    TreeItem<String> item = new TreeItem<>("📁 " + parts[finalI]);
                    item.setExpanded(finalI == 0);
                    return item;
                });
                if (!parent.getChildren().contains(dirItem)) {
                    parent.getChildren().add(dirItem);
                }
                parent = dirItem;
                pathBuilder.append("/");
            }

            String fileName = parts[parts.length - 1];
            boolean isContext = contextFiles.contains(file);
            TreeItem<String> fileItem = new TreeItem<>(
                    (isContext ? "☑ " : "") + iconFor(fileName) + " " + fileName
            );
            fileItem.setExpanded(false);
            parent.getChildren().add(fileItem);
        }

        fileTree.setRoot(root);

        fileTree.setCellFactory(tv -> new TreeCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setStyle("");
                } else {
                    setText(item);
                    TreeItem<String> treeItem = getTreeItem();
                    String fullPath = treeItem != null ? getFullPath(treeItem) : null;
                    if (fullPath != null && contextFiles.contains(fullPath)) {
                        setStyle("-fx-text-fill: #a6e3a1;");
                    } else {
                        setStyle("");
                    }
                }
            }
        });

        fileTree.setOnMouseClicked(event -> {
            TreeItem<String> selected = fileTree.getSelectionModel().getSelectedItem();
            if (selected == null || !selected.isLeaf()) return;

            String fullPath = getFullPath(selected);
            if (fullPath == null) return;

            if (event.isControlDown()) {
                // Ctrl+click: toggle context inclusion
                toggleContextFile(selected, fullPath);
                event.consume();
            } else {
                // Plain click: open file as target
                onFileSelected(fullPath);
            }
        });
    }

    private void toggleContextFile(TreeItem<String> item, String fullPath) {
        if (contextFiles.contains(fullPath)) {
            contextFiles.remove(fullPath);
            // Remove ☑ prefix from display
            String current = item.getValue();
            if (current.startsWith("☑ ")) {
                item.setValue(current.substring(2));
            }
        } else {
            contextFiles.add(fullPath);
            // Add ☑ prefix if not already there
            String current = item.getValue();
            if (!current.startsWith("☑ ")) {
                item.setValue("☑ " + current);
            }
        }
        // Refresh cell styles
        fileTree.refresh();
        updateContextLabel();
    }

    @FXML
    private void clearContextFiles() {
        clearContextMarkers(fileTree.getRoot());
        contextFiles.clear();
        fileTree.refresh();
        updateContextLabel();
        setStatus("Context selection cleared");
    }

    private void clearContextMarkers(TreeItem<String> node) {
        if (node == null) return;
        if (node.isLeaf()) {
            String val = node.getValue();
            if (val != null && val.startsWith("☑ ")) {
                node.setValue(val.substring(2));
            }
        }
        for (TreeItem<String> child : node.getChildren()) {
            clearContextMarkers(child);
        }
    }

    private void updateContextLabel() {
        int count = contextFiles.size();
        contextCountLabel.setText(count == 0 ? "None selected" : count + " file(s) as context");
    }

    @FXML
    private void filterFiles() {
        String filter = fileFilterField.getText().toLowerCase();
        if (filter.isBlank()) {
            buildFileTree(allProjectFiles);
            fileCountLabel.setText(allProjectFiles.size() + " files");
        } else {
            List<String> filtered = allProjectFiles.stream()
                    .filter(f -> f.toLowerCase().contains(filter))
                    .collect(Collectors.toList());
            buildFileTree(filtered);
            fileCountLabel.setText(filtered.size() + " / " + allProjectFiles.size() + " files");
        }
    }

    private void onFileSelected(String fullRelPath) {
        if (fullRelPath == null) return;
        selectedFilePath = fullRelPath;
        selectedFileLabel.setText(fullRelPath);
        setStatus("Loading " + fullRelPath + "...");

        String root = projectRootField.getText().trim();
        backend.readFile(root, fullRelPath).thenAccept(content -> Platform.runLater(() -> {
            originalCodeArea.setText(content);
            int lines = content.split("\n").length;
            fileSizeLabel.setText(lines + " lines · " + content.length() + " chars");
            setStatus("Loaded: " + fullRelPath);
        })).exceptionally(e -> {
            Platform.runLater(() -> {
                originalCodeArea.setText("// Error loading file: " + e.getMessage());
                setStatus("Error loading file");
            });
            return null;
        });
    }

    // ── Presets ───────────────────────────────────────────────────────────────

    @FXML
    private void applyPreset(javafx.event.ActionEvent event) {
        Button btn = (Button) event.getSource();
        promptArea.setText((String) btn.getUserData());
        promptArea.requestFocus();
    }

    @FXML
    private void clearPrompt() {
        promptArea.clear();
    }

    // ── Refactor ──────────────────────────────────────────────────────────────

    @FXML
    private void runOptimize() {
        AtomicBoolean doneHandled = new AtomicBoolean(false);

        String root     = projectRootField.getText().trim();
        String prompt   = promptArea.getText().trim();
        String provider = providerCombo.getValue();
        String model    = modelCombo.getValue();

        if (root.isBlank())            { showError("No project root set.");    return; }
        if (selectedFilePath == null)  { showError("No file selected.");       return; }
        if (prompt.isBlank())          { showError("Please enter a prompt.");  return; }

        // Clear output and show thinking overlay
        clearOutput();
        setThinkingState(true);
        setStatus("Connecting to " + provider + " (" + model + ")...");

        // Build context summary for the overlay label
        if (!contextFiles.isEmpty()) {
            contextFilesLabel.setText("Context: " + contextFiles.size() + " file(s) included");
        } else {
            contextFilesLabel.setText("");
        }

        String providerOverride = provider.toLowerCase();

        backend.streamRefactor(
                root,
                selectedFilePath,
                contextFiles.stream().filter(f -> !f.equals(selectedFilePath)).collect(Collectors.toList()),
                prompt,
                providerOverride,
                model,

                // onChunk
                chunk -> Platform.runLater(() -> {
                    if (thinkingOverlay.isVisible()) {
                        setThinkingState(false);
                    }
                    streamBuffer.append(chunk);
                    outputRenderer.streamUpdate(streamBuffer.toString());
                    statusLabel.setText("Streaming... " + streamBuffer.length() + " chars");
                }),

                // onDone
                () -> {
                    if (doneHandled.compareAndSet(false, true)) {
                        Platform.runLater(() -> {
                            lastRawOutput = streamBuffer.toString();
                            outputRenderer.finalRender(lastRawOutput);
                            streamBuffer.setLength(0);
                            copyBtn.setDisable(false);
                            formatBtn.setDisable(false);
                            optimizeBtn.setDisable(false);
                            setStatus("Done! Review the output on the right.");
                        });
                    }
                },

                // onError
                errorMsg -> Platform.runLater(() -> {
                    setThinkingState(false);
                    optimizeBtn.setDisable(false);
                    showError(errorMsg);
                    setStatus("Error: " + errorMsg);
                })
        );

        optimizeBtn.setDisable(true);
    }

    // ── Copy / Format ─────────────────────────────────────────────────────────

    @FXML
    private void copyOutput() {
        if (lastRawOutput == null) return;
        javafx.scene.input.Clipboard clipboard = javafx.scene.input.Clipboard.getSystemClipboard();
        javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
        content.putString(lastRawOutput);
        clipboard.setContent(content);
        setStatus("Output copied to clipboard ✓");
    }

    @FXML
    private void formatOutput() {
        if (lastRawOutput == null || lastRawOutput.isBlank()) return;

        String formatted = outputRenderer.formatCodeBlocks(lastRawOutput);
        lastRawOutput = formatted;
        outputRenderer.finalRender(lastRawOutput);
        setStatus("Code blocks formatted ✓");
    }

    // ── Misc Settings ─────────────────────────────────────────────────────────

    @FXML
    private void openSettings() {
        Alert info = new Alert(Alert.AlertType.INFORMATION);
        info.setTitle("Settings");
        info.setHeaderText("YoCoder Configuration");
        info.setContentText(
                "API Key: Set API_KEY in your .env file\n" +
                        "Backend URL: " + API_URL + "\n" +
                        "Currently selected model: " + modelCombo.getValue() + "\n\n" +
                        "Context files: Ctrl+click files in the tree to\n" +
                        "include them as context for the AI.\n\n" +
                        "To change the model, select from the dropdown in the top bar."
        );
        info.showAndWait();
    }

    // ── Provider and Models ───────────────────────────────────────────────────

    @FXML
    private void onProviderChanged() {
        String provider = providerCombo.getValue();
        modelCombo.getItems().setAll(PROVIDER_MODELS.get(provider));
        modelCombo.setValue(modelCombo.getItems().get(0));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void setThinkingState(boolean thinking) {
        thinkingOverlay.setVisible(thinking);
        thinkingOverlay.setManaged(thinking);
        outputWebView.setVisible(!thinking);
        outputWebView.setManaged(!thinking);
        optimizeBtn.setDisable(thinking);
    }

    private void clearOutput() {
        outputRenderer.clear();
        copyBtn.setDisable(true);
        formatBtn.setDisable(true);
        lastRawOutput = null;
        streamBuffer.setLength(0);
    }

    private void setStatus(String msg) {
        statusLabel.setText(msg);
    }

    private void showError(String msg) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("Error");
        alert.setHeaderText(null);
        alert.setContentText(msg);
        alert.showAndWait();
    }

    private String iconFor(String filename) {
        String ext = filename.contains(".") ?
                filename.substring(filename.lastIndexOf('.')).toLowerCase() : "";
        return switch (ext) {
            case ".java"  -> "☕";
            case ".php"   -> "🐘";
            case ".js", ".jsx" -> "🟨";
            case ".ts", ".tsx" -> "🔷";
            case ".vue"   -> "💚";
            case ".py"    -> "🐍";
            case ".go"    -> "🐹";
            case ".kt"    -> "🟣";
            case ".rb"    -> "💎";
            case ".rs"    -> "🦀";
            case ".html"  -> "🌐";
            case ".css", ".scss" -> "🎨";
            case ".xml"   -> "📋";
            case ".json"  -> "📦";
            case ".sql"   -> "🗄";
            case ".yaml", ".yml" -> "⚙";
            default       -> "📄";
        };
    }

    /** Reconstructs the full relative path from a tree item by walking up to root. */
    private String getFullPath(TreeItem<String> item) {
        List<String> parts = new ArrayList<>();
        TreeItem<String> current = item;
        while (current != null && current.getParent() != null) { // skip root
            String val = current.getValue()
                    .replaceFirst("^☑ ", "")      // strip context marker
                    .replaceFirst("^[^ ]+ ", "");  // strip emoji prefix
            parts.add(0, val);
            current = current.getParent();
        }
        String reconstructed = String.join("/", parts);
        return allProjectFiles.stream()
                .filter(f -> f.endsWith(reconstructed) || f.equals(reconstructed))
                .findFirst()
                .orElse(reconstructed);
    }

    private static final Map<String, List<String>> PROVIDER_MODELS = Map.of(
            "Claude", List.of(
                    "claude-haiku-4-5-20251001",
                    "claude-sonnet-4-20250514",
                    "claude-opus-4-20250514"
            ),
            "OpenAI", List.of(
                    "gpt-4o-mini",
                    "gpt-4o",
                    "gpt-4-turbo"
            ),
            "Gemini", List.of(
                    "gemini-2.5-flash",
                    "gemini-2.5-flash-lite",
                    "gemini-2.0-flash",
                    "gemini-2.0-flash-lite",
                    "gemini-1.5-flash"
            )
    );
}