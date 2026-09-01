package ui.managers;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import javafx.application.Platform;
import javafx.scene.control.Tab;
import plot.PlotWindow;
import ui.UI_Main;
import ui.editor.EditorUIHelper;
import ui.editor.homeTab.EditorFileCreator;
import ui.editor.homeTab.EditorFileOpener;
import ui.editor.homeTab.EditorFileRunner;
import ui.editor.homeTab.EditorFileSaver;
import ui.settings.connector_cpp.UPIPoint;
import ui.settings.connector_cpp.UPIVariable;

/**
 * High-level orchestrator for file and editor operations.
 * Delegates to specialized editor classes for specific operations.
 */
public class EditorManager {

    /*
     * =========================================================
     * NEW FILES - Delegate to EditorFileCreator
     * =========================================================
     */

    /**
     * Creates a new plain text file.
     * 
     * @param ide the IDE controller
     */
    public static void newPlainTextFile(UI_Main ide) {
        EditorFileCreator.newPlainTextFile(ide);
    }

    /**
     * Creates a new UPI file.
     * 
     * @param ide the IDE controller
     */
    public static void newUPIFile(UI_Main ide) {
        EditorFileCreator.newUPIFile(ide);
    }

    /**
     * Creates a new Java class file with template.
     * 
     * @param ide the IDE controller
     */
    public static void newClassFile(UI_Main ide) {
        EditorFileCreator.newClassFile(ide);
    }

    /**
     * Creates a new Java function file with template.
     * 
     * @param ide the IDE controller
     */
    public static void newFunctionFile(UI_Main ide) {
        EditorFileCreator.newFunctionFile(ide);
    }

    /*
     * =========================================================
     * OPEN FILE - Delegate to EditorFileOpener
     * =========================================================
     */

    /**
     * Opens a file via file chooser dialog.
     * 
     * @param ide the IDE controller
     */
    public static void openFile(UI_Main ide) {
        EditorFileOpener.openFile(ide);
    }

    /**
     * Opens a file directly by path.
     * 
     * @param ide  the IDE controller
     * @param path the file path
     */
    public static void openFileDirectly(UI_Main ide, Path path) {
        EditorFileOpener.openFileDirectly(ide, path);
    }

    /**
     * Opens a Neural Code (.nc) file only. Backs the default click on the
     * ribbon's Open button.
     * 
     * @param ide the IDE controller
     */
    public static void openNcFile(UI_Main ide) {
        EditorFileOpener.openNcFile(ide);
    }

    /**
     * Asks for a folder and loads it as the project.
     * 
     * @param ide the IDE controller
     */
    public static void openProjectFolder(UI_Main ide) {
        EditorFileOpener.openProjectFolder(ide);
    }

    /**
     * Re-opens the folder last loaded as a project.
     * 
     * @param ide the IDE controller
     */
    public static void openLastProject(UI_Main ide) {
        EditorFileOpener.openLastProject(ide);
    }

    /**
     * Points the project tree back at the current project's root folder.
     * 
     * @param ide the IDE controller
     */
    public static void openRootFolder(UI_Main ide) {
        EditorFileOpener.openRootFolder(ide);
    }

    /*
     * =========================================================
     * SAVE - Delegate to EditorFileSaver
     * =========================================================
     */

    /**
     * Saves the current file.
     * 
     * @param ide the IDE controller
     */
    public static void saveCurrentFile(UI_Main ide) {
        EditorFileSaver.saveCurrentFile(ide);
    }

    /**
     * Saves the current file with a new name.
     * 
     * @param ide the IDE controller
     */
    public static void saveAsFile(UI_Main ide) {
        EditorFileSaver.saveAsFile(ide);
    }

    /**
     * Saves the current file into the project root folder.
     * 
     * @param ide the IDE controller
     */
    public static void saveToProjectRoot(UI_Main ide) {
        EditorFileSaver.saveToProjectRoot(ide);
    }

    /**
     * Asks for a folder and saves the current file into it.
     * 
     * @param ide the IDE controller
     */
    public static void saveInFolder(UI_Main ide) {
        EditorFileSaver.saveInFolder(ide);
    }

