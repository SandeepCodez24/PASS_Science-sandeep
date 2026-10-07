package ui.editor.homeTab;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.WeakHashMap;
import java.util.regex.Pattern;

import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;

import javafx.application.Platform;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.WeakChangeListener;
import javafx.scene.Node;
import javafx.scene.control.Tab;
import javafx.scene.layout.Region;
import language.syntax.syntax_checker.ErrorChecker;
import language.syntax.syntax_checker.ErrorClassifier;
import language.syntax.syntax_checker.core.CheckContext;
import ui.UI_Main;
import ui.cli.CLI_Main;
import ui.editor.EditorFileInfo;
import ui.editor.EditorGutter;
import ui.editor.EditorTextOperations;
import ui.managers.EditorManager;

/**
 * The <b>Step Runner</b> (Phase 2a): block-aware, step-<em>over</em> stepping of
 * the active {@code .nc} script.
 *
 * <h3>What changed (classified errors)</h3>
 * When the step runner refuses to arm, or a unit fails its check, the Command
 * Window now lists each error with its category and line --
 * "✖ Syntax Error (line 5): ..." -- using {@code ErrorClassifier}, the same
 * report the Run button prints. It used to say only how many errors there
 * were.
 *
 * <h3>What changed from Phase 1</h3>
 * <ul>
 *   <li><b>Block awareness.</b> A header that ends with {@code :} ({@code for},
 *       {@code while}, {@code if}, {@code function}, {@code class}) is grouped
 *       with everything up to and including its closer ({@code fend},
 *       {@code wend}, {@code iend}, {@code fnend}, {@code csend}) into a single
 *       execution <em>unit</em>. {@code else:} and {@code elseif ...:} are
 *       branches inside an {@code if}, not new blocks. The header is never fed
 *       to the checker or backend on its own, so the "orphan
 *       {@code if (X==Y):}" errors are gone.</li>
 *   <li><b>Clean CLI.</b> The runner no longer echoes each line. It forwards
 *       the unit to the persistent {@code nc.exe} and lets the backend's own
 *       rule speak: a {@code ;}-terminated statement stays silent, a statement
 *       without {@code ;} prints its value (MATLAB-style). Only step errors are
 *       announced by the runner.</li>
 *   <li><b>Backend fix.</b> The backend is started once at arm-time
 *       ({@link EditorManager#startUPITerminal}) and a "not running" notice is
 *       shown at most once per session instead of on every click.</li>
 * </ul>
 *
 * <h3>Stop</h3>
 * {@link #reset} rewinds <b>every</b> step session, not only the selected
 * tab's, because there is one backend and Stop stops all of it. It is called by
 * {@code CLI_Main.stopExecution}, which every Stop control goes through.
 * {@link #activeProperty()} is true while any session has taken a step and
 * not yet finished or been reset; the ribbon's Stop stays enabled for that
 * time so the arrow can always be cleared, even between clicks.
 *
 * <h3>Step-over vs step-into</h3>
 * This class steps <b>over</b> compound blocks: a loop or a call executes as one
 * unit and the arrow advances past it. Stepping <b>into</b> a body line-by-line
 * (and following call order into a function defined elsewhere) is Phase 2b and
 * requires the engine's stepping protocol — see {@link #dispatch}, the single
 * seam where that protocol plugs in.
 *
 * <h3>Gutter and highlight</h3>
 * The runner does NOT install a paragraph graphic factory of its own. It used
 * to, and that replacement had none of the editor gutter's fixed widths, so the
 * code slid left into the grey band as soon as a step run started (and the
 * fold column disappeared). The arrow is now an overlay in the gutter's error
 * column, registered through {@link EditorGutter#setErrorColumnOverlay}, so the
 * gutter keeps its exact geometry and the arrow survives fold / unfold.
 *
 * <p>The line highlight adds and removes its one style class and leaves every
 * other class on the paragraph alone. Overwriting the whole style used to wipe
 * the fold style (springing collapsed blocks open) and the error-line class.
 */
public final class StepFileRunner {

    // =====================================================================
    // Neural Code grammar, as the step runner needs it
    // =====================================================================
    /** A logical line ending with this is a block header or a branch header. */
    private static final String BLOCK_OPENER_SUFFIX = ":";

    /**
     * Block closers. Each stands alone on its line; only a {@code #} comment
     * may follow it.
     */
    private static final Pattern BLOCK_CLOSER =
            Pattern.compile("^(?:iend|fend|wend|fnend|csend)\\b");

