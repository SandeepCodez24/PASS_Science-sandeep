package language.language_ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.fxmisc.richtext.CodeArea;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import language.syntax.syntax_checker.ErrorChecker;
import ui.editor.FoldingManager;

public final class ErrorStatusManager {

    private static final String ERROR_BUTTON_KEY = "errorButton";
    private static final String WARNING_BUTTON_KEY = "warningButton";

    private ErrorStatusManager() {
        // Utility class
    }

    public static HBox createTopRightStatusBox() {
        Label errorButton = createStatusLabel("✖ 0");
        Label warningButton = createStatusLabel("⚠ 0");

        applyErrorButtonStyle(errorButton, 0);
        applyWarningButtonStyle(warningButton, 0);

        errorButton.setTooltip(new Tooltip(
                "Undefined variable errors: 0\nIncomplete line syntax errors: 0\nInvalid exponent syntax errors: 0\nSubscript/superscript errors: 0"));
        warningButton.setTooltip(new Tooltip("Docstring warnings: 0"));

        HBox box = new HBox(8, errorButton, warningButton);
        box.setAlignment(Pos.CENTER_RIGHT);
        box.getProperties().put(ERROR_BUTTON_KEY, errorButton);
        box.getProperties().put(WARNING_BUTTON_KEY, warningButton);
        return box;
    }

    public static HBox createLineNumberGraphic(CodeArea editor, int index, FoldingManager foldingManager) {
        if (editor.isFolded(index)) {
            HBox hidden = new HBox();
            hidden.setMinSize(0, 0);
            hidden.setPrefSize(0, 0);
            hidden.setMaxSize(0, 0);
            return hidden;
        }

        Label foldArrow = new Label();
        foldArrow.setMinWidth(12);
        foldArrow.setAlignment(Pos.CENTER);
        foldArrow.setStyle("-fx-font-size: 10px; -fx-text-fill: #858585;");

        if (foldingManager != null && foldingManager.isFoldStart(index)) {
            boolean collapsed = foldingManager.isCollapsed(editor, index);
            foldArrow.setText(collapsed ? "▸" : "▾");
            foldArrow.setStyle(foldArrow.getStyle() + " -fx-cursor: hand;");
            foldArrow.setTooltip(
                    new Tooltip(collapsed ? "Click to expand the range" : "Click to collapse the range"));
            foldArrow.setOnMouseClicked(e -> foldingManager.toggleFold(editor, index));
        }

        Label marker = new Label("●");
        Label lineNumber = new Label(String.valueOf(index + 1));

        marker.setMinWidth(6);
        lineNumber.setMinWidth(18);
        lineNumber.setAlignment(Pos.CENTER_RIGHT);
        marker.setAlignment(Pos.CENTER);

        String editorText = editor.getText();
        Set<Integer> undefinedLines = ErrorChecker.findUndefinedVariableErrorLines(editorText);
        Set<Integer> incompleteLines = ErrorChecker.findIncompleteLineSyntaxErrorLines(editorText);
        Set<Integer> invalidExponentLines = ErrorChecker.findInvalidExponentErrorLines(editorText);
        Set<Integer> subscriptSuperscriptLines = ErrorChecker.findSubscriptSuperscriptErrorLines(editorText);
        Set<Integer> docstringLines = ErrorChecker.findDocstringErrorLines(editorText);

        boolean hasUndefined = undefinedLines.contains(index);
        boolean hasIncomplete = incompleteLines.contains(index);
        boolean hasInvalidExponent = invalidExponentLines.contains(index);
        boolean hasSubSup = subscriptSuperscriptLines.contains(index);
        boolean hasDocstringWarning = docstringLines.contains(index);
        boolean isErrorLine = hasUndefined || hasIncomplete || hasInvalidExponent || hasSubSup;
        boolean isWarningLine = !isErrorLine && hasDocstringWarning;

        List<String> lineIssues = new ArrayList<>();
        if (hasUndefined) {
            lineIssues.add("Undefined variable error");
        }
        if (hasIncomplete) {
            lineIssues.add("Incomplete line syntax error");
        }
        if (hasInvalidExponent) {
            lineIssues.add("Invalid exponent syntax error");
        }
        if (hasSubSup) {
            lineIssues.add("Invalid subscript/superscript usage");
        }
        if (hasDocstringWarning) {
            lineIssues.add("Docstring warning");
        }

        String issueText = String.join("\n", lineIssues);

        if (isErrorLine) {
            marker.setStyle("-fx-text-fill: #d32f2f; -fx-font-size: 10px; -fx-font-weight: bold;");
            Tooltip tooltip = new Tooltip(issueText.isEmpty() ? "Error line" : issueText);
            tooltip.setStyle("-fx-font-size: 10px;");
            lineNumber.setTooltip(tooltip);
            marker.setTooltip(tooltip);
        } else if (isWarningLine) {
            marker.setStyle("-fx-text-fill: #f9a825; -fx-font-size: 10px; -fx-font-weight: bold;");
            Tooltip tooltip = new Tooltip(issueText.isEmpty() ? "Warning line" : issueText);
            tooltip.setStyle("-fx-font-size: 10px;");
            lineNumber.setTooltip(tooltip);
            marker.setTooltip(tooltip);
        } else {
            marker.setStyle("-fx-text-fill: transparent; -fx-font-size: 8px;");
            lineNumber.setTooltip(null);
            marker.setTooltip(null);
        }

        lineNumber.setStyle("-fx-font-family: Consolas; -fx-font-size: 13px; -fx-text-fill: #858585;");

        HBox gutter = new HBox(6, foldArrow, marker, lineNumber);
        gutter.setAlignment(Pos.CENTER_RIGHT);
        // Small right padding so there's a gap between the line numbers and text
        gutter.setPadding(new Insets(0, 8, 0, 0));
        gutter.getStyleClass().add("line-number-gutter");
        return gutter;
    }

