package ui.ui_functions;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import javafx.application.Platform;
import javafx.scene.control.Tab;
import ui.UI_Main;
import ui.editor.EditorFileInfo;
import ui.managers.LanguageDesignManager;

public final class UI_MainProgramControl {

    private UI_MainProgramControl() {
    }

    public static void setRunningProcess(UI_Main ide, Process process) {

        ide.runningProcess = process;
        ide.programRunning = true;

        try {

            ide.processWriter = new BufferedWriter(
                    new OutputStreamWriter(process.getOutputStream()));

        } catch (Exception e) {

            appendErrorText(ide, "Terminal writer error\n");

        }

        ide.inputStart = ide.console.getLength();
    }

    public static void appendProcessOutput(UI_Main ide, String text) {

        clearConsoleErrorStyle(ide);

        if (ide != null && ide.console != null && text != null && !text.isEmpty()) {
            int length = ide.console.getLength();
            if (length > 0) {
                String tail = ide.console.getText(Math.max(0, length - 3), length);
                boolean endsWithPrompt = ">> ".equals(tail);
                boolean startsWithNewline = text.startsWith("\n") || text.startsWith("\r");
                if (endsWithPrompt && !startsWithNewline) {
                    ide.console.appendText("\n");
                }
            }
        }

        ide.console.appendText(text);

        // Capture "ans" output and store it as a variable
        captureAnsFromOutput(ide, text);

        if (text.endsWith(">> ")) {
            ide.inputStart = ide.console.getLength();
        }

        ide.console.positionCaret(ide.console.getLength());
    }

    public static void handleConsoleEnter(UI_Main ide) {

        int end = ide.console.getLength();

        if (ide.inputStart > end)
            ide.inputStart = end;

        String input = ide.console
                .getText()
                .substring(ide.inputStart, end)
                .replace(">> ", "")
                .trim();

        if (isExitCommand(input)) {
            handleExitCommand(ide);
            return;
        }

        if (isTerminalResetCommand(input)) {
            handleTerminalReset(ide, input);
            return;
        }

        if (ide.runningProcess == null || !ide.programRunning) {

            handleLocalConsoleInput(ide, input);

            return;
        }

        if (input != null && !input.isBlank()) {
            parseAndStoreConsoleVariables(ide, input);
        }

        try {
            clearConsoleErrorStyle(ide);

            if (ide.processWriter == null) {

                appendErrorText(ide, "\nInput error\n");

                ide.inputStart = ide.console.getLength();

                return;
            }

            ide.processWriter.write(input);

            ide.processWriter.newLine();

            ide.processWriter.flush();

            ide.inputStart = ide.console.getLength();

        } catch (Exception ex) {

            appendErrorText(ide, "\nInput error\n");

            ide.inputStart = ide.console.getLength();

        }
    }

    public static void handleLocalConsoleInput(UI_Main ide, String input) {

        if (ide != null && ide.console != null) {
            clearConsoleErrorStyle(ide);
            ide.console.appendText("\n");
        }

        if (isExitCommand(input)) {
            handleExitCommand(ide);
            return;
        }

        if (input != null && !input.isBlank()) {
            parseAndStoreConsoleVariables(ide, input);
            // Also capture "ans" if it appears in the input
            captureAnsFromOutput(ide, input);
        }

        if (ide != null) {
            ide.inputStart = ide.console.getLength();
            ide.console.positionCaret(ide.console.getLength());
        }
    }

    public static boolean isTerminalResetCommand(String input) {
        if (input == null) {
            return false;
        }
        String normalized = input.trim().toLowerCase(java.util.Locale.ROOT);
        return "cls".equals(normalized) || "clear".equals(normalized);
    }

    private static boolean isExitCommand(String input) {
        if (input == null) {
            return false;
        }
        String normalized = input.trim().toLowerCase(java.util.Locale.ROOT);
        return "exit".equals(normalized) || "exit()".equals(normalized)
                || "quit".equals(normalized) || "quit()".equals(normalized);
    }

