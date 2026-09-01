package ui.ribbon_interface.tool_bar;

import java.io.File;
import java.util.List;

import javafx.application.Platform;
import javafx.beans.binding.DoubleBinding;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ToolBar;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import multiphysics.UI_MP_Main;
import plot.PlotWindow;
import ui.UI_Main;
import ui.editor.homeTab.Export.UI_Home_Export_Main;
import ui.editor.homeTab.Import.UI_Home_Import_Main;
import ui.editor.homeTab.Process.UI_Home_Process_Main;
import ui.explorer.NeuralLinkWindow;
import ui.managers.EditorManager;
import ui.managers.TerminalManager;
import ui.ribbon_interface.tool_groups.RibbonAnalysis;
import ui.ribbon_interface.tool_groups.RibbonGroup;
import ui.ribbon_interface.tool_groups.RibbonItems;
import ui.ribbon_interface.tool_groups.RibbonSplitButton;

/**
 * Builds the Home tab's ribbon band.
 *
 * <p>
 * Eight compartments span the full ribbon width in the ratio
 * 2:2:1:1:1:2:2:1 out of 16 units, divided by mild vertical rules, with the
 * remaining 4 units left free at the right-hand edge:
 * </p>
 *
 * <pre>
 *   Files      New / Open / Save              (split buttons)
 *   Data       Import / Process / Export      (wired)
 *   Visualisation  Plot                       (wired)
 *   Modelling  Multi Physics                  (stub)
 *   Graphical  Neural Link                    (wired)
 *   Compute    Step / Run / Stop              (wired)
 *   Analysis   No. of Computation, Execution Time  (readouts)
 *   AI Help    Code Bot                       (stub)
 * </pre>
 */
public final class HomeRibbon {

    // --- existing assets (unchanged) -------------------------------------
    private static final String IC_RUN = "/icons/ic_04_run.png";
    private static final String IC_STOP = "/icons/ic_07_exit.png";
    private static final String IC_PLOT = "/icons/ic_27_newplot.png";
    private static final String IC_NEURAL_LINK = "/icons/ic_26_neural_link.png";

    // --- Files compartment assets under /icons/HomeFiles ------------------
    // Base names only: HomeFilesIcons appends the theme folder and the
    // extension, so the same constant serves the light and dark sets.
    private static final String IC_F_NEW = "new";
    private static final String IC_F_NEW_SCRIPT = "new_script";
    private static final String IC_F_NEW_FUNCTION = "new_function";
    private static final String IC_F_NEW_CLASS = "new_class";
    private static final String IC_F_NEW_TXT = "new_txt";
    private static final String IC_F_OPEN = "open";
    private static final String IC_F_OPEN_FILE = "open_file";
    private static final String IC_F_OPEN_LAST = "open_last_project";
    private static final String IC_F_OPEN_ROOT = "open_root_folder";
    private static final String IC_F_SAVE = "save";
    private static final String IC_F_SAVE_AS = "save_as";
    private static final String IC_F_SAVE_ROOT = "save_root";
    private static final String IC_F_SAVE_IN = "save_in";

    // --- new assets under /icons/home_icons ------------------------------
    private static final String IC_IMPORT_DATA = "/icons/home_icons/ic_h_01_import_data.png";
    private static final String IC_PROCESS_DATA = "/icons/home_icons/ic_h_02_process_data.png";
    private static final String IC_EXPORT_DATA = "/icons/home_icons/ic_h_03_export_data.png";
    private static final String IC_MULTI_PHYSICS = "/icons/home_icons/ic_h_04_multi_physics.png";
    private static final String IC_STEP = "/icons/home_icons/ic_h_05_step.png";
    private static final String IC_CODE_BOT = "/icons/home_icons/ic_h_06_code_bot.png";

    private HomeRibbon() {
    }