    public static Label createErrorLabel() {
        Label errorLabel = new Label(" Errors: 0 ");
        errorLabel.setVisible(true);
        errorLabel.setManaged(true);
        errorLabel.setMinWidth(120);
        errorLabel.setAlignment(Pos.CENTER_RIGHT);
        applyErrorStyle(errorLabel, 0);
        errorLabel.setTooltip(new Tooltip(
                "Docstring errors: 0\nUndefined variable errors: 0\nIncomplete line syntax errors: 0\nInvalid exponent syntax errors: 0\nSubscript/superscript errors: 0"));
        return errorLabel;
    }

    public static Label createTopRightErrorLabel() {
        return createErrorLabel();
    }

    // ---------- Error Label (top-right) ----------
    public static void bindLiveErrorCount(TextInputControl editor, Label errorLabel) {
        editor.textProperty().addListener((obs, oldText, newText) -> {
            updateErrorLabel(newText, errorLabel);
        });
        updateErrorLabel(editor.getText(), errorLabel);
    }

    // ---------- Real-time error update ----------
    public static void attachRealtimeErrorUpdate(TextInputControl editor, Label errorLabel) {
        bindLiveErrorCount(editor, errorLabel);
    }

    public static void attachRealtimeErrorUpdate(TextInputControl editor, HBox statusBox) {
        editor.textProperty().addListener((obs, oldText, newText) -> updateStatusBox(newText, statusBox));
        updateStatusBox(editor.getText(), statusBox);
    }

    public static void bindLiveErrorCount(CodeArea editor, Label errorLabel) {
        editor.textProperty().addListener((obs, oldText, newText) -> {
            updateErrorLabel(newText, errorLabel);
            updateErrorLineStyles(editor, newText);
        });
        updateErrorLabel(editor.getText(), errorLabel);
        updateErrorLineStyles(editor, editor.getText());
    }

    public static void attachRealtimeErrorUpdate(CodeArea editor, Label errorLabel) {
        bindLiveErrorCount(editor, errorLabel);
    }

    public static void attachRealtimeErrorUpdate(CodeArea editor, HBox statusBox) {
        editor.textProperty().addListener((obs, oldText, newText) -> {
            updateStatusBox(newText, statusBox);
            updateErrorLineStyles(editor, newText);
        });
        updateStatusBox(editor.getText(), statusBox);
        updateErrorLineStyles(editor, editor.getText());
    }

    private static void updateErrorLineStyles(CodeArea editor, String text) {
        Set<Integer> errorLines = ErrorChecker.findIncompleteLineSyntaxErrorLines(text);
        errorLines.addAll(ErrorChecker.findDocstringErrorLines(text));
        errorLines.addAll(ErrorChecker.findUndefinedVariableErrorLines(text));
        errorLines.addAll(ErrorChecker.findInvalidExponentErrorLines(text));
        errorLines.addAll(ErrorChecker.findSubscriptSuperscriptErrorLines(text));
        int paragraphCount = editor.getParagraphs().size();
        for (int i = 0; i < paragraphCount; i++) {
            if (errorLines.contains(i)) {
                editor.setParagraphStyle(i, Collections.singleton("error-line"));
            } else {
                editor.setParagraphStyle(i, Collections.emptyList());
            }
        }
    }

