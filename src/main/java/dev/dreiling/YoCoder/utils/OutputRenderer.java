package dev.dreiling.YoCoder.utils;

import javafx.scene.web.WebView;

import java.util.ArrayList;
import java.util.List;

/**
 * Renders AI output that mixes plain text and ##FILE: code sections into a WebView.
 *
 * During streaming, the raw text is injected as plain text for speed.
 * After streaming completes, {@link #finalRender(String)} parses ##FILE: blocks,
 * syntax-highlights the code sections, and renders everything as styled HTML.
 *
 * Format the AI is expected to produce:
 *   Some explanation text...
 *
 *   ##FILE: path/to/File.java
 *   public class Foo { ... }
 *
 *   More explanation...
 *
 *   ##FILE: path/to/Other.java
 *   public class Bar { ... }
 */
public class OutputRenderer {

    private final WebView webView;
    private boolean pageLoaded = false;

    // ── Base page ─────────────────────────────────────────────────────────────

    private static final String BASE_PAGE = """
            <!DOCTYPE html>
            <html>
            <head>
            <meta charset="UTF-8">
            <style>
                * { margin: 0; padding: 0; box-sizing: border-box; }
                html, body {
                    background: #0d1117;
                    color: #cdd6f4;
                    font-family: 'Segoe UI', 'Inter', 'SF Pro Text', sans-serif;
                    font-size: 13px;
                    line-height: 1.7;
                    padding: 14px 18px;
                }
                /* Plain text / prose */
                .prose {
                    white-space: pre-wrap;
                    word-break: break-word;
                    color: #cdd6f4;
                    margin-bottom: 8px;
                }
                /* File block wrapper */
                .file-block {
                    margin: 14px 0 10px 0;
                    border: 1px solid #3d3d5c;
                    border-radius: 6px;
                    overflow: hidden;
                }
                /* File header bar */
                .file-header {
                    background: #1e1e3a;
                    color: #89dceb;
                    font-family: 'JetBrains Mono', 'Cascadia Code', 'Fira Code', 'Consolas', monospace;
                    font-size: 11.5px;
                    padding: 5px 12px;
                    border-bottom: 1px solid #3d3d5c;
                    display: flex;
                    align-items: center;
                    gap: 8px;
                }
                .file-header::before { content: '📄'; font-size: 12px; }
                /* Code block */
                pre.code-block {
                    background: #11111b;
                    padding: 12px 16px;
                    white-space: pre;
                    word-wrap: normal;
                    overflow-x: auto;
                    tab-size: 4;
                    -moz-tab-size: 4;
                    font-family: 'JetBrains Mono', 'Cascadia Code', 'Fira Code', 'Consolas', monospace;
                    font-size: 12.5px;
                    line-height: 1.7;
                    margin: 0;
                }
                /* Streaming placeholder */
                #streaming-pre {
                    background: #11111b;
                    border-radius: 6px;
                    padding: 12px 16px;
                    white-space: pre-wrap;
                    font-family: 'JetBrains Mono', 'Cascadia Code', 'Fira Code', 'Consolas', monospace;
                    font-size: 12.5px;
                    line-height: 1.7;
                    color: #cdd6f4;
                    display: none;
                }
                .placeholder { color: #45475a; }
                /* Syntax highlight colours */
                .kw  { color: #cba6f7; }
                .cm  { color: #6c7086; font-style: italic; }
                .st  { color: #a6e3a1; }
                .an  { color: #fab387; }
                .nm  { color: #89dceb; }
                .nu  { color: #fab387; }
                /* Scrollbars */
                ::-webkit-scrollbar { width: 8px; height: 8px; }
                ::-webkit-scrollbar-track { background: #0d1117; }
                ::-webkit-scrollbar-thumb { background: #3d3d5c; border-radius: 3px; }
                ::-webkit-scrollbar-thumb:hover { background: #6c7086; }
            </style>
            </head>
            <body>
            <pre id="streaming-pre"></pre>
            <div id="output">
                <span class="placeholder">AI output will appear here after you click Optimize...</span>
            </div>
            <script>
                /**
                 * During streaming: show raw text in the streaming-pre element for speed.
                 */
                function streamChunk(text) {
                    var sp = document.getElementById('streaming-pre');
                    var out = document.getElementById('output');
                    sp.style.display = 'block';
                    out.style.display = 'none';
                    sp.textContent = text;
                    window.scrollTo(0, document.body.scrollHeight);
                }

                /**
                 * After streaming: inject the fully parsed+highlighted HTML.
                 */
                function setRendered(html) {
                    document.getElementById('streaming-pre').style.display = 'none';
                    var out = document.getElementById('output');
                    out.style.display = 'block';
                    out.innerHTML = html;
                }

                /**
                 * Clear back to placeholder.
                 */
                function resetOutput() {
                    document.getElementById('streaming-pre').style.display = 'none';
                    var out = document.getElementById('output');
                    out.style.display = 'block';
                    out.innerHTML = '<span class="placeholder">AI output will appear here after you click Optimize...</span>';
                }
            </script>
            </body>
            </html>
            """;