    /**
     * Builds the Home band, replacing the old flat row of seven buttons.
     *
     * @param ide the IDE controller
     * @return the Home ToolBar, ready to drop into the toolbar StackPane
     */
    public static ToolBar build(UI_Main ide) {

        List<RibbonGroup> groups = List.of(
                filesGroup(ide),
                dataGroup(ide),
                visualisationGroup(ide),
                modellingGroup(ide),
                graphicalGroup(ide),
                computeGroup(ide),
                analysisGroup(),
                aiHelpGroup(ide));

        ToolBar bar = new ToolBar();
        bar.getStyleClass().addAll("menu-toolbar", "home-ribbon-toolbar");
        bar.setPrefHeight(RibbonLayout.ROW_HEIGHT);
        bar.setMinHeight(RibbonLayout.ROW_HEIGHT);

        // One unit = (usable width) / 16, recomputed live as the window resizes.
        DoubleBinding unit = RibbonLayout.unitWidth(bar, groups.size());

        HBox root = new HBox();
        root.setAlignment(Pos.CENTER_LEFT);
        root.setSpacing(0);
        root.setFillHeight(true);
        root.getStyleClass().add("home-ribbon-root");

        for (int i = 0; i < groups.size(); i++) {
            RibbonGroup group = groups.get(i);
            group.bindWidthTo(unit);
            root.getChildren().add(group);
            if (i < groups.size() - 1) {
                root.getChildren().add(RibbonLayout.separator());
            }
        }

        // Right-side boundary after the final AI Help group.
        root.getChildren().add(RibbonLayout.separator());

        // The 4 free units. Left as a growing spacer rather than a fixed width
        // so any sub-pixel rounding slack lands here instead of shifting the
        // compartments.
        Region trailingFreeSpace = new Region();
        trailingFreeSpace.getStyleClass().add("ribbon-free-space");
        HBox.setHgrow(trailingFreeSpace, Priority.ALWAYS);
        root.getChildren().add(trailingFreeSpace);

        // A single root child means the ToolBar never renders an overflow
        // button, which would otherwise appear the moment the ratios add up to
        // slightly more than the available width.
        root.prefWidthProperty().bind(bar.widthProperty().subtract(RibbonLayout.TOOLBAR_H_INSET));

        bar.getItems().setAll(root);
        return bar;
    }

    // =====================================================================
    // 1. Files -- 2 units -- three split buttons
    // =====================================================================
    // Each control is one rounded rectangle: the icon runs the default
    // action, the triangle strip beneath the label opens the drop-down.
    // See RibbonSplitButton for the two-zone hover behaviour.
    private static RibbonGroup filesGroup(UI_Main ide) {

        RibbonSplitButton newBtn = RibbonItems.split(
                "New", IC_F_NEW, "New Neural Code script (.nc)",
                () -> EditorManager.newUPIFile(ide),
                RibbonItems.menuItem("Script", IC_F_NEW_SCRIPT,
                        () -> EditorManager.newUPIFile(ide)),
                RibbonItems.menuItem("Function", IC_F_NEW_FUNCTION,
                        () -> EditorManager.newFunctionFile(ide)),
                RibbonItems.menuItem("Class", IC_F_NEW_CLASS,
                        () -> EditorManager.newClassFile(ide)),
                RibbonItems.menuItem("Plain Text", IC_F_NEW_TXT,
                        () -> EditorManager.newPlainTextFile(ide)));

        RibbonSplitButton openBtn = RibbonItems.split(
                "Open", IC_F_OPEN, "Open a Neural Code file (.nc)",
                () -> EditorManager.openNcFile(ide),
                RibbonItems.menuItem("Open File", IC_F_OPEN_FILE,
                        () -> EditorManager.openFile(ide)),
                RibbonItems.menuItem("Open Folder", IC_F_OPEN,
                        () -> EditorManager.openProjectFolder(ide)),
                RibbonItems.menuItem("Open Last Project", IC_F_OPEN_LAST,
                        () -> EditorManager.openLastProject(ide)),
                RibbonItems.menuItem("Open Root Folder", IC_F_OPEN_ROOT,
                        () -> EditorManager.openRootFolder(ide)));

        RibbonSplitButton saveBtn = RibbonItems.split(
                "Save", IC_F_SAVE, "Save the current file",
                () -> EditorManager.saveCurrentFile(ide),
                RibbonItems.menuItem("Save", IC_F_SAVE,
                        () -> EditorManager.saveCurrentFile(ide)),
                RibbonItems.menuItem("Save As", IC_F_SAVE_AS,
                        () -> EditorManager.saveAsFile(ide)),
                RibbonItems.menuItem("Save Root", IC_F_SAVE_ROOT,
                        () -> EditorManager.saveToProjectRoot(ide)),
                RibbonItems.menuItem("Save In", IC_F_SAVE_IN,
                        () -> EditorManager.saveInFolder(ide)));

        return new RibbonGroup("Files", 2).withItems(newBtn, openBtn, saveBtn);
    }