    /*
     * =========================================================
     * RUN FILE - Delegate to EditorFileRunner
     * =========================================================
     */

    /**
     * Runs the current file.
     * 
     * @param ide the IDE controller
     */
    public static void runCurrentFile(UI_Main ide) {
        EditorFileRunner.runCurrentFile(ide);
    }

    /*
     * =========================================================
     * TERMINAL OPERATIONS
     * =========================================================
     */

    /**
     * Sends a command to the running UPI terminal.
     * 
     * @param ide     the IDE controller
     * @param command the command to send
     */
    public static void sendCommandToTerminal(UI_Main ide, String command) {
        try {
            if (ide.runningProcess == null) {
                ide.console.appendText("UPI terminal is not running.\n");
                return;
            }

            ide.runningProcess.getOutputStream().write(
                    (command + "\n").getBytes(StandardCharsets.UTF_8));
            ide.runningProcess.getOutputStream().flush();

        } catch (Exception e) {
            ide.console.appendText(
                    "Failed to send command: " + e.getMessage() + "\n");
        }
    }

    /**
     * Starts the UPI terminal session.
     * Initializes the UPI interpreter and sets up console I/O handling.
     * 
     * @param ide the IDE controller
     */
    public static void startUPITerminal(
            UI_Main ide) {

        try {

            String interpreterExe = Path.of(
                    System.getProperty("user.dir"),
                    "src",
                    "main",
                    "cpp",
                    "nc.exe")
                    .toString();

            ProcessBuilder pb = new ProcessBuilder(interpreterExe);

            Path interpreterDir = Path.of(
                    System.getProperty("user.dir"),
                    "src",
                    "main",
                    "cpp");

            pb.directory(interpreterDir.toFile());

            pb.redirectErrorStream(true);

            Process process = pb.start();

            ide.setRunningProcess(process);

            ide.setProgramRunning(true);

            /* READ OUTPUT */

            enum PlotMode {
                LINE,
                BAR
            }

            new Thread(() -> {
                AtomicReference<PlotMode> mode = new AtomicReference<>(PlotMode.LINE);

                try (var input = process.getInputStream()) {

                    byte[] buffer = new byte[1024];
                    int len;

                    StringBuilder pending = new StringBuilder();

                    boolean collectingPlot = false;

                    List<UPIVariable> variables = new ArrayList<>();
                    List<UPIPoint> barPoints = new ArrayList<>();
                    List<UPIPoint> linePoints = new ArrayList<>();

                    while ((len = input.read(buffer)) != -1) {

                        String chunk = new String(
                                buffer,
                                0,
                                len,
                                StandardCharsets.UTF_8);

                        // Show output immediately (restores >> prompt)
                        Platform.runLater(() -> ide.appendProcessOutput(chunk));

                        pending.append(chunk);

                        int pos;

                        while ((pos = pending.indexOf("\n")) >= 0) {

                            String line = pending.substring(0, pos);
                            pending.delete(0, pos + 1);

                            String clean = line.trim();

                            clean = clean.replaceFirst("^(>>\\s*)+", "").trim();
                            if (clean.contains("@PLOT_BEGIN")) {

                                collectingPlot = true;
                                mode.set(PlotMode.LINE);

                                linePoints.clear();
                                barPoints.clear();
                                variables.clear();

                                continue;
                            }
                            if (clean.contains("@BAR_BEGIN")) {

                                collectingPlot = true;
                                mode.set(PlotMode.BAR);

                                linePoints.clear();
                                barPoints.clear();
                                variables.clear();

                                continue;
                            }

                            if (clean.contains("@PLOT_END") || clean.contains("@BAR_END")) {

                                collectingPlot = false;

                                List<UPIPoint> data = (mode.get() == PlotMode.BAR) ? barPoints : linePoints;

                                List<UPIVariable> varsCopy = new ArrayList<>(variables);

                                Platform.runLater(() -> {

                                    if (ide.plotWindow == null) {
                                        ide.plotWindow = new PlotWindow(
                                                ide,
                                                ide.currentFolder != null ? ide.currentFolder.toFile() : null);
                                    }

                                    ide.plotWindow.show();

                                    if (mode.get() == PlotMode.BAR) {
                                        ide.plotWindow.showBarPoints(barPoints, varsCopy);
                                    } else {
                                        ide.plotWindow.showLivePoints(data, varsCopy);
                                    }
                                });

                                continue;
                            }

                            // int plotPos = clean.indexOf("@PLOT ");

                            if (collectingPlot && clean.startsWith("@PLOT ")) {
                                String[] parts = clean.split("\\s+");

                                if (parts.length >= 3) {
                                    UPIPoint p = new UPIPoint(
                                            Double.parseDouble(parts[1]),
                                            Double.parseDouble(parts[2]));

                                    if (mode.get() == PlotMode.BAR) {
                                        barPoints.add(p);
                                    } else {
                                        linePoints.add(p);
                                    }
                                }
                            }
                            if (collectingPlot && clean.startsWith("@BAR ")) {

                                String[] parts = clean.split("\\s+");

                                if (parts.length >= 3) {

                                    barPoints.add(
                                            new UPIPoint(
                                                    Double.parseDouble(parts[1]),
                                                    Double.parseDouble(parts[2])));
                                }
                            }

                            if (clean.startsWith("@VAR ")) {

                                String[] parts = clean.split("\\s+");

                                if (parts.length >= 5) {

                                    variables.add(
                                            new UPIVariable(
                                                    parts[1],
                                                    parts[2],
                                                    parts[3],
                                                    parts[4]));
                                }
                            }

                            if (clean.startsWith("@STEPMARK ")) {
                                ui.editor.step.StepSignal.onMarkerReceived(
                                        clean.substring("@STEPMARK ".length()).trim());
                            }
                        }
                    }

                } catch (Exception ex) {

                    Platform.runLater(() -> ide.console.appendText(
                            "Reader error: "
                                    + ex.getMessage()
                                    + "\n"));
                }

            }).start();
            /* WAIT FOR EXIT */

            new Thread(() -> {

                try {

                    process.waitFor();

                } catch (InterruptedException ignored) {
                }

                javafx.application.Platform.runLater(() -> {

                    ide.console.appendText(
                            "\nUPI terminal closed.\n");

                    ide.onProgramFinished();

                });

            }).start();

        } catch (

        Exception e) {

            ide.console.appendText(
                    "Failed to start UPI terminal: "
                            + e.getMessage()
                            + "\n");
        }
    }
    /*
     * =========================================================
     * PLOTTING
     * =========================================================
     */