    // ── Constructor ───────────────────────────────────────────────────────────

    public OutputRenderer(WebView webView) {
        this.webView = webView;
        webView.setContextMenuEnabled(false);
        webView.getEngine().loadContent(BASE_PAGE);
        webView.getEngine().getLoadWorker().stateProperty().addListener((obs, oldState, newState) -> {
            if (newState == javafx.concurrent.Worker.State.SUCCEEDED) {
                pageLoaded = true;
            }
        });
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Fast live update during streaming — just dumps raw text, no parsing.
     */
    public void streamUpdate(String fullText) {
        if (!pageLoaded) return;
        String escaped = escapeForTemplateLiteral(fullText);
        webView.getEngine().executeScript("streamChunk(`" + escaped + "`)");
    }

    /**
     * Called once streaming finishes. Parses the full output into prose + ##FILE:
     */
    public void finalRender(String rawOutput) {
        if (!pageLoaded) return;
        String html = buildHtml(rawOutput);
        String escaped = escapeForTemplateLiteral(html);
        webView.getEngine().executeScript("setRendered(`" + escaped + "`)");
    }

    /**
     * Re-formats code blocks only (re-indents stripped code), then re-renders.
     */
    public String formatCodeBlocks(String rawOutput) {
        if (rawOutput == null || rawOutput.isBlank()) return rawOutput;
        List<Segment> segments = parse(rawOutput);
        StringBuilder result = new StringBuilder();
        for (Segment seg : segments) {
            if (seg.isCode) {
                result.append("##FILE: ").append(seg.filePath).append("\n");
                result.append(reindent(seg.content));
            } else {
                result.append(seg.content);
            }
        }
        return result.toString();
    }

    /**
     * Resets the WebView to the placeholder state.
     */
    public void clear() {
        if (pageLoaded) {
            webView.getEngine().executeScript("resetOutput()");
        } else {
            webView.getEngine().loadContent(BASE_PAGE);
        }
    }

    // ── Parsing ───────────────────────────────────────────────────────────────

    private record Segment(boolean isCode, String filePath, String content) {}

    /**
     * Splits the raw AI output into alternating prose/code segments.
     * Splits on lines starting with "##FILE:".
     */
    private List<Segment> parse(String raw) {
        List<Segment> segments = new ArrayList<>();
        String[] lines = raw.split("\n", -1);

        StringBuilder current = new StringBuilder();
        String currentFilePath = null;
        boolean inCodeBlock = false;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.startsWith("##FILE:")) {
                if (current.length() > 0) {
                    String content = current.toString();
                    if (inCodeBlock) {
                        segments.add(new Segment(true, currentFilePath, content));
                    } else if (!content.isBlank()) {
                        segments.add(new Segment(false, null, content));
                    }
                    current.setLength(0);
                }
                currentFilePath = line.substring("##FILE:".length()).trim();
                inCodeBlock = true;
            } else if (inCodeBlock && line.equals("##ENDFILE")) {
                segments.add(new Segment(true, currentFilePath, current.toString()));
                current.setLength(0);
                inCodeBlock = false;
                currentFilePath = null;
            } else {
                current.append(line);
                if (i < lines.length - 1) current.append("\n");
            }
        }
        // Flush remainder
        if (current.length() > 0) {
            String content = current.toString();
            if (inCodeBlock) {
                segments.add(new Segment(true, currentFilePath, content));
            } else if (!content.isBlank()) {
                segments.add(new Segment(false, null, content));
            }
        }
        return segments;
    }

    // ── HTML Building ─────────────────────────────────────────────────────────

    private String buildHtml(String rawOutput) {
        List<Segment> segments = parse(rawOutput);
        StringBuilder html = new StringBuilder();

        for (Segment seg : segments) {
            if (seg.isCode) {
                html.append("<div class=\"file-block\">");
                html.append("<div class=\"file-header\">").append(escapeHtml(seg.filePath)).append("</div>");
                html.append("<pre class=\"code-block\">");
                html.append(highlight(escapeHtml(seg.content.stripTrailing())));
                html.append("</pre></div>\n");
            } else {
                // Trim trailing/leading blank lines from prose segments but keep inner spacing
                String prose = seg.content.strip();
                if (!prose.isBlank()) {
                    html.append("<div class=\"prose\">").append(escapeHtml(prose)).append("</div>\n");
                }
            }
        }

        if (html.isEmpty()) {
            html.append("<span style=\"color:#45475a\">(empty response)</span>");
        }
        return html.toString();
    }

    // ── Syntax Highlighter ────────────────────────────────────────────────────

    private String highlight(String code) {
        String[] lines = code.split("\n", -1);
        StringBuilder result = new StringBuilder();
        boolean inBlockComment = false;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            StringBuilder out = new StringBuilder();
            int pos = 0;
            int len = line.length();

            while (pos < len) {
                if (inBlockComment) {
                    int end = line.indexOf("*/", pos);
                    if (end >= 0) {
                        out.append("<span class='cm'>").append(line, pos, end + 2).append("</span>");
                        pos = end + 2;
                        inBlockComment = false;
                    } else {
                        out.append("<span class='cm'>").append(line.substring(pos)).append("</span>");
                        pos = len;
                    }
                    continue;
                }
                if (pos + 1 < len && line.charAt(pos) == '/' && line.charAt(pos + 1) == '/') {
                    out.append("<span class='cm'>").append(line.substring(pos)).append("</span>");
                    pos = len;
                    continue;
                }
                if (pos + 1 < len && line.charAt(pos) == '/' && line.charAt(pos + 1) == '*') {
                    int end = line.indexOf("*/", pos + 2);
                    if (end >= 0) {
                        out.append("<span class='cm'>").append(line, pos, end + 2).append("</span>");
                        pos = end + 2;
                    } else {
                        out.append("<span class='cm'>").append(line.substring(pos)).append("</span>");
                        inBlockComment = true;
                        pos = len;
                    }
                    continue;
                }
                if (line.charAt(pos) == '@') {
                    int end = pos + 1;
                    while (end < len && (Character.isLetterOrDigit(line.charAt(end)) || line.charAt(end) == '_')) end++;
                    out.append("<span class='an'>").append(line, pos, end).append("</span>");
                    pos = end;
                    continue;
                }
                char ch = line.charAt(pos);
                if (ch == '"' || ch == '\'') {
                    int end = pos + 1;
                    while (end < len) {
                        if (line.charAt(end) == '\\') { end += 2; continue; }
                        if (line.charAt(end) == ch) { end++; break; }
                        end++;
                    }
                    out.append("<span class='st'>").append(line, pos, end).append("</span>");
                    pos = end;
                    continue;
                }
                if (Character.isDigit(ch)) {
                    int end = pos;
                    while (end < len && (Character.isDigit(line.charAt(end)) || line.charAt(end) == '.' || line.charAt(end) == 'L' || line.charAt(end) == 'f')) end++;
                    out.append("<span class='nu'>").append(line, pos, end).append("</span>");
                    pos = end;
                    continue;
                }
                if (Character.isLetter(ch) || ch == '_') {
                    int end = pos;
                    while (end < len && (Character.isLetterOrDigit(line.charAt(end)) || line.charAt(end) == '_')) end++;
                    String word = line.substring(pos, end);
                    if (isKeyword(word)) {
                        out.append("<span class='kw'>").append(word).append("</span>");
                    } else if (Character.isUpperCase(word.charAt(0)) && word.length() > 1) {
                        out.append("<span class='nm'>").append(word).append("</span>");
                    } else {
                        out.append(word);
                    }
                    pos = end;
                    continue;
                }
                out.append(ch);
                pos++;
            }

            result.append(out);
            if (i < lines.length - 1) result.append("\n");
        }
        return result.toString();
    }

    private boolean isKeyword(String word) {
        return switch (word) {
            case "abstract", "assert", "boolean", "break", "byte", "case", "catch",
                 "char", "class", "const", "continue", "default", "do", "double",
                 "else", "enum", "extends", "final", "finally", "float", "for",
                 "goto", "if", "implements", "import", "instanceof", "int", "interface",
                 "long", "native", "new", "package", "private", "protected", "public",
                 "return", "short", "static", "strictfp", "super", "switch", "synchronized",
                 "this", "throw", "throws", "transient", "try", "var", "void", "volatile",
                 "while", "record", "sealed", "permits", "yield",
                 "true", "false", "null",
                 "function", "echo", "require", "include", "use", "namespace", "fn",
                 "let", "async", "await", "typeof", "of", "export", "from", "type",
                 "readonly", "declare",
                 "def", "lambda", "with", "as", "pass", "del", "raise", "except",
                 "global", "nonlocal", "and", "or", "not", "in", "is",
                 "func", "defer", "go", "chan", "map", "range", "select", "struct" -> true;
            default -> false;
        };
    }

    // ── Re-indenter (format code blocks only) ────────────────────────────────

    private String reindent(String code) {
        if (code == null || code.isBlank()) return code;
        String[] lines = code.split("\n", -1);

        // If already indented (>10% of non-blank lines start with whitespace), return as-is
        long nonBlank = 0, alreadyIndented = 0;
        for (String line : lines) {
            if (line.isBlank()) continue;
            nonBlank++;
            if (!line.isEmpty() && (line.charAt(0) == ' ' || line.charAt(0) == '\t')) alreadyIndented++;
        }
        if (nonBlank > 0 && (double) alreadyIndented / nonBlank > 0.10) return code;

        String indent = "    ";
        int depth = 0;
        StringBuilder result = new StringBuilder();

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].strip();
            if (line.isEmpty()) { result.append("\n"); continue; }

            int closingLeaders = countLeadingClosers(line);
            depth = Math.max(0, depth - closingLeaders);

            result.append(indent.repeat(depth)).append(line);
            if (i < lines.length - 1) result.append("\n");

            int netOpen = countNetOpeners(line) + closingLeaders;
            depth = Math.max(0, depth + netOpen);
        }
        return result.toString();
    }

    private int countLeadingClosers(String line) {
        int count = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '}' || c == ')' || c == ']') count++;
            else break;
        }
        return count;
    }

    private int countNetOpeners(String line) {
        int net = 0;
        boolean inString = false;
        char stringChar = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (!inString && c == '/' && i + 1 < line.length() && line.charAt(i + 1) == '/') break;
            if (!inString && (c == '"' || c == '\'')) { inString = true; stringChar = c; continue; }
            if (inString && c == '\\') { i++; continue; }
            if (inString && c == stringChar) { inString = false; continue; }
            if (inString) continue;
            if (c == '{' || c == '(' || c == '[') net++;
            else if (c == '}' || c == ')' || c == ']') net--;
        }
        return net;
    }

    // ── String Escaping ───────────────────────────────────────────────────────

    private String escapeHtml(String text) {
        if (text == null) return "";
        return text
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private String escapeForTemplateLiteral(String text) {
        if (text == null) return "";
        return text
                .replace("\\", "\\\\")
                .replace("`", "\\`")
                .replace("$", "\\$");
    }
}