    // =====================================================================
    // 2. Data -- 2 units -- each item opens its own window
    // =====================================================================
    // Short visible labels: three items share 2 units, so "Process Data"
    // at full length collides with its neighbours below roughly 2000px.
    // The "Data" caption underneath supplies the missing noun; the full
    // name still shows in the tooltip and in each window's title bar.
    //
    // The window shells live under ui.editor.homeTab.{Import,Process,Export}
    // so each stage owns its own package alongside its future back end.
    private static RibbonGroup dataGroup(UI_Main ide) {
        return new RibbonGroup("Data", 2).withItems(
                RibbonItems.item("Import", "Import Data", IC_IMPORT_DATA,
                        () -> openWindow(ide, "Import Data",
                                () -> UI_Home_Import_Main.open(ide))),
                RibbonItems.item("Process", "Process Data", IC_PROCESS_DATA,
                        () -> openWindow(ide, "Process Data",
                                () -> UI_Home_Process_Main.open(ide))),
                RibbonItems.item("Export", "Export Data", IC_EXPORT_DATA,
                        () -> openWindow(ide, "Export Data",
                                () -> UI_Home_Export_Main.open(ide))));
    }

    /**
     * Opens a Data window on the FX thread, reporting any failure to the
     * Command Window rather than letting it vanish into an uncaught handler.
     * Mirrors how the Plot button guards its own window construction.
     *
     * @param ide    the IDE controller
     * @param name   feature name, used in the failure message
     * @param opener the window's open call
     */
    private static void openWindow(UI_Main ide, String name, Runnable opener) {
        Platform.runLater(() -> {
            try {
                opener.run();
            } catch (Exception ex) {
                TerminalManager.println(ide, name + " window failed: " + ex.getMessage());
                ex.printStackTrace();
            }
        });
    }

    // =====================================================================
    // 3. Visualisation -- 1 unit
    // =====================================================================
    private static RibbonGroup visualisationGroup(UI_Main ide) {
        return new RibbonGroup("Visualisation", 1).withItems(
                RibbonItems.item("Plot", IC_PLOT, () -> Platform.runLater(() -> {
                    try {
                        File currentFolder = (ide.currentFolder != null)
                                ? ide.currentFolder.toFile()
                                : null;
                        PlotWindow pw = new PlotWindow(ide, currentFolder);
                        pw.show();
                    } catch (Exception ex) {
                        TerminalManager.println(ide, "Plot window failed: " + ex.getMessage());
                        ex.printStackTrace();
                    }
                })));
    }

    // =====================================================================
    // 4. Modelling -- 1 unit
    // =====================================================================
    private static RibbonGroup modellingGroup(UI_Main ide) {
        return new RibbonGroup("Modelling", 1).withItems(
                RibbonItems.item("Multi Physics", IC_MULTI_PHYSICS,
                        () -> UI_MP_Main.open(ide.stage)));
    }

    // =====================================================================
    // 5. Graphical -- 1 unit
    // =====================================================================
    private static RibbonGroup graphicalGroup(UI_Main ide) {
        return new RibbonGroup("Graphical", 1).withItems(
                RibbonItems.item("Neural Link", IC_NEURAL_LINK,
                        () -> NeuralLinkWindow.open(ide.stage)));
    }

    // =====================================================================
    // 6. Compute -- 2 units -- Step executes the open script one unit at a
    // time (see ui.editor.step.StepController); Run and Stop keep their wiring
    // =====================================================================
    private static RibbonGroup computeGroup(UI_Main ide) {
        Button stepBtn = RibbonItems.item("Step", IC_STEP, () -> {
        });
        stepBtn.setTooltip(new Tooltip("Execute the next statement or block"));
        stepBtn.setOnAction(e -> ui.editor.step.StepController.onStepClicked(ide, stepBtn));

        return new RibbonGroup("Compute", 2).withItems(
                stepBtn,
                RibbonItems.item("Run", IC_RUN, () -> EditorManager.runCurrentFile(ide)),
                RibbonItems.item("Stop", IC_STOP, () -> {
                    ui.editor.step.StepController.onStop(ide);
                    if (ide.isProgramRunning()) {
                        ide.stopProgram();
                    }
                }));
    }

    // =====================================================================
    // 7. Analysis -- 2 units -- readouts, not buttons
    // =====================================================================
    private static RibbonGroup analysisGroup() {
        return new RibbonGroup("Analysis", 2).withContent(RibbonAnalysis.buildContent());
    }

    // =====================================================================
    // 8. AI Help -- 1 unit
    // =====================================================================
    private static RibbonGroup aiHelpGroup(UI_Main ide) {
        return new RibbonGroup("AI Help", 1).withItems(
                RibbonItems.stub("Code Bot", IC_CODE_BOT, ide));
    }
}
