package ui.cli;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.util.Duration;
import language.syntax.syntax_checker.ErrorClassifier;
import language.syntax.syntax_checker.core.CheckContext;
import ui.UI_Main;
import ui.editor.homeTab.StepFileRunner;
import ui.ui_functions.Variable;
import ui.ui_functions.WorkspaceStore;

/**
 * <h2>CLI_Main — the Command Window controller</h2>
 *
 * Single Java owner of the Command Window.
 *
 * <h3>Design contract</h3>
 * <ul>
 *   <li><b>C++ owns evaluation.</b> One persistent {@code nc.exe} is the live
 *       backend for the whole session, so the workspace persists between
 *       commands and between script runs.</li>
 *   <li><b>Java owns the prompt.</b> {@link ConsoleInputGuard} owns the
 *       editable region; this class owns command dispatch.</li>
 *   <li><b>{@code variables.txt} is the workspace.</b> {@link WorkspaceStore}
 *       is refreshed from it whenever the {@code >> } prompt returns, which is
 *       the only race-free moment: the interpreter has finished writing.</li>
 * </ul>
 *
 * <h3>Command semantics</h3>
 * <table>
 *   <tr><td>{@code cls}</td><td>clear the screen only; workspace untouched</td></tr>
 *   <tr><td>{@code clear}</td><td>reset the workspace only; screen untouched</td></tr>
 *   <tr><td>{@code clear A}</td><td>remove one variable; screen untouched</td></tr>
 * </table>
 *
 * <p><b>These work against your current nc.exe with no recompile.</b>
 * {@code cls} never reaches the backend. {@code clear} maps onto the existing
 * {@code clear} builtin. {@code clear <name>} is done by rewriting
 * {@code variables.txt} from Java and sending {@code @cmd syncws} — see
 * {@link #clearOne} for the one caveat.
 *
 * <h3>What changed (a broken script never reaches the backend)</h3>
 * <ul>
 *   <li><b>Typed script names are checked first.</b> When the line typed at
 *       the prompt is a bare script name ({@code sct_01}, {@code sct_01.nc},
 *       optionally ending in {@code ;}) and that {@code .nc} file exists in the
 *       backend's working folder, the saved file is run through the editor's
 *       checks before anything is sent. If it has errors, each one is listed
 *       with its category and line -- exactly as the Run button lists them --
 *       and the command is not forwarded. See {@link #blockedScriptReport}.</li>
 *   <li><b>Safe by construction.</b> The check only ever <i>adds</i> a
 *       refusal: anything it cannot decide (the line is not a bare name, no
 *       such file, the file cannot be read, a workspace variable has that name,
 *       any unexpected failure) falls through to the backend unchanged, so no
 *       command that worked before can stop working because of it. A bare name
 *       that is also a workspace variable is left to the backend, since the
 *       user may mean the variable; typing it with {@code .nc} makes it
 *       unambiguous.</li>
 *   <li><b>{@link #reportBlockedRun}</b> prints a refused run the way a real
 *       one starts -- the script name echoed at the prompt -- followed by the
 *       report and a fresh prompt. The Run button uses it.</li>
 * </ul>
 *
 * <h3>Stop (Ctrl+C, the ribbon Stop, F6, the three-dot menu)</h3>
 * <p>All four call {@link #stopExecution(UI_Main)}. A Windows child process
 * on pipes cannot be sent a real Ctrl+C, so a running command is stopped by
 * <b>killing and relaunching</b> {@code nc.exe}:
 * <ol>
 *   <li>The old process is orphaned first (its reader and exit watcher check
 *       a generation number), then killed, so none of its trailing output or
 *       its death notice can land on the new session.</li>
 *   <li>"Execution stopped by user." is printed with a fresh prompt.</li>
 *   <li>A new {@code nc.exe} is started on the <b>same workspace</b>:
 *       {@code variables.lastgood.txt}, copied every time a prompt returns
 *       (the only moment {@code variables.txt} is known to be complete), is
 *       restored first, and the interpreter loads it at startup. The working
 *       folder is re-sent with {@code @cmd setpwd}.</li>
 * </ol>
 * The workspace therefore comes back exactly as it was after the last
 * <i>completed</i> command; whatever the interrupted command changed is
 * discarded. A cooperative interrupt in the backend would keep that partial
 * state -- {@link #restartBackend(String)} is the one place to swap it in.
 *
 * <h3>Busy</h3>
 * <p>Java now knows whether a command is executing. Every line the user (or
 * the Step runner, or Run) sends is counted; every {@code >> } the
 * interpreter answers with pays one back. {@link #busyProperty()} is true
 * while replies are owed, and drives Ctrl+C (stop when busy, copy when idle)
 * and the enabled state of both Stop controls. If the interpreter answers a
 * multi-line block with fewer prompts than lines, a short settle timer clears
 * the debt once the console has sat at a prompt with no further output.
 *
 * <h3>Self-healing backend</h3>
 * <p>If {@code nc.exe} is not running when a command, a Run or a Step needs
 * it, it is relaunched on the saved workspace instead of answering "UPI
 * terminal is not running." The workspace is only ever wiped on the first
 * launch of a session.
 */
public final class CLI_Main {

    /** The interactive prompt printed by nc.exe. */
    static final String PROMPT = ">> ";

    /** Optional override: -Dupi.nc.exe=C:\\path\\to\\nc.exe */
    private static final String NC_EXE_PROPERTY = "upi.nc";

    private static final String[] NC_EXE_CANDIDATES = {
            "cpp/nc",
            "src/main/cpp/nc",
            "nc"
    };