    private static void handleExitCommand(UI_Main ide) {
        if (ide == null) {
            return;
        }

        if (ide.console != null) {
            ide.console.appendText("\nExiting UPI...\n");
        }

        if (ide.runningProcess != null && ide.programRunning) {
            try {
                ide.runningProcess.destroy();
            } catch (Exception ignored) {
            }
        }

        if (ide.stage != null) {
            ide.stage.close();
        }

        Platform.exit();
    }

    public static void handleTerminalReset(UI_Main ide, String input) {
        if (ide == null || ide.console == null) {
            return;
        }

        String normalized = input == null ? "" : input.trim().toLowerCase(java.util.Locale.ROOT);
        boolean clearVariables = "clear".equals(normalized);

        ide.console.clear();
        ide.console.appendText(">> ");
        ide.inputStart = ide.console.getLength();
        ide.console.positionCaret(ide.console.getLength());

        if (clearVariables) {
            clearVariableExplorerState(ide);
            clearVariableFiles(ide);
        }

        if (ide.runningProcess != null && ide.programRunning) {
            try {
                ide.runningProcess.getOutputStream()
                        .write((clearVariables ? "clear" : "cls").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                ide.runningProcess.getOutputStream().write('\n');
                ide.runningProcess.getOutputStream().flush();
            } catch (Exception ignored) {
            }
        }
    }

    /** Public so ui.editor.step.StepController can reuse it for a fresh step session's reset. */
    public static void clearVariableExplorerState(UI_Main ide) {
        if (ide.varTable != null) {
            ide.varTable.getItems().clear();
        }
        if (ide.consoleVariables != null) {
            ide.consoleVariables.clear();
        }
    }

    private static void clearVariableFiles(UI_Main ide) {
        if (ide == null || ide.editorTabs == null) {
            return;
        }

        for (javafx.scene.control.Tab tab : ide.editorTabs.getTabs()) {
            Object userData = tab.getUserData();
            if (!(userData instanceof EditorFileInfo info) || info.path == null
                    || !LanguageDesignManager.isUpiFile(info.path)) {
                continue;
            }

            Path variablePath = LanguageDesignManager.buildVariablePath(info);
            Path javaDictionaryPath = LanguageDesignManager.buildJavaDictionaryPath(info);
            try {
                Files.writeString(variablePath, "", StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                Files.writeString(javaDictionaryPath, "", StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING);
            } catch (IOException ignored) {
            }
        }
    }

    public static void handleConsoleStatementTerminated(UI_Main ide) {
        if (ide == null || ide.console == null) {
            return;
        }

        int end = ide.console.getLength();
        if (ide.inputStart > end) {
            ide.inputStart = end;
        }

        String rawInput = ide.console.getText().substring(ide.inputStart, end).replace(">> ", "");
        String input = rawInput.trim();
        if (input.isEmpty()) {
            return;
        }

        boolean terminatedBySemicolon = rawInput.endsWith(";");
        boolean terminatedBySpace = rawInput.endsWith(" ");
        Tab selectedTab = ide.editorTabs != null ? ide.editorTabs.getSelectionModel().getSelectedItem() : null;
        if (!terminatedBySemicolon && !terminatedBySpace) {
            parseAndStoreConsoleVariables(ide, input);
            captureAnsFromOutput(ide, input);
            ide.refreshVariablesForOpenTabs(selectedTab);
            return;
        }

        parseAndStoreConsoleVariables(ide, input);
        captureAnsFromOutput(ide, input);
        ide.refreshVariablesForOpenTabs(selectedTab);
    }

    private static void parseAndStoreConsoleVariables(UI_Main ide, String input) {
        if (ide == null || ide.varTable == null || input == null)
            return;

        java.util.List<Variable> parsed = IDEVariableService.parseConsoleVariables(input);
        ide.addConsoleVariables(parsed);
    }

    public static void onProgramFinished(UI_Main ide) {

        ide.runningProcess = null;

        ide.processWriter = null;

        ide.programRunning = false;

        // Unblock any in-flight Step wait immediately (process died/was killed)
        // instead of leaving the caller to sit through the full timeout.
        ui.editor.step.StepSignal.cancelPending("Interpreter process stopped.");
    }

    public static void stopProgram(UI_Main ide) {

        if (ide.runningProcess != null && ide.programRunning) {

            ide.runningProcess.destroyForcibly();

            onProgramFinished(ide);

            // ide.console.appendText("\nProgram stopped.\n");

            ide.inputStart = ide.console.getLength();
        }
    }

    public static void appendNextPrompt(UI_Main ide) {
        if (ide != null && ide.console != null) {
            clearConsoleErrorStyle(ide);
            int length = ide.console.getLength();
            if (length > 0) {
                String tail = ide.console.getText(Math.max(0, length - 1), length);
                if (!"\n".equals(tail) && !"\r".equals(tail)) {
                    ide.console.appendText("\n");
                }
            }
            ide.console.appendText(">> ");
            ide.inputStart = ide.console.getLength();
            ide.console.positionCaret(ide.console.getLength());
        }
    }

    public static void appendErrorText(UI_Main ide, String text) {
        if (ide == null || ide.console == null || text == null)
            return;

        // Ensure UI updates happen on the JavaFX Application Thread so inline
        // style changes are applied before text is rendered.
        javafx.application.Platform.runLater(() -> {
            String prevStyle = ide.console.getStyle();
            try {
                String redStyle = prevStyle == null || prevStyle.isBlank()
                        ? "-fx-text-fill: #c62828;"
                        : prevStyle + "; -fx-text-fill: #c62828;";
                ide.console.setStyle(redStyle);
                ide.console.appendText(text);
            } finally {
                ide.console.setStyle(prevStyle == null ? "" : prevStyle);
            }
            ide.console.positionCaret(ide.console.getLength());
        });
    }

    private static void clearConsoleErrorStyle(UI_Main ide) {
        if (ide == null || ide.console == null)
            return;
        ide.console.getStyleClass().remove("console-error");
    }

    public static void replaceInitialPromptWithRunLabel(UI_Main ide, String filename) {
        if (ide == null || ide.console == null) {
            return;
        }
        // Replace the initial ">> " with the running file label
        int consoleLength = ide.console.getLength();
        if (consoleLength >= 3) {
            String lastChars = ide.console.getText(Math.max(0, consoleLength - 3), consoleLength);
            if (lastChars.equals(">> ")) {
                ide.console.deleteText(consoleLength - 3, consoleLength);
                ide.console.appendText("Running " + filename + "...\n");
                ide.inputStart = ide.console.getLength();
            }
        }
    }

    /**
     * Captures "ans" (answer/result) values from console output and stores them as
     * variables.
     * Detects patterns like "ans = 42" or just numeric output that represents ans.
     * 
     * @param ide        the IDE controller
     * @param outputText the text from program/console output
     */
    private static void captureAnsFromOutput(UI_Main ide, String outputText) {
        if (ide == null || ide.varTable == null || outputText == null || outputText.isBlank()) {
            return;
        }

        // Pattern to detect "ans = value" or similar
        java.util.regex.Pattern ansPattern = java.util.regex.Pattern.compile(
                "ans\\s*=\\s*([^\\n]+)", java.util.regex.Pattern.CASE_INSENSITIVE);
        java.util.regex.Matcher matcher = ansPattern.matcher(outputText);

        if (matcher.find()) {
            String ansValue = matcher.group(1).trim();

            // Create Variable for ans
            Variable ansVar = IDEVariableService.createAnsVariable(ansValue);
            if (ansVar != null) {
                ide.addConsoleVariables(java.util.List.of(ansVar));
            }
        }
    }
}