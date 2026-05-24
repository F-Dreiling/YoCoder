package dev.dreiling.YoCoder.controller;

import dev.dreiling.YoCoder.service.BackendClient;
import dev.dreiling.YoCoder.utils.EnvLoader;
import dev.dreiling.YoCoder.utils.CodeViewer;
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

    // Middle panel
    @FXML private Label selectedFileLabel;
    @FXML private Label fileSizeLabel;
    @FXML private TextArea originalCodeArea;
    @FXML private TextArea promptArea;
    @FXML private Label charCountLabel;
    @FXML private Label contextInfoLabel;
    @FXML private Button optimizeBtn;

    // Right panel
    @FXML private WebView outputWebView;
    @FXML private TextArea explanationArea;
    @FXML private VBox thinkingOverlay;
    @FXML private Label thinkingLabel;
    @FXML private Label contextFilesLabel;
    @FXML private ProgressIndicator progressIndicator;
    @FXML private Button copyBtn;
    @FXML private Button saveBtn;
    @FXML private Label contextUsedLabel;
    @FXML private Label promptCharsLabel;
    @FXML private HBox warningBar;
    @FXML private Label warningLabel;
    @FXML private ToggleButton codeViewBtn;
    @FXML private ToggleButton explainViewBtn;

    // Status bar
    @FXML private Label statusLabel;
    @FXML private Label tokenUsageLabel;

    // ── State ─────────────────────────────────────────────────────────────────

    private final BackendClient backend = new BackendClient(API_URL, API_KEY);
    private final Map<String, String> refactoredFiles = new LinkedHashMap<>(); // path -> content
    private List<String> allProjectFiles = new ArrayList<>();
    private String selectedFilePath = null;
    private String lastRefactoredCode = null;
    private String lastOriginalCode = null;
    private String currentOutputFile = null;

    // ── Others ────────────────────────────────────────────────────────────────

    private final StringBuilder streamBuffer = new StringBuilder();
    private CodeViewer codeViewer;

    // ── Initialise ────────────────────────────────────────────────────────────

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        providerCombo.getItems().addAll("Gemini", "Claude", "OpenAI");
        providerCombo.setValue("Gemini");
        onProviderChanged();

        // Prompt char counter
        promptArea.textProperty().addListener((obs, o, n) ->
                charCountLabel.setText(n.length() + " chars")
        );

        // File tree selection
        fileTree.getSelectionModel().selectedItemProperty().addListener((obs, oldItem, newItem) -> {
            if (newItem != null && newItem.isLeaf()) {
                onFileSelected(newItem.getValue(), getFullPath(newItem));
            }
        });

        // Populate ToggleGroup
        ToggleGroup viewModeGroup = new ToggleGroup();
        codeViewBtn.setToggleGroup(viewModeGroup);
        explainViewBtn.setToggleGroup(viewModeGroup);
        codeViewBtn.setSelected(true);

        // Check backend health on startup
        checkBackendHealth();

        codeViewer = new CodeViewer(outputWebView);

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
                    item.setExpanded(finalI == 0); // expand top level
                    return item;
                });
                if (!parent.getChildren().contains(dirItem)) {
                    parent.getChildren().add(dirItem);
                }
                parent = dirItem;
                pathBuilder.append("/");
            }

            String fileName = parts[parts.length - 1];
            TreeItem<String> fileItem = new TreeItem<>(iconFor(fileName) + " " + fileName);
            fileItem.setExpanded(false);
            parent.getChildren().add(fileItem);
        }

        fileTree.setRoot(root);
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

    private void onFileSelected(String displayName, String fullRelPath) {
        if (fullRelPath == null) return;
        selectedFilePath = fullRelPath;
        selectedFileLabel.setText(fullRelPath);
        setStatus("Loading " + fullRelPath + "...");

        String root = projectRootField.getText().trim();
        backend.readFile(root, fullRelPath).thenAccept(content -> Platform.runLater(() -> {
            originalCodeArea.setText(content);
            lastOriginalCode = content;
            int lines = content.split("\n").length;
            fileSizeLabel.setText(lines + " lines · " + content.length() + " chars");
            setStatus("Loaded: " + fullRelPath);

            clearOutput();
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
        setThinkingState(true);
        streamBuffer.setLength(0);
        lastRefactoredCode = null;
        setStatus("Connecting to " + provider + " (" + model + ")...");

        String providerOverride = provider.toLowerCase();

        backend.streamRefactor(
                root,
                selectedFilePath,
                prompt,
                providerOverride,
                model,

                // onChunk
                chunk -> Platform.runLater(() -> {
                    if (thinkingOverlay.isVisible()) {
                        setThinkingState(false);
                    }
                    streamBuffer.append(chunk);
                    codeViewer.setCode(streamBuffer.toString(), true);
                    statusLabel.setText("Streaming... " + streamBuffer.length() + " chars");
                }),

                // onDone
                () -> {
                    if (doneHandled.compareAndSet(false, true)) {
                        Platform.runLater(() -> {
                            lastRefactoredCode = streamBuffer.toString();
                            splitExplanationFromOutput();
                            streamBuffer.setLength(0);
                            copyBtn.setDisable(false);
                            saveBtn.setDisable(false);
                            optimizeBtn.setDisable(false);
                            setStatus("Done! Review the refactored code on the right.");
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

    private void splitExplanationFromOutput() {
        String full = lastRefactoredCode;

        // Fix literal \n if they slipped through
        if (!full.contains("\n") && full.contains("\\n")) {
            full = full.replace("\\n", "\n").replace("\\t", "\t");
        }

        // Split on ## EXPLANATION first
        int explIdx = full.indexOf("## EXPLANATION");
        String filesSection = explIdx >= 0 ? full.substring(0, explIdx).trim() : full.trim();
        String expl = explIdx >= 0 ? full.substring(explIdx + "## EXPLANATION".length()).trim()
                : "(No explanation section found in response)";

        // Parse ##FILE: markers
        refactoredFiles.clear();
        String[] parts = filesSection.split("(?=##FILE:)");
        for (String part : parts) {
            part = part.trim();
            if (part.startsWith("##FILE:")) {
                int newline = part.indexOf('\n');
                if (newline > 0) {
                    String filePath = part.substring("##FILE:".length(), newline).trim();
                    String content = part.substring(newline + 1).trim();
                    refactoredFiles.put(filePath, content);
                }
            }
        }

        if (refactoredFiles.isEmpty()) {
            // Fallback: no ##FILE: markers found — treat whole output as the target file
            refactoredFiles.put(selectedFilePath, filesSection);
        }

        // Show the first (or only) file in the code view
        currentOutputFile = refactoredFiles.keySet().iterator().next();
        lastRefactoredCode = refactoredFiles.get(currentOutputFile);
        codeViewer.setCode(lastRefactoredCode, false);

        // If multiple files were returned, show a notice in the explanation
        explanationArea.setText(expl + buildMultiFileNotice());
    }

    private String buildMultiFileNotice() {
        if (refactoredFiles.size() <= 1) return "";
        StringBuilder sb = new StringBuilder("\n\n── MULTIPLE FILES AFFECTED ──\n");
        sb.append("The following files were refactored. Use Save to write each one:\n\n");
        int i = 1;
        for (String path : refactoredFiles.keySet()) {
            sb.append(i++).append(". ").append(path).append("\n");
        }
        sb.append("\nTo save a different file, select it in the file tree first,\nthen click Save — it will use the correct refactored content.");
        return sb.toString();
    }

    // ── View Toggle ───────────────────────────────────────────────────────────

    @FXML private void showCodeView() { setOutputView("code"); }
    @FXML private void showExplainView() { setOutputView("explain"); }

    private void setOutputView(String mode) {
        outputWebView.setVisible("code".equals(mode));
        outputWebView.setManaged("code".equals(mode));
        explanationArea.setVisible("explain".equals(mode));
        explanationArea.setManaged("explain".equals(mode));
    }

    // ── Copy / Save ───────────────────────────────────────────────────────────

    @FXML
    private void copyOutput() {
        if (lastRefactoredCode == null) return;
        javafx.scene.input.Clipboard clipboard = javafx.scene.input.Clipboard.getSystemClipboard();
        javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
        content.putString(lastRefactoredCode);
        clipboard.setContent(content);
        setStatus("Refactored code copied to clipboard ✓");
    }

    @FXML
    private void saveToFile() {
        if (refactoredFiles.isEmpty()) return;

        if (refactoredFiles.size() > 1) {
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
            confirm.setTitle("Save Refactored Files");
            confirm.setHeaderText("Save " + refactoredFiles.size() + " refactored files?");
            confirm.setContentText(
                    "The following files will be overwritten:\n" +
                            String.join("\n", refactoredFiles.keySet()) +
                            "\n\nA .bak backup will be created for each.\n\nContinue?"
            );
            Optional<ButtonType> result = confirm.showAndWait();
            if (result.isEmpty() || result.get() != ButtonType.OK) return;

            String root = projectRootField.getText().trim();
            saveBtn.setDisable(true);
            setStatus("Saving " + refactoredFiles.size() + " files...");

            // Save all files sequentially
            List<String> paths = new ArrayList<>(refactoredFiles.keySet());
            saveNextFile(root, paths, 0);
        }
    }

    private void saveNextFile(String root, List<String> paths, int index) {
        if (index >= paths.size()) {
            Platform.runLater(() -> {
                saveBtn.setDisable(false);
                setStatus("✓ Saved " + paths.size() + " files successfully.");
            });
            return;
        }
        String path = paths.get(index);
        String content = refactoredFiles.get(path);
        backend.saveFile(root, path, content)
                .thenAccept(ok -> Platform.runLater(() -> {
                    if (!ok) {
                        showError("Failed to save: " + path);
                    }
                    saveNextFile(root, paths, index + 1);
                }));
    }

    // ── Misc Settings ─────────────────────────────────────────────────────────

    @FXML
    private void openSettings() {
        Alert info = new Alert(Alert.AlertType.INFORMATION);
        info.setTitle("Settings");
        info.setHeaderText("Refactor AI Configuration");
        info.setContentText(
                "API Key: Set REFACTORAI_CLAUDE_API_KEY env var\n" +
                        "or edit backend/src/main/resources/application.yml\n\n" +
                        "Backend URL: " + API_URL + "\n" +
                        "Currently selected model: " + modelCombo.getValue() + "\n\n" +
                        "To change the model, select from the dropdown in the top bar.\n" +
                        "(Model change requires backend restart if configured via yml.)"
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
        explanationArea.setVisible(false);
        explanationArea.setManaged(false);
        optimizeBtn.setDisable(thinking);
        warningBar.setVisible(false);
        warningBar.setManaged(false);
    }

    private void clearOutput() {
        codeViewer.clear();
        explanationArea.clear();
        copyBtn.setDisable(true);
        saveBtn.setDisable(true);
        contextUsedLabel.setText("");
        promptCharsLabel.setText("");
        warningBar.setVisible(false);
        warningBar.setManaged(false);
        lastRefactoredCode = null;
        refactoredFiles.clear();
        currentOutputFile = null;
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

    private String formatExplanation(String raw) {
        if (raw == null || raw.isBlank()) return "(No explanation provided)";
        return raw.strip();
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
                    .replaceFirst("^[^ ]+ ", ""); // strip emoji prefix
            parts.add(0, val);
            current = current.getParent();
        }
        // Match back to allProjectFiles for accuracy
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