    private static UI_Main ide;
    private static Process process;
    private static BufferedWriter processWriter;
    private static ConsoleInputGuard guard;

    /** Directory nc.exe runs in; also where the shared variables.txt lives. */
    private static Path workingDirectory;
    private static Path variablesTxt;

    private static CLI_SuggestionBridge suggestionBridge;

    /**
     * Prompts the interpreter will emit for commands the USER never typed.
     * Every {@code @cmd ...} the IDE sends is answered with a ">> " that must
     * not reach the screen, or the console grows a spurious ">> >> ".
     */
    private static int silentPrompts;

    /** Last directory sent with {@code @cmd setpwd}; avoids resending it. */
    private static Path lastWorkspaceDirectory;

    /**
     * Notified with the 1-based line, relative to the block that was sent,
     * whenever the interpreter reports "@STEPAT &lt;line&gt;" (a step-into pause).
     */
    private static IntConsumer stepAtListener;

    /**
     * Notified every time the console returns to a settled prompt. The Step
     * runner decides whether that means its block has finished.
     */
    private static Runnable stepIdleListener;

    /**
     * A line that names a script and nothing else: an identifier (any script,
     * as variable names are), an optional {@code .nc}, an optional {@code ;}.
     */
    private static final Pattern SCRIPT_CALL = Pattern.compile(
            "^([\\p{L}_][\\p{L}\\p{M}\\p{N}_]*)(\\.nc)?\\s*;?$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);

    /** Scripts larger than this are not pre-checked; the backend handles them as before. */
    private static final long SCRIPT_CHECK_MAX_BYTES = 8L * 1024 * 1024;

    /** {@code -Dastra.workspace.persist=true} keeps the previous session's variables. */
    private static final String PERSIST_PROPERTY = "astra.workspace.persist";

    /** Copy of variables.txt taken whenever a prompt returns; restored on a restart. */
    private static final String SNAPSHOT_FILE = "variables.lastgood.txt";

    /** Printed when a running command is stopped. */
    private static final String STOP_MESSAGE = "Execution stopped by user.\n";

    /** Printed when the interpreter dies on its own. */
    private static final String CRASH_MESSAGE =
            "UPI backend stopped unexpectedly. It restarts with your next command.\n";

    /** How long the console must sit at a prompt before an unpaid reply debt is cleared. */
    private static final Duration SETTLE_DELAY = Duration.millis(400);

    /** Upper bound on waiting for a killed interpreter to exit. */
    private static final long KILL_WAIT_MS = 3000;

    private static Path snapshotTxt;

    /** Bumped on every launch and kill; output from an older process is dropped. */
    private static int generation;

    /** True once the first interpreter of this session has been launched. */
    private static boolean launchedOnce;

    /** True while the app is closing, so the exit watcher stays quiet. */
    private static boolean shuttingDown;

    /** Prompts still owed for lines the user, Run or Step sent. */
    private static int pendingReplies;

    private static final ReadOnlyBooleanWrapper BUSY = new ReadOnlyBooleanWrapper(false);

    private static PauseTransition settleTimer;

    /** Minimum gap between two output drains on the FX thread (about 30 per second). */
    private static final long DRAIN_INTERVAL_MS = 33;

    /** Most output held between two drains; older text is dropped beyond this. */
    private static final int PENDING_CAP = 256 * 1024;

    /** The console transcript is trimmed from the top once it passes this. */
    private static final int CONSOLE_MAX_CHARS = 200_000;

    /** What a trim leaves behind, so trimming happens in batches, not every drain. */
    private static final int CONSOLE_KEEP_CHARS = 150_000;

    private CLI_Main() {
    }

    // =====================================================================
    // 1. Console construction (called from Main_Window.setupConsole)
    // =====================================================================

    /**
     * Builds the console, installs the freeze, and attaches the shared
     * suggestion popup.
     *
     * @param ideRef the IDE controller
     */
    public static void initConsole(UI_Main ideRef) {
        ide = ideRef;
        guard = ConsoleController.initialize(ide);
        // Live symbol replacement first, so the suggestion bridge sees text
        // that has already been folded into its visual form.
        ConsoleSymbolInput.attach(ide, guard);
        suggestionBridge = new CLI_SuggestionBridge();
        suggestionBridge.attach(ide, guard);
        guard.setSuppressEnter(CLI_Main::isSuggestionShowing);
        guard.setOnSubmit(() -> handleEnter(ide));
        // Ctrl+C: stop while a command runs, copy otherwise.
        guard.setBusy(CLI_Main::isBusy);
        guard.setOnInterrupt(() -> stopExecution(ide));
    }

    // =====================================================================
    // 2. Backend lifecycle
    // =====================================================================

    /**
     * Locates and launches the persistent {@code nc.exe} backend. Idempotent:
     * calling it twice never starts a second interpreter.
     *
     * <p>Only the first launch of a session starts from an empty workspace.
     * Any later call (the Step runner arming after a crash, say) relaunches on
     * the saved workspace, so it can never wipe the user's variables.
     *
     * @param ideRef the IDE controller
     */
    public static void start(UI_Main ideRef) {
        ide = ideRef;
        if (isBackendAlive()) {
            return;
        }
        boolean fresh = !launchedOnce && !Boolean.getBoolean(PERSIST_PROPERTY);
        launch(fresh, launchedOnce, false);
    }

    /**
     * Starts one interpreter.
     *
     * @param freshWorkspace  empty variables.txt before the process starts
     * @param restoreSnapshot put the last complete workspace back first
     * @param silentStartup   swallow the startup prompt too (the caller has
     *                        painted its own, or is about to send a command)
     * @return true when an interpreter is now running
     */
    private static boolean launch(boolean freshWorkspace, boolean restoreSnapshot, boolean silentStartup) {
        Path exe = resolveNcExe();
        if (exe == null) {
            appendOutput("UPI backend not found. Set -D" + NC_EXE_PROPERTY
                    + "=<path to nc.exe> or place nc.exe under one of: "
                    + String.join(", ", NC_EXE_CANDIDATES) + "\n");
            appendPrompt();
            return false;
        }
        workingDirectory = exe.toAbsolutePath().getParent();
        variablesTxt = workingDirectory.resolve("variables.txt");
        snapshotTxt = workingDirectory.resolve(SNAPSHOT_FILE);

        // A new window means a new workspace. Parser's constructor calls
        // loadFromFile(), so variables.txt must be emptied BEFORE the process
        // starts — otherwise the interpreter inherits the previous session and
        // the Explorer fills with variables the user never declared here.
        if (freshWorkspace) {
            WorkspaceVariableParser.writeEmpty(variablesTxt);
            WorkspaceStore.get().clearAll();
            snapshotWorkspace();
        } else if (restoreSnapshot) {
            restoreWorkspaceSnapshot();
        }

        try {
            ProcessBuilder pb = new ProcessBuilder(exe.toAbsolutePath().toString());
            pb.directory(workingDirectory.toFile());
            pb.redirectErrorStream(true);
            Process started = pb.start();

            generation++;
            int gen = generation;
            process = started;
            launchedOnce = true;
            shuttingDown = false;
            silentPrompts = silentStartup ? 1 : 0;

            ide.runningProcess = started;
            ide.programRunning = true;
            processWriter = new BufferedWriter(
                    new OutputStreamWriter(started.getOutputStream(), StandardCharsets.UTF_8));
            ide.processWriter = processWriter;

            startReader(started, gen);
            startExitWatcher(started, gen);

            // Resume in the folder the user was working in; on the very first
            // launch that is the project folder.
            Path folder = lastWorkspaceDirectory != null ? lastWorkspaceDirectory : ide.getCurrentFolder();
            lastWorkspaceDirectory = null;
            if (folder != null) {
                setWorkspaceDirectory(folder);
            }
            reloadWorkspace();
            return true;
        } catch (IOException ex) {
            appendOutput("Failed to start UPI terminal: " + ex.getMessage() + "\n");
            appendPrompt();
            return false;
        }
    }

    /**
     * Makes sure an interpreter is running, relaunching one on the saved
     * workspace if it is not. Used by every path that is about to send a
     * command, which is what retires "UPI terminal is not running."
     *
     * @return true when an interpreter is running
     */
    private static boolean ensureBackend() {
        if (isBackendAlive()) {
            return true;
        }
        if (ide == null) {
            return false;
        }
        boolean fresh = !launchedOnce && !Boolean.getBoolean(PERSIST_PROPERTY);
        return launch(fresh, launchedOnce, true);
    }

    /** @return true while an interpreter process is alive */
    public static boolean isBackendAlive() {
        return process != null && process.isAlive();
    }

    /**
     * Tells the backend which folder to resolve script names against.
     *
     * @param folder the project/workspace directory
     */
    public static void setWorkspaceDirectory(Path folder) {
        if (folder == null) {
            return;
        }
        Path absolute = folder.toAbsolutePath();
        if (absolute.equals(lastWorkspaceDirectory)) {
            return; // already there; resending only costs a spurious prompt
        }
        lastWorkspaceDirectory = absolute;
        sendInternal("@cmd setpwd \"" + absolute + "\"");
    }

    /**
     * Registers (or clears, with null) the callback for "@STEPAT &lt;line&gt;".
     *
     * @param listener callback receiving the 1-based line, or null
     */
    public static void setStepAtListener(IntConsumer listener) {
        stepAtListener = listener;
    }

    /**
     * Registers (or clears, with null) the callback run when the console
     * settles at a prompt.
     *
     * @param listener callback, or null
     */
    public static void setStepIdleListener(Runnable listener) {
        stepIdleListener = listener;
    }

    /**
     * Sends one step-into control line. {@code @cmd stepinto} is answered by a
     * prompt that must stay hidden, so it is sent silently. {@code @cmd stepnext}
     * is read by the paused interpreter itself and answered with no prompt, so
     * it is written without counting a reply owed.
     *
     * @param ideRef the IDE controller
     * @param line   {@code @cmd stepinto} or {@code @cmd stepnext}
     */
    public static void sendStepControl(UI_Main ideRef, String line) {
        if (ideRef != null) {
            ide = ideRef;
        }
        if (line == null) {
            return;
        }
        Runnable action = () -> {
            if (!isBackendAlive()) {
                return;
            }
            if (line.equals("@cmd stepnext")) {
                sendRaw(line);
            } else {
                sendInternal(line);
            }
        };
        if (Platform.isFxApplicationThread()) {
            action.run();
        } else {
            Platform.runLater(action);
        }
    }

    // =====================================================================
    // 3. Input handling
    // =====================================================================

    /**
     * Called by {@link ConsoleInputGuard} when ENTER is pressed on the prompt
     * line and the suggestion popup is not open.
     *
     * @param ideRef the IDE controller
     */
    public static void handleEnter(UI_Main ideRef) {
        ide = ideRef;
        String line = guard == null ? "" : guard.currentInput().strip();

        if (isExit(line)) {
            guard.appendRaw("\n");
            shutdown();
            if (ide.stage != null) {
                ide.stage.close();
            }
            Platform.exit();
            return;
        }

        guard.rememberCommand(line);

        // ---- cls: screen only. Never reaches the backend, so the workspace
        // ---- cannot be touched by it. ------------------------------------
        if (isClearScreen(line)) {
            guard.clearScreen();
            appendPrompt();
            return;
        }

        // ---- clear / clear <name>: workspace only, screen untouched -------
        String target = clearTarget(line);
        if (target != null) {
            guard.appendRaw("\n");
            guard.lockToEnd();
            if (target.isEmpty()) {
                clearAll();
            } else {
                clearOne(target);
            }
            return;
        }

        // ---- everything else: one line advance, forward to backend --------
        guard.appendRaw("\n");
        guard.lockToEnd();

        // ---- a script name: refuse it here if the editor's checks fail ----
        String blocked = blockedScriptReport(line);
        if (blocked != null) {
            appendOutput(blocked);
            appendPrompt();
            return;
        }

        if (!ensureBackend()) {
            appendOutput("UPI terminal is not running.\n");
            appendPrompt();
            return;
        }

        // The screen keeps the visual line the user typed; the interpreter is
        // handed the cleaned one, exactly as Run hands it cleaned_Runfile.nc.
        // Sending the raw line was why a Greek/Tamil/Sanskrit declaration made
        // in the Command Window silently did nothing. Pure-ASCII lines are
        // returned unchanged by the codec, so every command that worked before
        // still travels byte for byte.
        sendUser(CliSymbolCodec.encodeLine(line));
    }

    /**
     * Runs a script on the shared persistent backend.
     *
     * <p>The <b>displayed</b> name and the <b>executed</b> file are deliberately
     * different: the console shows {@code >> ssss.nc} (requirement 3) while the
     * backend is handed the absolute path of {@code cleaned_Runfile.nc}, which
     * is the only file {@code nc.exe} should ever execute (requirement 4).
     *
     * @param ideRef      the IDE controller
     * @param displayName what to echo at the prompt, e.g. {@code ssss.nc}
     * @param fileToRun   the cleaned file actually handed to the interpreter
     */
    public static void runScript(UI_Main ideRef, String displayName, Path fileToRun) {
        ide = ideRef;
        if (fileToRun == null) {
            return;
        }
        Platform.runLater(() -> {
            if (!ensureBackend()) {
                appendOutput("UPI terminal is not running.\n");
                appendPrompt();
                return;
            }
            guard.appendRaw(displayName == null ? fileToRun.getFileName().toString() : displayName);
            guard.appendRaw("\n");
            guard.lockToEnd();
            sendUser("\"" + fileToRun.toAbsolutePath() + "\"");
        });
    }

    /**
     * Kept so existing callers compile. Prefer
     * {@link #runScript(UI_Main, String, Path)}, which runs the cleaned file.
     *
     * @param ideRef     the IDE controller
     * @param scriptName the script's name or path
     */
    public static void runScriptByName(UI_Main ideRef, String scriptName) {
        ide = ideRef;
        if (scriptName == null || scriptName.isBlank()) {
            return;
        }
        String blocked = blockedScriptReport(scriptName);
        if (blocked != null) {
            reportBlockedRun(ideRef, scriptName, blocked);
            return;
        }
        Platform.runLater(() -> {
            if (!ensureBackend()) {
                appendOutput("UPI terminal is not running.\n");
                appendPrompt();
                return;
            }
            guard.appendRaw(scriptName);
            guard.appendRaw("\n");
            guard.lockToEnd();
            sendUser(scriptName);
        });
    }

    /**
     * Prints a refused run: the script name echoed at the prompt, as a real run
     * would show it, then the report, then a fresh prompt.
     *
     * @param ideRef      the IDE controller
     * @param displayName the name to echo, e.g. {@code t9_isolate3.nc}
     * @param report      the lines to print, each ending in a newline
     */
    public static void reportBlockedRun(UI_Main ideRef, String displayName, String report) {
        if (ideRef != null) {
            ide = ideRef;
        }
        if (guard == null || ide == null) {
            return;
        }
        Runnable paint = () -> {
            if (!endsWithPrompt()) {
                if (ide.console.getLength() > 0 && !endsWith("\n")) {
                    guard.appendRaw("\n");
                }
                guard.append(PROMPT);
            }
            if (displayName != null && !displayName.isBlank()) {
                guard.appendRaw(displayName);
            }
            guard.appendRaw("\n");
            guard.lockToEnd();
            guard.append(report == null || report.isEmpty() ? "Run aborted.\n" : report);
            appendPrompt();
        };
        if (Platform.isFxApplicationThread()) {
            paint.run();
        } else {
            Platform.runLater(paint);
        }
    }

    /**
     * Decides whether a command is a script call that must be refused.
     *
     * <p>
     * Returns the report to print when {@code typed} is a bare script name,
     * the matching {@code .nc} file exists in the backend's working folder, and
     * the editor's checks find errors in it. Returns null -- meaning "forward
     * the command as usual" -- in every other case, including every case this
     * method cannot decide. It checks the file as saved on disk, because that
     * is what the backend would run.
     *
     * @param typed the command as the user typed it
     * @return the report, or null to let the command through
     */
    static String blockedScriptReport(String typed) {
        try {
            if (typed == null) {
                return null;
            }
            Matcher call = SCRIPT_CALL.matcher(typed.strip());
            if (!call.matches()) {
                return null;
            }
            String base = call.group(1);
            boolean explicitExtension = call.group(2) != null;

            Path folder = lastWorkspaceDirectory != null ? lastWorkspaceDirectory : workingDirectory;
            if (folder == null) {
                return null;
            }
            Path script = folder.resolve(base + ".nc");
            if (!Files.isRegularFile(script) || Files.size(script) > SCRIPT_CHECK_MAX_BYTES) {
                return null;
            }
            // `A` may mean the variable A rather than A.nc; let the backend decide.
            if (!explicitExtension && isWorkspaceVariable(base)) {
                return null;
            }

            String text = Files.readString(script, StandardCharsets.UTF_8);
            ErrorClassifier.Classification found = ErrorClassifier.classify(text, CheckContext.forFile(script));
            if (!found.hasErrors()) {
                return null;
            }
            return ErrorClassifier.consoleReport(found, script.getFileName().toString(), "Run");
        } catch (IOException | RuntimeException e) {
            // The check may only ever add a refusal, never break a command.
            return null;
        }
    }

    /** @return true when the workspace holds a variable typed as {@code visualName} */
    private static boolean isWorkspaceVariable(String visualName) {
        try {
            String cleaned = CliSymbolCodec.encodeName(visualName);
            return cleaned != null && !cleaned.isBlank() && WorkspaceStore.get().contains(cleaned);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** @return true while the shared suggestion popup is visible. */
    public static boolean isSuggestionShowing() {
        return suggestionBridge != null && suggestionBridge.isShowing();
    }

    // =====================================================================
    // 4. clear / clear <name>
    // =====================================================================

    /**
     * Resets the whole workspace. Uses the interpreter's existing {@code clear}
     * builtin, so no rebuild is needed; the screen is left alone because Java
     * never clears it here.
     */
    private static void clearAll() {
        WorkspaceStore.get().clearAll();
        if (variablesTxt != null) {
            WorkspaceVariableParser.writeEmpty(variablesTxt);
        }
        // Keep the restart copy in step, or a later Stop would bring the
        // cleared variables back.
        snapshotWorkspace();
        if (isBackendAlive()) {
            sendUser("clear");
        } else {
            appendPrompt();
        }
    }

    /**
     * Removes one variable from the workspace.
     *
     * <p>The interpreter has no {@code unset} builtin, so this rewrites
     * {@code variables.txt} without that record and asks the REPL to reload it
     * with {@code @cmd syncws}.
     *
     * <p><b>Caveat with the current binary.</b> If your compiled {@code nc.exe}
     * predates the {@code syncws} hook in {@code main.cpp}, the unknown
     * {@code @cmd} is silently ignored: the file and the Explorer are correct,
     * but the REPL's in-memory copy still holds the variable and will write it
     * back on its next save. The rebuild notes in NOTES.md close this gap.
     *
     * @param visualName the name the user typed, in editor form
     */
    private static void clearOne(String visualName) {
        String cleaned = CliSymbolCodec.encodeName(visualName);
        if (cleaned == null || cleaned.isBlank()) {
            appendOutput("clear: no variable name given.\n");
            appendPrompt();
            return;
        }
        boolean known = WorkspaceStore.get().contains(cleaned);
        boolean removed = variablesTxt != null
                && WorkspaceVariableParser.removeVariable(variablesTxt, cleaned);

        if (!known && !removed) {
            appendOutput("clear: '" + visualName + "' is not in the workspace.\n");
            appendPrompt();
            return;
        }

        WorkspaceStore.get().remove(cleaned);
        snapshotWorkspace();
        if (isBackendAlive()) {
            sendInternal("@cmd syncws");
        } else {
            appendPrompt();
        }
    }

    // =====================================================================
    // 4b. Stop and busy state
    // =====================================================================

    /**
     * Stops whatever is running and returns the Command Window to a working
     * prompt. The single entry point for Ctrl+C, the ribbon Stop, F6 and the
     * three-dot menu's Stop.
     *
     * <p>Always rewinds every Step session. If a command is executing, the
     * interpreter is killed and relaunched on the saved workspace. If the
     * interpreter is already dead, it is relaunched so the console works
     * again. If it is alive and idle, nothing else happens.
     *
     * @param ideRef the IDE controller
     */
    public static void stopExecution(UI_Main ideRef) {
        if (ideRef != null) {
            ide = ideRef;
        }
        if (ide == null) {
            return;
        }
        Runnable action = () -> {
            // A step-into pause leaves the interpreter blocked on stdin even when
            // nothing is owed a reply, so it counts as running.
            boolean stepping = StepFileRunner.activeProperty().get();
            StepFileRunner.reset(ide);
            if (guard == null) {
                return;
            }
            boolean alive = isBackendAlive();
            if (alive && !isBusy() && !stepping) {
                return; // nothing is running
            }
            restartBackend(alive ? STOP_MESSAGE : null);
        };
        if (Platform.isFxApplicationThread()) {
            action.run();
        } else {
            Platform.runLater(action);
        }
    }

    /**
     * Kills the current interpreter and starts a new one on the saved
     * workspace. The seam for a future cooperative interrupt: if the backend
     * ever learns to abort a command on request, signal it here instead of
     * killing, and keep the rest of this method's console handling.
     *
     * @param message printed before the fresh prompt, or null for none
     */
    private static void restartBackend(String message) {
        // Orphan the old process before it dies, so neither its last chunks of
        // output nor its exit watcher can touch the new session.
        generation++;
        Process old = process;
        process = null;
        processWriter = null;
        ide.processWriter = null;
        ide.runningProcess = null;
        ide.programRunning = false;
        killQuietly(old);

        clearBusy();
        silentPrompts = 0;

        if (message != null) {
            startNewLine();
            guard.append(message);
        }
        appendPrompt();

        // Our prompt is already painted, so the new interpreter's startup
        // prompt is swallowed; any leftover ">> >> " is folded anyway.
        launch(false, true, true);
    }

    private static void killQuietly(Process old) {
        if (old == null) {
            return;
        }
        try {
            old.getOutputStream().close();
        } catch (IOException ignored) {
            // the process is being killed anyway
        }
        old.destroyForcibly();
        try {
            old.waitFor(KILL_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * True while a command sent by the user, Run or Step is still executing.
     *
     * @return the busy flag
     */
    public static boolean isBusy() {
        return BUSY.get();
    }

    /**
     * Observable busy flag, for enabling the Stop controls.
     *
     * @return read-only busy property (changes on the FX thread only)
     */
    public static ReadOnlyBooleanProperty busyProperty() {
        return BUSY.getReadOnlyProperty();
    }

    /**
     * Sends a line on behalf of another part of the IDE (the Step runner, via
     * {@code EditorManager.sendCommandToTerminal}) so that it counts towards
     * the busy state exactly like a typed command.
     *
     * @param ideRef  the IDE controller
     * @param command the line to send
     */
    public static void sendFromIde(UI_Main ideRef, String command) {
        if (ideRef != null) {
            ide = ideRef;
        }
        if (command == null) {
            return;
        }
        Runnable action = () -> {
            if (!ensureBackend()) {
                appendOutput("UPI terminal is not running.\n");
                appendPrompt();
                return;
            }
            sendUser(command);
        };
        if (Platform.isFxApplicationThread()) {
            action.run();
        } else {
            Platform.runLater(action);
        }
    }

    /** Records one more reply owed and raises the busy flag. */
    private static void markSent() {
        pendingReplies++;
        stopSettleTimer();
        setBusy(true);
    }

    private static void clearBusy() {
        pendingReplies = 0;
        stopSettleTimer();
        setBusy(false);
    }

    private static void setBusy(boolean busy) {
        if (Platform.isFxApplicationThread()) {
            BUSY.set(busy);
        } else {
            Platform.runLater(() -> BUSY.set(busy));
        }
    }

    /**
     * Clears an unpaid reply debt once the console has sat at a prompt with no
     * new output for {@link #SETTLE_DELAY}. Covers an interpreter that answers
     * a multi-line block with a single prompt.
     */
    private static void armSettleTimer() {
        if (settleTimer == null) {
            settleTimer = new PauseTransition(SETTLE_DELAY);
            settleTimer.setOnFinished(e -> {
                if (ide != null && ide.console != null && endsWithPrompt()) {
                    clearBusy();
                }
            });
        }
        settleTimer.playFromStart();
    }

    private static void stopSettleTimer() {
        if (settleTimer != null) {
            settleTimer.stop();
        }
    }

    // =====================================================================
    // 4c. Workspace snapshot (what a restart comes back to)
    // =====================================================================

    /** Copies variables.txt aside. Called only when the file is known complete. */
    private static void snapshotWorkspace() {
        if (variablesTxt == null || snapshotTxt == null || !Files.exists(variablesTxt)) {
            return;
        }
        try {
            Files.copy(variablesTxt, snapshotTxt, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException ignored) {
            // A missed snapshot only means a restart falls back to variables.txt.
        }
    }

    /** Puts the last complete workspace back before a relaunch. */
    private static void restoreWorkspaceSnapshot() {
        if (variablesTxt == null || snapshotTxt == null || !Files.exists(snapshotTxt)) {
            return;
        }
        try {
            Files.copy(snapshotTxt, variablesTxt, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException ignored) {
            // Fall back to whatever variables.txt holds.
        }
    }

    // =====================================================================
    // 5. Output streaming + Explorer refresh
    // =====================================================================

    private static void startReader(Process source, int gen) {
        OutputPump pump = new OutputPump(gen);
        Thread readerThread = new Thread(() -> {
            // A Reader keeps one decoder across reads. Constructing a String
            // per raw byte-chunk (the previous approach) corrupts any
            // multi-byte character that straddles a chunk boundary — which is
            // exactly what Greek and Tamil output is made of.
            try (Reader in = new InputStreamReader(source.getInputStream(), StandardCharsets.UTF_8)) {
                char[] buffer = new char[8192];
                int length;
                while ((length = in.read(buffer)) != -1) {
                    pump.offer(buffer, length);
                }
            } catch (IOException ignored) {
                // Stream closed on shutdown or kill — nothing to do.
            }
        }, "cli-nc-reader");
        readerThread.setDaemon(true);
        readerThread.start();
    }

    /**
     * Moves interpreter output onto the FX thread in <b>batches</b>.
     *
     * <h3>Why this exists (the frozen screen)</h3>
     * The reader used to post one {@code Platform.runLater} per 2 KB chunk. A
     * loop such as {@code while 1: publish("running....!")} produces chunks far
     * faster than the FX thread can append them, and every append costs time
     * proportional to the <i>whole</i> transcript: the console re-wraps its
     * text, and two text listeners (symbol input, suggestions) each rebuild
     * the full transcript string on every change. The FX event queue filled
     * with output faster than it drained, so clicks -- the Stop button, the
     * menus, the editor -- waited behind thousands of appends and the window
     * looked frozen.
     *
     * <h3>What it does now</h3>
     * <ul>
     *   <li>The reader thread only appends to a buffer. At most one drain is
     *       queued at a time, and drains are spaced at least
     *       {@link #DRAIN_INTERVAL_MS} apart, so the FX thread always has time
     *       left for input.</li>
     *   <li>Output arriving faster than that is coalesced into one append per
     *       drain. If more than {@link #PENDING_CAP} characters pile up between
     *       drains, the oldest are dropped -- nobody can read them scrolling
     *       past at that rate anyway, and the newest output (including the
     *       final prompt) is always kept.</li>
     *   <li>The console itself is capped at {@link #CONSOLE_MAX_CHARS}; see
     *       {@code ConsoleInputGuard.trimHistory}.</li>
     * </ul>
     * One pump belongs to one interpreter; after a Stop its leftovers are
     * discarded by the generation check.
     */
    private static final class OutputPump {
        private final int gen;
        private final StringBuilder pending = new StringBuilder();
        private boolean scheduled;
        private long lastDrainNanos;

        OutputPump(int gen) {
            this.gen = gen;
        }

        /** Reader thread: buffer the chunk, queue one drain if none is queued. */
        void offer(char[] buffer, int length) {
            synchronized (this) {
                pending.append(buffer, 0, length);
                if (pending.length() > PENDING_CAP) {
                    int cut = pending.length() - PENDING_CAP;
                    // Never split a surrogate pair.
                    if (Character.isLowSurrogate(pending.charAt(cut))) {
                        cut++;
                    }
                    pending.delete(0, cut);
                }
                if (scheduled) {
                    return;
                }
                scheduled = true;
            }
            Platform.runLater(this::scheduleDrain);
        }

        /** FX thread: drain now, or after the rest of the minimum interval. */
        private void scheduleDrain() {
            long sinceMs = (System.nanoTime() - lastDrainNanos) / 1_000_000L;
            long waitMs = DRAIN_INTERVAL_MS - sinceMs;
            if (waitMs <= 0) {
                drain();
            } else {
                PauseTransition delay = new PauseTransition(Duration.millis(waitMs));
                delay.setOnFinished(e -> drain());
                delay.play();
            }
        }

        private void drain() {
            String text;
            synchronized (this) {
                text = pending.toString();
                pending.setLength(0);
                scheduled = false;
            }
            lastDrainNanos = System.nanoTime();
            if (gen == generation && !text.isEmpty()) {
                onOutput(text);
            }
        }
    }

    private static void startExitWatcher(Process watched, int gen) {
        Thread watcher = new Thread(() -> {
            try {
                watched.waitFor();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            Platform.runLater(() -> {
                if (gen != generation) {
                    return; // stopped on purpose and already replaced
                }
                process = null;
                processWriter = null;
                ide.programRunning = false;
                ide.runningProcess = null;
                ide.processWriter = null;
                clearBusy();
                if (!shuttingDown) {
                    startNewLine();
                    appendOutput(CRASH_MESSAGE);
                    appendPrompt();
                }
            });
        }, "cli-nc-exit-watcher");
        watcher.setDaemon(true);
        watcher.start();
    }

    private static void onOutput(String text) {
        if (ide == null || ide.console == null || text == null || text.isEmpty() || guard == null) {
            return;
        }
        stopSettleTimer();

        // Prompts owed to IDE-internal commands are consumed here rather than
        // printed. The interpreter answers every line it reads with a prompt,
        // including the "@cmd" lines the user never typed.
        String remaining = text;
        while (silentPrompts > 0 && remaining.startsWith(PROMPT)) {
            remaining = remaining.substring(PROMPT.length());
            silentPrompts--;
        }
        if (!remaining.isEmpty() && !remaining.startsWith(PROMPT)) {
            // Real output arrived, so any prompt we were still waiting to
            // swallow is never coming; drop the debt rather than let it eat a
            // legitimate prompt later.
            silentPrompts = 0;
        }

        // "@STEPAT <line>" is a step-into pause marker, not script output: hand
        // it to the Step runner and keep it off the console. Best-effort across
        // read chunks, like the prompt handling around it.
        if (remaining.contains("@STEPAT ")) {
            Matcher m = Pattern.compile("@STEPAT (\\d+)\\r?\\n?").matcher(remaining);
            StringBuilder stripped = new StringBuilder();
            int last = 0;
            while (m.find()) {
                stripped.append(remaining, last, m.start());
                last = m.end();
                if (stepAtListener != null) {
                    stepAtListener.accept(Integer.parseInt(m.group(1)));
                }
            }
            stripped.append(remaining.substring(last));
            remaining = stripped.toString();
        }

        // Every prompt left after the internal ones pays back one reply owed.
        if (pendingReplies > 0) {
            pendingReplies = Math.max(0, pendingReplies - countPrompts(remaining));
        }

        if (!remaining.isEmpty()) {
            // nc.exe answers in cleaned names ("var_mu = 25"). The console is
            // the user's side of the boundary, so names are turned back into
            // their visual form here and the echo reads "μ = 25". Ordinary
            // names and all other output are untouched.
            guard.appendRaw(CliSymbolCodec.decodeOutput(remaining));
        }
        collapseTrailingPrompts();

        // Bound the transcript: the cost of every append and every text
        // listener grows with its length.
        guard.trimHistory(CONSOLE_MAX_CHARS, CONSOLE_KEEP_CHARS);

        // The prompt marks the end of a command: the interpreter has finished
        // and variables.txt is fully written, so this is the correct moment to
        // refresh the workspace.
        if (endsWithPrompt()) {
            guard.lockToEnd();
            snapshotWorkspace();
            reloadWorkspace();
            if (stepIdleListener != null) {
                stepIdleListener.run();
            }
            if (pendingReplies == 0) {
                setBusy(false);
            } else {
                armSettleTimer();
            }
        }
    }

    private static int countPrompts(String text) {
        int count = 0;
        int from = 0;
        while ((from = text.indexOf(PROMPT, from)) >= 0) {
            count++;
            from += PROMPT.length();
        }
        return count;
    }

    /**
     * Collapses ">> >> " down to a single prompt.
     *
     * <p>Safety net for the suppression above: output can be delivered in
     * chunks that split a prompt across two reads, in which case the
     * {@code startsWith} test misses. Two prompts with nothing between them are
     * always noise, whatever produced them, so they are folded here.
     */
    private static void collapseTrailingPrompts() {
        guard.systemWrite(() -> {
            while (endsWith(PROMPT + PROMPT)) {
                int length = ide.console.getLength();
                ide.console.deleteText(length - PROMPT.length(), length);
            }
            ide.console.positionCaret(ide.console.getLength());
        });
    }

    /**
     * Reloads {@code variables.txt} into the Explorer. Rows are merged in
     * place by {@link WorkspaceStore}, so nothing is duplicated and nothing is
     * dropped that the interpreter still holds.
     */
    public static void reloadWorkspace() {
        if (variablesTxt == null || !Files.exists(variablesTxt)) {
            return;
        }
        List<Variable> globals = WorkspaceVariableParser.parse(variablesTxt);
        WorkspaceStore.get().sync(globals);
    }

    /** @return the shared workspace file, or null before the backend starts */
    public static Path getVariablesFile() {
        return variablesTxt;
    }

    // =====================================================================
    // 6. Low-level plumbing
    // =====================================================================

    /**
     * Sends a command the user did not type, and records that its prompt must
     * be swallowed rather than displayed.
     *
     * @param line the internal command
     */
    private static void sendInternal(String line) {
        silentPrompts++;
        sendRaw(line);
    }

    /**
     * Sends a line whose prompt the user is waiting for, and counts it towards
     * the busy state.
     *
     * @param line the command
     */
    private static void sendUser(String line) {
        markSent();
        if (!sendRaw(line)) {
            // No reply is coming for a line that never left.
            clearBusy();
            appendPrompt();
        }
    }

    /**
     * Writes one line to the interpreter's stdin.
     *
     * @param line the line
     * @return true when the line was written
     */
    private static boolean sendRaw(String line) {
        if (processWriter == null) {
            appendOutput("Input error: backend not connected.\n");
            return false;
        }
        try {
            processWriter.write(line);
            processWriter.newLine();
            processWriter.flush();
            return true;
        } catch (IOException ex) {
            appendOutput("Input error: " + ex.getMessage() + "\n");
            return false;
        }
    }

    /** Cleanly stops the backend (used on Exit / app close). */
    public static void shutdown() {
        shuttingDown = true;
        try {
            if (processWriter != null) {
                processWriter.write("exit");
                processWriter.newLine();
                processWriter.flush();
            }
        } catch (IOException ignored) {
            // fall through to destroy
        }
        if (process != null && process.isAlive()) {
            process.destroy();
        }
    }

    /**
     * Appends IDE-generated text to the console, respecting the freeze.
     *
     * @param text the text to append
     */
    public static void appendOutput(String text) {
        if (guard == null) {
            return;
        }
        if (Platform.isFxApplicationThread()) {
            guard.append(text);
        } else {
            Platform.runLater(() -> guard.append(text));
        }
    }

    /** Ends a half-written output line ("running....!" cut off by a kill). */
    private static void startNewLine() {
        if (guard != null && ide != null && ide.console != null
                && ide.console.getLength() > 0 && !endsWith("\n")) {
            guard.appendRaw("\n");
        }
    }

    /** Paints a prompt locally (only when the backend cannot provide one). */
    public static void appendPrompt() {
        if (guard == null) {
            return;
        }
        Runnable paint = () -> {
            if (!endsWithPrompt()) {
                if (ide.console.getLength() > 0 && !endsWith("\n")) {
                    guard.appendRaw("\n");
                }
                guard.append(PROMPT);
            }
        };
        if (Platform.isFxApplicationThread()) {
            paint.run();
        } else {
            Platform.runLater(paint);
        }
    }

    private static Path resolveNcExe() {
        String override = System.getProperty(NC_EXE_PROPERTY);
        if (override != null && !override.isBlank()) {
            Path p = Path.of(override);
            if (Files.exists(p)) {
                return p;
            }
        }
        Path base = Path.of(System.getProperty("user.dir"));
        for (String candidate : NC_EXE_CANDIDATES) {
            Path p = base.resolve(candidate);
            if (Files.exists(p)) {
                return p;
            }
        }
        return null;
    }

    private static boolean endsWithPrompt() {
        return endsWith(PROMPT);
    }

    private static boolean endsWith(String suffix) {
        int length = ide.console.getLength();
        if (length < suffix.length()) {
            return false;
        }
        return suffix.equals(ide.console.getText(length - suffix.length(), length));
    }

    private static boolean isExit(String s) {
        String n = norm(s);
        return n.equals("exit") || n.equals("exit()") || n.equals("quit") || n.equals("quit()");
    }

    /** cls = clear screen only (keep workspace). */
    private static boolean isClearScreen(String s) {
        String n = norm(s);
        return n.equals("cls") || n.equals("clc");
    }

    /**
     * Recognises {@code clear} and {@code clear <name>}.
     *
     * @param s the submitted line
     * @return null when this is not a clear command, "" for a full clear, or
     *         the variable name for a targeted clear
     */
    private static String clearTarget(String s) {
        String raw = s == null ? "" : s.strip();
        String lower = raw.toLowerCase(Locale.ROOT);
        if (lower.equals("clear")) {
            return "";
        }
        if (lower.startsWith("clear ")) {
            return raw.substring(6).strip();
        }
        return null;
    }

    private static String norm(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }
}