    /**
     * Branch headers inside an {@code if}. They end with {@code :} but continue
     * the current block rather than opening a new one.
     */
    private static final Pattern BRANCH_HEADER = Pattern.compile("^(?:elseif|else)\\b");

    /** Line-continuation marker: folds the next physical line into this one. */
    private static final String CONTINUATION = "~~";

    /**
     * Comment prefixes that make a line non-executable: {@code #}, and the
     * {@code \\ } prefix the Ctrl+? toggle writes. A single backslash is NOT a
     * comment -- {@code \var(...)} and {@code \con(...)} lines start with one.
     */
    private static final String[] COMMENT_PREFIXES = {"#", "\\\\"};

    /** Shown once per session when a step is taken with no interpreter running. */
    private static final String BACKEND_DOWN_MESSAGE =
            "NC backend is not running; line not executed. Variables will not update until it starts.\n";

    /** Paragraph style class for the lines of the current unit. */
    private static final String STEP_LINE_CLASS = "step-current-line";

    /**
     * Arrow property holding its state listener. The session only keeps a weak
     * reference, so the arrow has to hold the strong one -- and when RichTextFX
     * discards the arrow, the listener goes with it.
     */
    private static final String ARROW_LISTENER_KEY = "step.arrow.stateListener";

    /** Per-tab stepping state. Weak so closed tabs are collected. */
    private static final WeakHashMap<Tab, StepSession> SESSIONS = new WeakHashMap<>();

    /** True while any session is mid-run (has stepped, not finished or reset). */
    private static final ReadOnlyBooleanWrapper ACTIVE = new ReadOnlyBooleanWrapper(false);

    /** The session whose block is running step-into, or null. One backend, so at most one. */
    private static StepSession activeSteppingSession;

    static {
        CLI_Main.setStepAtListener(StepFileRunner::onStepAt);
        CLI_Main.setStepIdleListener(StepFileRunner::onBackendIdle);
    }

    private StepFileRunner() {
    }

    /** A pause report from the interpreter: move the arrow to that statement. */
    private static void onStepAt(int line) {
        StepSession session = activeSteppingSession;
        if (session == null || line < 1 || line > session.sentParagraphs.size()) {
            return;
        }
        session.sawPause = true;
        session.pauseOutstanding = true;
        session.currentPara.set(session.sentParagraphs.get(line - 1));
    }

    /**
     * The console settled at a prompt. The block is over only when the
     * interpreter has paused at least once and has since been told to continue
     * (so this is not one of the prompts printed while the block was still being
     * read in).
     */
    private static void onBackendIdle() {
        StepSession session = activeSteppingSession;
        if (session == null || !session.blockInProgress
                || !session.sawPause || session.pauseOutstanding) {
            return;
        }
        session.blockInProgress = false;
        activeSteppingSession = null;
    }

    // =====================================================================
    // Public entry points (wired from HomeRibbon)
    // =====================================================================

    /**
     * Executes the next unit of the active editor tab.
     *
     * @param ide the IDE controller
     */
    public static void step(UI_Main ide) {
        try {
            stepOnce(ide);
        } finally {
            refreshActive();
        }
    }

    /**
     * True while a step run is in progress in any tab. Drives the ribbon
     * Stop's enabled state together with the Command Window's busy flag.
     *
     * @return read-only active property
     */
    public static ReadOnlyBooleanProperty activeProperty() {
        return ACTIVE.getReadOnlyProperty();
    }

    private static void refreshActive() {
        boolean any = false;
        for (StepSession session : new ArrayList<>(SESSIONS.values())) {
            if (session != null && session.pointer >= 0) {
                any = true;
                break;
            }
        }
        ACTIVE.set(any);
    }