    /**
     * Plots the current UPI file output.
     * 
     * @param ide the IDE controller
     */
    public static void plotCurrentUPIFile(UI_Main ide) {
        runCurrentFile(ide);
    }

    /**
     * Checks if the current file is a UPI file.
     * 
     * @param ide the IDE controller
     * @return true if current file is .upi
     */
    public static boolean isCurrentUPIFile(UI_Main ide) {
        if (ide == null || ide.editorTabs == null) {
            return false;
        }
        Tab tab = ide.editorTabs.getSelectionModel().getSelectedItem();
        if (tab == null) {
            return false;
        }
        Object userData = tab.getUserData();
        if (!(userData instanceof ui.editor.EditorFileInfo)) {
            return false;
        }
        ui.editor.EditorFileInfo info = (ui.editor.EditorFileInfo) userData;
        return ".upi".equalsIgnoreCase(info.ext);
    }

    /*
     * =========================================================
     * DEMO FILE INTEGRATION
     * =========================================================
     */

    /**
     * Opens the demo C++ file (main.cpp).
     * 
     * @param ide the IDE controller
     */
    public static void integrateDemoCpp(UI_Main ide) {
        Path mainCpp = Path.of(
                System.getProperty("user.dir"),
                "src",
                "main",
                "cpp",
                "main.cpp");

        try {
            if (!Files.exists(mainCpp)) {
                ide.console.appendText("main.cpp not found.\n");
                return;
            }

            openFileDirectly(ide, mainCpp);
            ide.console.appendText("Opened main.cpp\n");

        } catch (Exception e) {
            EditorUIHelper.showError("main.cpp integration failed", e);
        }
    }
}
