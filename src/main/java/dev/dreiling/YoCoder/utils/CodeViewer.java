package dev.dreiling.YoCoder.utils;

import javafx.scene.web.WebView;

public class CodeViewer {

    private final WebView webView;
    private boolean pageLoaded = false;

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
                    font-family: 'JetBrains Mono', 'Cascadia Code', 'Fira Code', 'Consolas', monospace;
                    font-size: 12.5px;
                    line-height: 1.7;
                }
                pre {
                    padding: 14px 18px;
                    /* pre preserves all whitespace and newlines exactly as given */
                    white-space: pre;
                    word-wrap: normal;
                    overflow-x: auto;
                    min-height: 100vh;
                    /* tab-size controls how wide a literal tab character renders */
                    tab-size: 4;
                    -moz-tab-size: 4;
                }
                /* Syntax highlight colours */
                .kw  { color: #cba6f7; }  /* keywords */
                .cm  { color: #6c7086; font-style: italic; } /* comments */
                .st  { color: #a6e3a1; }  /* strings */
                .an  { color: #fab387; }  /* annotations / decorators */
                .nm  { color: #89dceb; }  /* type names / class names */
                .nu  { color: #fab387; }  /* numbers */
                .ph  { color: #45475a; }  /* placeholder */
                /* Scrollbars */
                ::-webkit-scrollbar { width: 8px; height: 8px; }
                ::-webkit-scrollbar-track { background: #0d1117; }
                ::-webkit-scrollbar-thumb { background: #3d3d5c; border-radius: 3px; }
                ::-webkit-scrollbar-thumb:hover { background: #6c7086; }
            </style>
            </head>
            <body>
            <pre id="code"><span class="ph">Refactored code will appear here after you click Optimize...</span></pre>
            <script>
                /**
                 * streamChunk: called repeatedly during streaming with the growing full text.
                 * We use textContent (not innerHTML) so special chars are safe, but we must
                 * first clear the element so the browser does not double-encode entities.
                 */
                function streamChunk(text) {
                    var el = document.getElementById('code');
                    el.textContent = text;
                    window.scrollTo(0, document.body.scrollHeight);
                }
 
                /**
                 * setHighlighted: called once after streaming finishes with the
                 * syntax-highlighted HTML string (spans + escaped entities).
                 * Uses innerHTML because the Java side has already HTML-escaped the content.
                 */
                function setHighlighted(html) {
                    document.getElementById('code').innerHTML = html;
                }
            </script>
            </body>
            </html>
            """;

    public CodeViewer(WebView webView) {
        this.webView = webView;
        webView.setContextMenuEnabled(false);
        webView.getEngine().loadContent(BASE_PAGE);
        // Track when page is ready for JS calls
        webView.getEngine().getLoadWorker().stateProperty().addListener((obs, oldState, newState) -> {
            if (newState == javafx.concurrent.Worker.State.SUCCEEDED) {
                pageLoaded = true;
            }
        });
    }

    /**
     * During streaming: inject text via JS using textContent assignment.
     * textContent is safe — the browser treats the value as plain text, so no
     * HTML-escaping is needed and indentation / special chars are preserved exactly.
     */
    public void streamUpdate(String fullText) {
        if (!pageLoaded) return;
        // We pass the text inside a JS template literal, so we must escape only
        // the three characters that are special inside template literals.
        String escaped = escapeForTemplateLiteral(fullText);
        webView.getEngine().executeScript("streamChunk(`" + escaped + "`)");
    }

    /**
     * Final render after streaming completes — applies syntax highlighting.
     */
    public void setCode(String code) {
        if (!pageLoaded) {
            // Page not ready yet — fall back to full reload
            webView.getEngine().loadContent(BASE_PAGE.replace(
                    "<span class=\"ph\">Refactored code will appear here after you click Optimize...</span>",
                    escapeHtml(code)
            ));
            return;
        }
        String highlighted = highlight(escapeHtml(code));
        // Escape for JS template literal
        String escaped = escapeForTemplateLiteral(highlighted);
        webView.getEngine().executeScript("setHighlighted(`" + escaped + "`)");
    }

    /**
     * Clears the WebView back to the placeholder state.
     */
    public void clear() {
        if (pageLoaded) {
            webView.getEngine().executeScript(
                    "document.getElementById('code').innerHTML = '<span class=\"ph\">Refactored code will appear here after you click Optimize...</span>';"
            );
        } else {
            webView.getEngine().loadContent(BASE_PAGE);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Basic syntax highlighter
    //  Operates on already-HTML-escaped text, wraps tokens in <span> tags.
    //  Handles: keywords, comments (// and /* */), strings, annotations,
    //           class/type names (PascalCase), numbers.
    // ─────────────────────────────────────────────────────────────────────────

    private String highlight(String code) {
        // Process line by line to handle single-line comments correctly
        String[] lines = code.split("\n", -1);
        StringBuilder result = new StringBuilder();
        boolean inBlockComment = false;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            StringBuilder out = new StringBuilder();
            int pos = 0;
            int len = line.length();

            while (pos < len) {
                // Inside block comment
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

                // Single-line comment //
                if (pos + 1 < len && line.charAt(pos) == '/' && line.charAt(pos + 1) == '/') {
                    out.append("<span class='cm'>").append(line.substring(pos)).append("</span>");
                    pos = len;
                    continue;
                }

                // Block comment start /*
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

                // Annotation / decorator @Something
                if (line.charAt(pos) == '@') {
                    int end = pos + 1;
                    while (end < len && (Character.isLetterOrDigit(line.charAt(end)) || line.charAt(end) == '_')) end++;
                    out.append("<span class='an'>").append(line, pos, end).append("</span>");
                    pos = end;
                    continue;
                }

                // String literal (double or single quote)
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

                // Number
                if (Character.isDigit(ch)) {
                    int end = pos;
                    while (end < len && (Character.isDigit(line.charAt(end)) || line.charAt(end) == '.' || line.charAt(end) == 'L' || line.charAt(end) == 'f')) end++;
                    out.append("<span class='nu'>").append(line, pos, end).append("</span>");
                    pos = end;
                    continue;
                }

                // Word (keyword, class name, identifier)
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

                // Whitespace (space, tab) — output as-is; the <pre> tag preserves it
                if (Character.isWhitespace(ch)) {
                    out.append(ch);
                    pos++;
                    continue;
                }

                // Everything else — output as-is
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
            // Java
            case "abstract", "assert", "boolean", "break", "byte", "case", "catch",
                 "char", "class", "const", "continue", "default", "do", "double",
                 "else", "enum", "extends", "final", "finally", "float", "for",
                 "goto", "if", "implements", "import", "instanceof", "int", "interface",
                 "long", "native", "new", "package", "private", "protected", "public",
                 "return", "short", "static", "strictfp", "super", "switch", "synchronized",
                 "this", "throw", "throws", "transient", "try", "var", "void", "volatile",
                 "while", "record", "sealed", "permits", "yield",
                 // values
                 "true", "false", "null",
                 // PHP
                 "function", "echo", "require", "include", "use", "namespace", "fn",
                 // JS/TS
                 "let", "async", "await", "typeof", "of",
                 "export", "from", "type", "readonly", "declare",
                 // Python
                 "def", "lambda", "with", "as", "pass", "del", "raise", "except",
                 "global", "nonlocal", "and", "or", "not", "in", "is",
                 // Go
                 "func", "defer", "go", "chan", "map", "range", "select", "struct" -> true;
            default -> false;
        };
    }

    private String escapeHtml(String text) {
        if (text == null) return "";
        return text
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    /**
     * Escapes a string for safe embedding inside a JavaScript template literal (`...`).
     * Only three characters are special inside template literals:
     *   backslash  \   →  \\
     *   backtick   `   →  \`
     *   dollar     $   →  \$   (prevents ${...} interpolation)
     *
     * NOTE: we do NOT escape newlines or tabs — they must pass through verbatim
     * so the browser renders indentation correctly inside the <pre> element.
     */
    private String escapeForTemplateLiteral(String text) {
        if (text == null) return "";
        return text
                .replace("\\", "\\\\")
                .replace("`", "\\`")
                .replace("$", "\\$");
    }

    /**
     * Re-indents code that has had its indentation stripped by the model.
     * Uses brace/bracket depth tracking to compute the correct indent per line.
     *
     * If the code already appears to be indented (>10% of non-blank lines start
     * with whitespace), returns it unchanged — no double-formatting.
     *
     * @param code the raw code string, possibly with stripped indentation
     * @return re-indented code using 4-space indentation
     */
    public String formatCode(String code) {
        if (code == null || code.isBlank()) return code;

        // Check if already indented — if so, just re-render and return
        String[] lines = code.split("\n", -1);
        long nonBlank = 0, alreadyIndented = 0;
        for (String line : lines) {
            if (line.isBlank()) continue;
            nonBlank++;
            if (line.charAt(0) == ' ' || line.charAt(0) == '\t') alreadyIndented++;
        }
        if (nonBlank > 0 && (double) alreadyIndented / nonBlank > 0.10) {
            // Already indented — just refresh the highlighted render
            setCode(code);
            return code;
        }

        // Re-indent using brace depth tracking
        String indent = "    "; // 4 spaces
        int depth = 0;
        StringBuilder result = new StringBuilder();

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].strip();

            if (line.isEmpty()) {
                result.append("\n");
                continue;
            }

            // Closing braces/brackets dedent BEFORE this line
            int closingLeaders = countLeadingClosers(line);
            depth = Math.max(0, depth - closingLeaders);

            // Build the indented line
            result.append(indent.repeat(depth)).append(line);
            if (i < lines.length - 1) result.append("\n");

            // Opening braces/brackets indent the NEXT line
            // Net change = openers - closers already applied above
            int netOpen = countNetOpeners(line) + closingLeaders; // re-add what we subtracted
            depth = Math.max(0, depth + netOpen);
        }

        String formatted = result.toString();
        setCode(formatted);
        return formatted;
    }

    /** Counts how many of the line's leading characters are closing braces/brackets. */
    private int countLeadingClosers(String line) {
        int count = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '}' || c == ')' || c == ']') count++;
            else break;
        }
        return count;
    }

    /**
     * Net openers = (opening braces in line) - (closing braces in line),
     * ignoring those inside strings and comments.
     * Used to determine depth change for the NEXT line.
     */
    private int countNetOpeners(String line) {
        int net = 0;
        boolean inString = false;
        char stringChar = 0;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);

            // Skip line comments
            if (!inString && c == '/' && i + 1 < line.length() && line.charAt(i + 1) == '/') break;

            // Track string boundaries
            if (!inString && (c == '"' || c == '\'')) {
                inString = true;
                stringChar = c;
                continue;
            }
            if (inString && c == '\\') { i++; continue; } // escaped char
            if (inString && c == stringChar) { inString = false; continue; }
            if (inString) continue;

            if (c == '{' || c == '(' || c == '[') net++;
            else if (c == '}' || c == ')' || c == ']') net--;
        }
        return net;
    }
}