    private static void stepOnce(UI_Main ide) {
        if (ide == null || ide.editorTabs == null) {
            return;
        }
        Tab tab = ide.editorTabs.getSelectionModel().getSelectedItem();
        if (tab == null) {
            CLI_Main.appendOutput("Step: no editor tab is open.\n");
            CLI_Main.appendPrompt();
            return;
        }
        CodeArea editor = codeAreaOf(tab);
        if (editor == null) {
            CLI_Main.appendOutput("Step runner works on Neural Code (.nc) tabs only.\n");
            CLI_Main.appendPrompt();
            return;
        }

        StepSession existing = SESSIONS.get(tab);
        if (existing != null && existing.blockInProgress) {
            // Inside a block: run one more statement of it, not the next unit.
            existing.pauseOutstanding = false;
            CLI_Main.sendStepControl(ide, "@cmd stepnext");
            return;
        }

        String text = EditorTextOperations.getEditorText(tab);
        StepSession session = SESSIONS.get(tab);

        // (Re)build when there is no session yet, or the text changed under us.
        if (session == null || !text.equals(session.sourceSnapshot)) {
            // A whole-script syntax gate, exactly like Run does before it hands
            // the file to the interpreter. If the script is broken, refuse to
            // arm and say why, rather than stepping into a guaranteed error.
            // The report lists every error by category and line, exactly as
            // the Run button's does, so the user knows why it refused.
            var whole = ErrorChecker.analyzeErrors(text, checkContextOf(tab));
            if (whole.getErrorCount() > 0) {
                CLI_Main.appendOutput(ErrorClassifier.consoleReport(
                        whole.getClassification(), tabFileName(tab), "Step"));
                CLI_Main.appendOutput("Fix them, then step.\n");
                CLI_Main.appendPrompt();
                return;
            }
            session = new StepSession(text);
            SESSIONS.put(tab, session);
            installGutter(editor, session);
            clearHighlight(editor, session);
            pointWorkspaceAtFile(tab);
            // Idempotent: never starts a second interpreter, but guarantees the
            // one backend is up before the first line is dispatched.
            EditorManager.startUPITerminal(ide);
            CLI_Main.appendOutput("Step runner armed: " + session.units.size()
                    + " unit(s).\n");
        }

        session.pointer++;

        // Ran off the end -> finish and rewind so a further click restarts.
        if (session.pointer >= session.units.size()) {
            CLI_Main.appendOutput("Step run complete.\n");
            CLI_Main.appendPrompt();
            clearHighlight(editor, session);
            session.currentPara.set(-1);
            session.state.set("none");
            session.pointer = -1;
            return;
        }

        Unit unit = session.units.get(session.pointer);
        showAt(editor, session, unit);

        // Cumulative check THROUGH the current unit. Because compound blocks are
        // grouped, the cumulative text is always syntactically complete (no
        // half-open "if:"), so the Phase-1 false positives cannot recur.
        String cumulative = session.cumulativeThrough(session.pointer);
        var report = ErrorChecker.analyzeErrors(cumulative, checkContextOf(tab));
        if (report.getErrorCount() > 0) {
            session.state.set("error");
            String where = unit.block ? "in the block starting at line " : "at line ";
            CLI_Main.appendOutput("\u2716 Step error " + where + unit.humanLine()
                    + ": " + unit.firstLine() + "\n");
            // The cumulative text starts at line 1 of the file, so the line
            // numbers in this report match the editor's.
            CLI_Main.appendOutput(ErrorClassifier.consoleReport(
                    report.getClassification(), tabFileName(tab), "Step"));
            CLI_Main.appendPrompt();
            return;
        }

        // Clean unit: forward it to the backend. No manual echo -- the backend
        // prints a value only when the statement omits ';'.
        session.state.set("ok");
        dispatch(ide, session, unit);
    }

    /**
     * The syntax checker's view of the tab's file, so script calls
     * ({@code ABC;}) are checked against the file's own folder.
     */
    private static CheckContext checkContextOf(Tab tab) {
        if (tab != null && tab.getUserData() instanceof EditorFileInfo info) {
            return CheckContext.forFile(info.path);
        }
        return CheckContext.NONE;
    }

    /**
     * The file name to show in a Command Window report: the saved file's name,
     * else the tab's title without its unsaved-changes star.
     */
    private static String tabFileName(Tab tab) {
        if (tab.getUserData() instanceof EditorFileInfo info && info.path != null
                && info.path.getFileName() != null) {
            return info.path.getFileName().toString();
        }
        String title = tab.getText() == null ? "" : tab.getText();
        return title.endsWith("*") ? title.substring(0, title.length() - 1) : title;
    }

    /**
     * Stops every step run: clears each highlight and gutter arrow and rewinds
     * each program counter, so the next Step starts from the top. Called by
     * {@code CLI_Main.stopExecution}, which every Stop control goes through.
     *
     * @param ide the IDE controller (unused beyond the null check; kept so
     *            existing callers compile)
     */
    public static void reset(UI_Main ide) {
        if (ide == null) {
            return;
        }
        try {
            for (var entry : new ArrayList<>(SESSIONS.entrySet())) {
                Tab tab = entry.getKey();
                StepSession session = entry.getValue();
                if (tab == null || session == null) {
                    continue;
                }
                CodeArea editor = codeAreaOf(tab);
                if (editor != null) {
                    clearHighlight(editor, session);
                } else {
                    session.highlighted.clear();
                }
                session.currentPara.set(-1);
                session.state.set("none");
                session.pointer = -1;
                session.blockInProgress = false;
                session.pauseOutstanding = false;
            }
            activeSteppingSession = null;
        } finally {
            refreshActive();
        }
    }