    public static void updateErrorLabel(String text, Label errorLabel) {
        ErrorChecker.ErrorReport report = ErrorChecker.analyzeErrors(text == null ? "" : text);
        errorLabel.setText(report.toDisplayText());
        applyErrorStyle(errorLabel, report);
        errorLabel.setTooltip(new Tooltip(report.toTooltipText()));
    }

    private static void applyErrorStyle(Label errorLabel, int count) {
        if (count > 0) {
            errorLabel.setStyle(
                    "-fx-text-fill: white; -fx-font-size: 12; -fx-font-weight: bold; -fx-background-color: #c62828; -fx-background-radius: 4; -fx-padding: 2 8 2 8;");
        } else {
            errorLabel.setStyle(
                    "-fx-text-fill: white; -fx-font-size: 12; -fx-font-weight: bold; -fx-background-color: #2e7d32; -fx-background-radius: 4; -fx-padding: 2 8 2 8;");
        }
    }

    private static void applyErrorStyle(Label errorLabel, ErrorChecker.ErrorReport report) {
        if (report.getErrorCount() > 0) {
            errorLabel.setStyle(
                    "-fx-text-fill: white; -fx-font-size: 12; -fx-font-weight: bold; -fx-background-color: #c62828; -fx-background-radius: 4; -fx-padding: 2 8 2 8;");
        } else if (report.getWarningCount() > 0) {
            errorLabel.setStyle(
                    "-fx-text-fill: black; -fx-font-size: 12; -fx-font-weight: bold; -fx-background-color: #f9a825; -fx-background-radius: 4; -fx-padding: 2 8 2 8;");
        } else {
            errorLabel.setStyle(
                    "-fx-text-fill: white; -fx-font-size: 12; -fx-font-weight: bold; -fx-background-color: #2e7d32; -fx-background-radius: 4; -fx-padding: 2 8 2 8;");
        }
    }

    private static Label createStatusLabel(String text) {
        Label label = new Label(text);
        label.setMinWidth(64);
        label.setAlignment(Pos.CENTER_RIGHT);
        return label;
    }

    private static void updateStatusBox(String text, HBox statusBox) {
        if (statusBox == null) {
            return;
        }

        Object errorObj = statusBox.getProperties().get(ERROR_BUTTON_KEY);
        Object warningObj = statusBox.getProperties().get(WARNING_BUTTON_KEY);
        if (!(errorObj instanceof Label) || !(warningObj instanceof Label)) {
            return;
        }

        Label errorButton = (Label) errorObj;
        Label warningButton = (Label) warningObj;

        ErrorChecker.ErrorReport report = ErrorChecker.analyzeErrors(text == null ? "" : text);

        errorButton.setText("✖ " + report.getErrorCount());
        warningButton.setText("⚠ " + report.getWarningCount());

        applyErrorButtonStyle(errorButton, report.getErrorCount());
        applyWarningButtonStyle(warningButton, report.getWarningCount());

        errorButton.setTooltip(new Tooltip("Undefined variable errors: " + report.getUndefinedVariableErrors()
                + "\nIncomplete line syntax errors: " + report.getIncompleteLineSyntaxErrors()
                + "\nInvalid exponent syntax errors: " + report.getInvalidExponentErrors()
                + "\nSubscript/superscript errors: " + report.getSubscriptSuperscriptErrors()));
        warningButton.setTooltip(new Tooltip("Docstring warnings: " + report.getDocstringErrors()));
    }

    private static void applyErrorButtonStyle(Label errorButton, int errorCount) {
        errorButton.setStyle(
                "-fx-text-fill: #d32f2f; -fx-font-size: 12; -fx-font-weight: bold; -fx-background-color: transparent; -fx-padding: 0 4 0 4;");
    }

    private static void applyWarningButtonStyle(Label warningButton, int warningCount) {
        warningButton.setStyle(
                "-fx-text-fill: #f9a825; -fx-font-size: 12; -fx-font-weight: bold; -fx-background-color: transparent; -fx-padding: 0 4 0 4;");
    }
}
