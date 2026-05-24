package dev.dreiling.YoCoder.utils;

import javafx.scene.web.WebView;

public class CodeViewer {

    private final WebView webView;

    private static final String HTML_TEMPLATE = """
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
                    line-height: 1.6;
                    height: 100%%;
                }
                pre {
                    padding: 12px 16px;
                    white-space: pre;
                    word-wrap: normal;
                    overflow-x: auto;
                    min-height: 100vh;
                }
                /* Scrollbar styling */
                ::-webkit-scrollbar { width: 8px; height: 8px; }
                ::-webkit-scrollbar-track { background: #0d1117; }
                ::-webkit-scrollbar-thumb { background: #3d3d5c; border-radius: 3px; }
                ::-webkit-scrollbar-thumb:hover { background: #6c7086; }
            </style>
            </head>
            <body><pre id="code">%s</pre></body>
            </html>
            """;

    private static final String PLACEHOLDER_HTML = String.format(
            HTML_TEMPLATE,
            "<span style='color:#45475a'>Refactored code will appear here after you click Optimize...</span>"
    );

    public CodeViewer(WebView webView) {
        this.webView = webView;
        webView.setContextMenuEnabled(false);
        webView.getEngine().loadContent(PLACEHOLDER_HTML);
    }

    /**
     * Renders the given code string into the WebView.
     * Escapes HTML special characters to prevent rendering issues.
     * Scrolls to bottom if scrollToBottom is true (used during streaming).
     */
    public void setCode(String code, boolean scrollToBottom) {
        String escaped = escapeHtml(code);
        String html = String.format(HTML_TEMPLATE, escaped);
        webView.getEngine().loadContent(html);

        if (scrollToBottom) {
            webView.getEngine().executeScript(
                    "window.scrollTo(0, document.body.scrollHeight);"
            );
        }
    }

    public void clear() {
        webView.getEngine().loadContent(PLACEHOLDER_HTML);
    }

    public WebView getWebView() {
        return webView;
    }

    private String escapeHtml(String text) {
        if (text == null) return "";
        return text
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}