    // =====================================================================
    // Execution  --  THE PHASE 2b SEAM
    // =====================================================================

    /**
     * Sends one unit to the execution backend.
     *
     * <p><b>Phase 2a (today).</b> The unit's source lines are written to the
     * persistent {@code nc.exe} over stdin. A simple statement is one line; a
     * compound block is its full header+body, so the loop/def executes
     * atomically (step-over). The backend's prompt-return refreshes the
     * Variable Explorer on its own, exactly as typing at the console does.
     *
     * <p><b>Phase 2b (your engine work).</b> Replace this body with calls to the
     * stepping protocol: {@code engine.stepInto(unit)} / {@code engine.next()},
     * then read back {@code {currentLine, workspace, error}} and drive
     * {@link #showAt}/{@link #applyArrowState} from the ENGINE's program
     * counter instead of the editor's. Nothing else in this class needs to
     * change.
     */
    private static void dispatch(UI_Main ide, StepSession session, Unit unit) {
        if (!ide.programRunning) {
            if (!session.backendWarned) {
                CLI_Main.appendOutput(BACKEND_DOWN_MESSAGE);
                CLI_Main.appendPrompt();
                session.backendWarned = true;
            }
            return;
        }
        String[] physicalLines = unit.code.split("\n", -1);
        if (unit.block) {
            // Step-into: arm the interpreter's pause hook, then send the block.
            // It pauses after each statement it runs; onStepAt() maps those
            // pauses back to the editor lines recorded here.
            CLI_Main.sendStepControl(ide, "@cmd stepinto");
            session.sentParagraphs.clear();
            session.sawPause = false;
            session.pauseOutstanding = false;
            session.blockInProgress = true;
            activeSteppingSession = session;
        }
        for (int k = 0; k < physicalLines.length; k++) {
            // Blank and comment lines inside a block are for the reader only;
            // the interpreter never needs to see them.
            if (Program.isSkippable(physicalLines[k])) {
                continue;
            }
            if (unit.block) {
                session.sentParagraphs.add(unit.startPara + k);
            }
            // Line-oriented forward. If your REPL needs an explicit
            // block-submit for indented bodies, this is where a
            // "@block begin/end" wrapper (or the Phase-2b protocol) goes.
            EditorManager.sendCommandToTerminal(ide, physicalLines[k]);
        }
    }

    // =====================================================================
    // View: highlight + gutter arrow
    // =====================================================================

    private static void showAt(CodeArea editor, StepSession session, Unit unit) {
        clearHighlight(editor, session);
        for (int p = unit.startPara; p <= unit.endPara && p < editor.getParagraphs().size(); p++) {
            setStepLineStyle(editor, p, true);
            session.highlighted.add(p);
        }
        session.currentPara.set(unit.startPara);   // arrow rides the header row
        Platform.runLater(() -> {
            try {
                editor.showParagraphInViewport(unit.startPara);
                editor.moveTo(unit.startPara, 0);
            } catch (RuntimeException ignored) {
                // Paragraph index can race an edit; the next click recovers.
            }
        });
    }

    private static void clearHighlight(CodeArea editor, StepSession session) {
        for (int p : session.highlighted) {
            if (p < editor.getParagraphs().size()) {
                setStepLineStyle(editor, p, false);
            }
        }
        session.highlighted.clear();
    }

    /**
     * Adds or removes {@link #STEP_LINE_CLASS} on one paragraph, keeping every
     * other class it carries -- RichTextFX's fold style and ErrorStatusManager's
     * {@code error-line} in particular. No-op when nothing would change.
     */
    private static void setStepLineStyle(CodeArea editor, int paragraph, boolean on) {
        Collection<String> current;
        try {
            current = editor.getParagraph(paragraph).getParagraphStyle();
        } catch (RuntimeException e) {
            return;
        }
        boolean has = current != null && current.contains(STEP_LINE_CLASS);
        if (has == on) {
            return;
        }
        List<String> updated = new ArrayList<>();
        if (current != null) {
            for (String styleClass : current) {
                if (!STEP_LINE_CLASS.equals(styleClass)) {
                    updated.add(styleClass);
                }
            }
        }
        if (on) {
            updated.add(STEP_LINE_CLASS);
        }
        editor.setParagraphStyle(paragraph, updated);
    }

