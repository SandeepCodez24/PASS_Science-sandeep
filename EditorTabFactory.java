package ui.editor;

import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;

import javafx.beans.binding.Bindings;
import javafx.beans.binding.DoubleBinding;
import javafx.beans.binding.IntegerBinding;
import javafx.geometry.Insets;
import javafx.scene.control.Control;
import javafx.scene.control.Tab;
import javafx.scene.control.TextArea;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import language.language_ui.ErrorStatusManager;
import language.language_ui.handler.EditorActions;
import language.language_ui.handler.EditorContextMenuManager;
import language.syntax.UPITokenMaker;
import ui.UI_Main;
import ui.ui_functions.EditorSuggestionHandler;

/**
 * Factory for creating editor tabs with proper UI setup and listeners.
 * Handles both CodeArea (for UPI files) and TextArea (for plain text).
 */
public class EditorTabFactory {

    /**
     * Creates an editor tab appropriate for the file type.
     * Routes to CodeArea for UPI files, TextArea for others.
     * 
     * @param ide     the IDE controller
     * @param name    the tab name
     * @param content the initial content
     * @param ext     the file extension
     * @return a configured Tab
     */
    public static Tab createEditorTab(UI_Main ide, String name, String content, String ext) {
        if (".nc".equals(ext)) {
            return createCodeAreaTab(ide, name, content, ext);
        }
        return createTextAreaTab(ide, name, content, ext);
    }

    /**
     * Creates a CodeArea tab with syntax highlighting and advanced features.
     * Sets up line numbers, error tracking, and keyboard shortcuts.
     * 
     * @param ide     the IDE controller
     * @param name    the tab name
     * @param content the initial content
     * @param ext     the file extension
     * @return a configured Tab with CodeArea
     */
    public static Tab createCodeAreaTab(UI_Main ide, String name, String content, String ext) {
        CodeArea editor = new CodeArea(content);
        if (!editor.getStyleClass().contains("code-area")) {
            editor.getStyleClass().add("code-area");
        }
        editor.setStyle("-fx-font-family: Consolas; -fx-font-size: 16px;");
        editor.setPadding(new Insets(8, 8, 8, 8));

        FoldingManager foldingManager = new FoldingManager();

        // Declared before the closures below so they can all capture it.
        Tab tab = new Tab(name);
        tab.setUserData(new EditorFileInfo(null, ext));

        // Setup line numbers, fold arrows, and gutter
        editor.setParagraphGraphicFactory(index -> {
            HBox graphic = ErrorStatusManager.createLineNumberGraphic(editor, index, foldingManager);

            IntegerBinding paragraphCount = Bindings.createIntegerBinding(
                    () -> editor.getParagraphs().size(), editor.getParagraphs());

            DoubleBinding gutterWidth = Bindings.createDoubleBinding(() -> {
                int digits = Math.max(1, String.valueOf(paragraphCount.get()).length());
                Text measurer = new Text("0".repeat(digits));
                measurer.setFont(Font.font("Consolas", 5));
                return measurer.getLayoutBounds().getWidth() + 40;
            }, paragraphCount);

            graphic.minWidthProperty().bind(gutterWidth);
            graphic.setMaxWidth(Control.USE_PREF_SIZE);
            return graphic;
        });

        // Caret skip-over-folded-lines is native to RichTextFX (GenericStyledArea
        // wires its own listener for this), so no custom handling is needed here.

        // Setup syntax highlighting
        editor.richChanges()
                .filter(ch -> !ch.getInserted().equals(ch.getRemoved()))
                .subscribe(change -> editor.setStyleSpans(0, UPITokenMaker.computeHighlighting(editor.getText())));

        editor.setStyleSpans(0, UPITokenMaker.computeHighlighting(content));

        // Attach the CodeArea to the tab constructed above.
        VirtualizedScrollPane<CodeArea> scrollPane = new VirtualizedScrollPane<>(editor);
        tab.setContent(scrollPane);

        // Setup text change listener
        editor.textProperty().addListener((obs, oldText, newText) -> {
            if (!tab.getText().endsWith("*")) {
                tab.setText(tab.getText() + "*");
            }
            // An edit invalidates a stale Step highlight immediately, rather than
            // leaving it visually pinned to now-wrong line numbers until the next
            // Step click re-detects the session is stale (EditorFileInfo fields
            // are otherwise reset lazily, on demand, by StepController).
            if (tab.getUserData() instanceof EditorFileInfo info
                    && !newText.equals(info.stepSourceSnapshot)
                    && info.stepCurrentUnitStart >= 0) {
                info.stepCurrentUnitStart = -1;
                info.stepCurrentUnitEnd = -1;
            }
        });

        // Use an event filter so this shortcut still works even if other code sets
        // onKeyPressed.
        editor.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (isCommandCommentShortcut(e)) {
                EditorTextOperations.commentSelectedLinesCodeArea(editor);
                e.consume();
            }
        });

        editor.setOnKeyReleased(e -> EditorActions.handleReplacement(editor, e));

        // Setup context menu and suggestions
        EditorContextMenuManager.install(editor);
        EditorSuggestionHandler.wireCodeAreaSuggestions(ide, editor, tab);

        // Setup error handling
        EditorErrorHandler.attachErrorListener(ide, editor, tab, foldingManager);

        return tab;
    }

    /**
     * Creates a TextArea tab for plain text files.
     * Simpler than CodeArea but without syntax highlighting.
     * 
     * @param ide     the IDE controller
     * @param name    the tab name
     * @param content the initial content
     * @param ext     the file extension
     * @return a configured Tab with TextArea
     */
    public static Tab createTextAreaTab(UI_Main ide, String name, String content, String ext) {
        TextArea area = new TextArea(content);
        area.setStyle("-fx-font-family: Consolas; -fx-font-size: 16px;");

        Tab tab = new Tab(name, area);
        tab.setUserData(new EditorFileInfo(null, ext));

        // Setup text change listener
        area.textProperty().addListener((obs, oldText, newText) -> {
            if (!tab.getText().endsWith("*")) {
                tab.setText(tab.getText() + "*");
            }
        });

        // Keep the same shortcut behavior for plain TextArea tabs as well.
        area.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (isCommandCommentShortcut(e)) {
                EditorTextOperations.commentSelectedLinesTextArea(area);
                e.consume();
            }
        });

        // Setup suggestions and context menu
        EditorSuggestionHandler.wireTextAreaSuggestions(ide, area, tab);
        EditorErrorHandler.attachErrorListenerTextArea(ide, area, tab);
        EditorContextMenuManager.install(area);

        return tab;
    }

    /**
     * Checks if a key event is the comment shortcut (Ctrl + /).
     * 
     * @param event the key event
     * @return true if this is the comment shortcut
     */
    private static boolean isCommandCommentShortcut(KeyEvent event) {
        return event.isControlDown()
                && (event.getCode() == KeyCode.SLASH
                        || event.getCode() == KeyCode.DIVIDE
                        || event.getCode() == KeyCode.BACK_SLASH);
    }
}
