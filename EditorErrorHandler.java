package ui.editor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.fxmisc.richtext.CodeArea;

import javafx.scene.control.Tab;
import language.syntax.syntax_checker.ErrorChecker;
import ui.UI_Main;
import ui.managers.IDEErrorStatusManager;

/**
 * Handles error checking and visual error feedback.
 * Manages error highlighting and status updates for editors.
 */
public class EditorErrorHandler {

    /**
     * Attaches real-time error checking to a CodeArea editor.
     * Updates error indicators and line styles as text changes.
     * @param ide the IDE controller
     * @param editor the CodeArea to monitor
     * @param tab the tab containing the editor
     */
    public static void attachErrorListener(UI_Main ide, CodeArea editor, Tab tab, FoldingManager foldingManager) {
        editor.textProperty().addListener((obs, oldText, newText) -> {
            ErrorChecker.ErrorReport report = ErrorChecker.analyzeErrors(newText);
            IDEErrorStatusManager.updateErrorStatusIndicator(ide, report);
            if (foldingManager != null) {
                foldingManager.recompute(newText);
            }
            updateLineStyles(editor, newText, tab);
        });

        String initialText = editor.getText();
        IDEErrorStatusManager.updateErrorStatusIndicator(ide, ErrorChecker.analyzeErrors(initialText));
        if (foldingManager != null) {
            foldingManager.recompute(initialText);
        }
        updateLineStyles(editor, initialText, tab);
    }

    /**
     * Attaches real-time error checking to a TextArea editor.
     * Updates error indicators as text changes.
     * @param ide the IDE controller
     * @param editor the TextArea to monitor
     * @param tab the tab containing the editor
     */
    public static void attachErrorListenerTextArea(UI_Main ide, javafx.scene.control.TextArea editor, Tab tab) {
        editor.textProperty().addListener((obs, oldText, newText) -> {
            ErrorChecker.ErrorReport report = ErrorChecker.analyzeErrors(newText);
            IDEErrorStatusManager.updateErrorStatusIndicator(ide, report);
        });

        IDEErrorStatusManager.updateErrorStatusIndicator(ide, ErrorChecker.analyzeErrors(editor.getText()));
    }

    /**
     * Re-applies the current per-paragraph style classes (error highlighting +
     * fold state + step-current-line) without recomputing error analysis.
     * Used when only the Step cursor moved (a fold/error refresh already
     * happens on its own via the text-change listener above).
     * @param editor the CodeArea to update
     * @param tab the tab this editor belongs to, for its step-highlight range
     */
    public static void refreshStepHighlight(CodeArea editor, Tab tab) {
        updateLineStyles(editor, editor.getText(), tab);
    }

    /**
     * Updates visual styling for error lines in a CodeArea. Applies error-line
     * style to lines containing errors, preserving RichTextFX's own native
     * "collapse" fold style class (added/removed by CodeArea.foldParagraphs/
     * unfoldParagraphs) and the Step feature's current-line highlight, rather
     * than clobbering either — setParagraphStyle replaces a paragraph's whole
     * style-class list, so anything not re-added here would silently vanish
     * on the next keystroke or step.
     * @param editor the CodeArea to update
     * @param text the current text content
     * @param tab the tab this editor belongs to, for its step-highlight range (may be null)
     */
    private static void updateLineStyles(CodeArea editor, String text, Tab tab) {
        Set<Integer> errorLines = new HashSet<>(ErrorChecker.findErrorLinesAfterSemicolon(text));
        errorLines.addAll(ErrorChecker.findIncompleteLineSyntaxErrorLines(text));
        errorLines.addAll(ErrorChecker.findDocstringErrorLines(text));
        errorLines.addAll(ErrorChecker.findUndefinedVariableErrorLines(text));
        errorLines.addAll(ErrorChecker.findInvalidExponentErrorLines(text));

        int stepStart = -1;
        int stepEnd = -1;
        if (tab != null && tab.getUserData() instanceof EditorFileInfo info) {
            stepStart = info.stepCurrentUnitStart;
            stepEnd = info.stepCurrentUnitEnd;
        }

        int paragraphCount = editor.getParagraphs().size();
        for (int i = 0; i < paragraphCount; i++) {
            List<String> classes = new ArrayList<>();
            if (errorLines.contains(i)) {
                classes.add("error-line");
            }
            if (editor.isFolded(i)) {
                classes.add("collapse");
            }
            if (stepStart >= 0 && i >= stepStart && i <= stepEnd) {
                classes.add("step-current-line");
            }
            editor.setParagraphStyle(i, classes.isEmpty() ? Collections.emptyList() : classes);
        }
    }

    /**
     * Performs error checking on the current editor and updates status.
     * @param ide the IDE controller
     * @param tab the tab to check
     * @return the total number of errors found
     */
    public static int checkErrors(UI_Main ide, Tab tab) {
        if (tab == null) {
            return 0;
        }

        String text = EditorTextOperations.getEditorText(tab);
        ErrorChecker.ErrorReport report = ErrorChecker.analyzeErrors(text);
        IDEErrorStatusManager.updateErrorStatusIndicator(ide, report);
        ide.console.appendText("✔ Error check: " + report.getTotalErrors() + " issue(s) found\n");
        return report.getTotalErrors();
    }

    /**
     * Refreshes the error status for a specific tab's editor.
     * @param ide the IDE controller
     * @param tab the tab to refresh
     */
    public static void refreshErrorStatus(UI_Main ide, Tab tab) {
        if (ide == null || tab == null) {
            return;
        }

        String text = EditorTextOperations.getEditorText(tab);
        IDEErrorStatusManager.updateErrorStatusIndicator(ide, ErrorChecker.analyzeErrors(text));
    }
}