    /**
     * Puts this session's arrow into the editor gutter's error column.
     *
     * <p>The gutter itself is left exactly as {@code EditorGutter} built it, so
     * the band, the number slot, the fold column and the blank strip before the
     * code keep their widths and nothing moves. On the arrow's line the error
     * dot is hidden while the arrow shows (the column holds one or the other);
     * the line's error tooltip stays on the number.
     */
    private static void installGutter(CodeArea editor, StepSession session) {
        if (!EditorGutter.setErrorColumnOverlay(editor, index -> createArrow(session, index))) {
            // Not an editor built by EditorTabFactory: step without an arrow
            // rather than replace a gutter we know nothing about.
            System.err.println("Step runner: editor has no standard gutter; arrow not shown.");
        }
    }

    /**
     * The arrow for one line. Its size comes from {@code .step-arrow} in the
     * editor CSS (10px), which fits the 12px error column.
     */
    private static Node createArrow(StepSession session, int index) {
        Region arrow = new Region();
        arrow.getStyleClass().add("step-arrow");
        arrow.setMouseTransparent(true);
        arrow.setFocusTraversable(false);
        // Bindings.equal observes currentPara weakly, so discarded arrows are
        // collected normally.
        arrow.visibleProperty().bind(session.currentPara.isEqualTo(index));

        applyArrowState(arrow, session.state.get());
        ChangeListener<String> onState = (obs, was, now) -> applyArrowState(arrow, now);
        // Strong reference on the arrow, weak one on the session: rows are
        // rebuilt on every scroll and fold, and a strong listener per row used
        // to accumulate on the session for as long as the tab stayed open.
        arrow.getProperties().put(ARROW_LISTENER_KEY, onState);
        session.state.addListener(new WeakChangeListener<>(onState));
        return arrow;
    }

    private static void applyArrowState(Region arrow, String state) {
        arrow.getStyleClass().removeAll("step-arrow-ok", "step-arrow-error",
                "step-arrow-active", "step-arrow-none");
        arrow.getStyleClass().add("step-arrow-" + (state == null || state.isBlank() ? "none" : state));
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    private static void pointWorkspaceAtFile(Tab tab) {
        Object data = tab.getUserData();
        if (data instanceof EditorFileInfo info && info.path != null) {
            Path parent = info.path.toAbsolutePath().getParent();
            if (parent != null) {
                CLI_Main.setWorkspaceDirectory(parent);
            }
        }
    }

    private static CodeArea codeAreaOf(Tab tab) {
        Node content = tab.getContent();
        if (content instanceof VirtualizedScrollPane<?> scroll
                && scroll.getContent() instanceof CodeArea code) {
            return code;
        }
        if (content instanceof CodeArea code) {
            return code;
        }
        return null;
    }

    // =====================================================================
    // Model
    // =====================================================================

    /**
     * One execution unit: a simple statement (one logical line) or a compound
     * block (header + indented body). {@code code} preserves the raw source
     * lines so a block is dispatched exactly as written.
     */
    private static final class Unit {
        final int startPara;
        final int endPara;
        final String code;
        final boolean block;

        Unit(int startPara, int endPara, String code, boolean block) {
            this.startPara = startPara;
            this.endPara = endPara;
            this.code = code;
            this.block = block;
        }

        int humanLine() {
            return startPara + 1;
        }

        /** The header line, for messages. */
        String firstLine() {
            int nl = code.indexOf('\n');
            return (nl < 0 ? code : code.substring(0, nl)).strip();
        }
    }

    /** Per-tab stepping state: the parsed program plus its live view bindings. */
    private static final class StepSession {
        final String sourceSnapshot;
        final List<Unit> units;
        final List<Integer> highlighted = new ArrayList<>();
        final IntegerProperty currentPara = new SimpleIntegerProperty(-1);
        final StringProperty state = new SimpleStringProperty("none");
        int pointer = -1;
        boolean backendWarned = false;

        // Step-into state for the block currently running (see dispatch()).
        boolean blockInProgress = false;
        boolean sawPause = false;
        boolean pauseOutstanding = false;
        final List<Integer> sentParagraphs = new ArrayList<>();

        StepSession(String source) {
            this.sourceSnapshot = source;
            this.units = new Program(source).parse();
        }

        /** Joins units 0..index, for a cumulative, syntactically complete check. */
        String cumulativeThrough(int index) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i <= index && i < units.size(); i++) {
                sb.append(units.get(i).code).append('\n');
            }
            return sb.toString();
        }
    }

    /**
     * Splits raw source into {@link Unit}s: skips blanks and comments, folds
     * {@code ~~} continuations, and groups each block header with everything up
     * to its matching closer.
     *
     * <p>Grouping is by keyword, not by indentation. Neural Code closes every
     * block with a word ({@code fend}, {@code wend}, {@code iend},
     * {@code fnend}, {@code csend}) that sits at the header's own indentation,
     * so an indentation rule would cut the closer off into a unit of its own
     * and treat {@code else:} as a fresh block.
     */
    private static final class Program {
        private final String[] lines;

        Program(String source) {
            this.lines = source.split("\n", -1);
        }

        List<Unit> parse() {
            List<Unit> out = new ArrayList<>();
            int i = 0;
            while (i < lines.length) {
                if (isSkippable(lines[i])) {
                    i++;
                    continue;
                }
                int headerEnd = logicalEnd(i);
                String headerText = logicalText(i, headerEnd);

                if (opensBlock(headerText)) {
                    int blockEnd = matchingCloser(headerEnd + 1);
                    out.add(new Unit(i, blockEnd, rawSpan(i, blockEnd), true));
                    i = blockEnd + 1;
                } else {
                    out.add(new Unit(i, headerEnd, headerText, false));
                    i = headerEnd + 1;
                }
            }
            return out;
        }

        /**
         * Walks forward from the first body line, counting nested openers and
         * closers, and returns the line index of the closer that ends the
         * block. An unterminated block runs to the end of the file; the
         * whole-script syntax gate in {@link StepFileRunner#step} rejects such
         * a file before it ever gets here.
         */
        private int matchingCloser(int from) {
            int depth = 1;
            int j = from;
            int last = from - 1;
            while (j < lines.length) {
                if (isSkippable(lines[j])) {
                    last = j;
                    j++;
                    continue;
                }
                int end = logicalEnd(j);
                String text = logicalText(j, end);
                if (BLOCK_CLOSER.matcher(text).find()) {
                    depth--;
                } else if (opensBlock(text)) {
                    depth++;
                }
                last = end;
                if (depth == 0) {
                    return end;
                }
                j = end + 1;
            }
            return Math.max(from - 1, Math.min(last, lines.length - 1));
        }

        /** Last physical line of the logical line starting at {@code start}. */
        private int logicalEnd(int start) {
            int end = start;
            while (endsWithContinuation(lines[end]) && end + 1 < lines.length) {
                end++;
            }
            return end;
        }

        /** The logical line {@code start..end} with continuations folded. */
        private String logicalText(int start, int end) {
            StringBuilder text = new StringBuilder(dropContinuation(strip(lines[start])));
            for (int k = start + 1; k <= end; k++) {
                text.append(' ').append(dropContinuation(strip(lines[k])));
            }
            return text.toString().strip();
        }

        /** Raw source lines a..b inclusive, indentation preserved. */
        private String rawSpan(int a, int b) {
            StringBuilder sb = new StringBuilder();
            for (int k = a; k <= b && k < lines.length; k++) {
                sb.append(lines[k]);
                if (k < b) {
                    sb.append('\n');
                }
            }
            return sb.toString();
        }

        /** A header ending in {@code :} that is not an {@code else}/{@code elseif} branch. */
        private static boolean opensBlock(String logicalLine) {
            return logicalLine.endsWith(BLOCK_OPENER_SUFFIX)
                    && !BRANCH_HEADER.matcher(logicalLine).find();
        }

        static boolean isSkippable(String line) {
            String t = strip(line);
            if (t.isEmpty()) {
                return true;
            }
            for (String c : COMMENT_PREFIXES) {
                if (t.startsWith(c)) {
                    return true;
                }
            }
            return false;
        }

        private static boolean endsWithContinuation(String line) {
            return strip(line).endsWith(CONTINUATION);
        }

        private static String dropContinuation(String s) {
            String t = s.strip();
            return t.endsWith(CONTINUATION) ? t.substring(0, t.length() - CONTINUATION.length()).strip() : t;
        }

        private static String strip(String s) {
            return s == null ? "" : s.strip();
        }
    }
}
