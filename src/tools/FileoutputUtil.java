/*
 This file is part of the OdinMS Maple Story Server
 Copyright (C) 2008 ~ 2010 Patrick Huy <patrick.huy@frz.cc>
 Matthias Butz <matze@odinms.de>
 Jan Christian Meyer <vimes@odinms.de>

 This program is free software: you can redistribute it and/or modify
 it under the terms of the GNU Affero General Public License version 3
 as published by the Free Software Foundation. You may not use, modify
 or distribute this program under any other version of the
 GNU Affero General Public License.

 This program is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU Affero General Public License for more details.

 You should have received a copy of the GNU Affero General Public License
 along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package tools;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.LogRecord;

/**
 * The {@code Logs/*.rtf} topic files, on top of {@link System.Logger}.
 *
 * <p>The conventions for logging in this server - how loggers are named, which
 * level each of the tree's old reporting mechanisms maps to, and what must not
 * be converted - are documented once, on {@link Logging}. This class is the
 * part of them that addresses a <em>file</em> rather than a class: seven named
 * topics plus whatever {@code server.Timer} mints for its pools at runtime
 * ({@code Logs/Log_<pool>_Except.rtf}).
 *
 * <p>Each file gets a logger in the reserved {@code tms.} namespace
 * ({@code Log_Zakum.rtf} is {@code tms.boss.zakum}) with one handler of its
 * own and {@code useParentHandlers = false}, so a topic entry goes to its file
 * and nowhere else, exactly as before. Both of those are set only when
 * {@code Libs/logging.properties} has not already said otherwise, so
 * {@code tms.boss.level = OFF} silences the boss bookkeeping and
 * {@code tms.script.useParentHandlers = true} additionally puts script
 * exceptions on the console.
 *
 * <h2>Why the handler is not {@code java.util.logging.FileHandler}</h2>
 *
 * The file names are part of this server's interface - they are documented in
 * CLAUDE.md and an administrator greps them - and {@code FileHandler} cannot
 * be trusted with them. Measured, not assumed:
 *
 * <ul>
 * <li>{@code new FileHandler("Logs/Log_Zakum.rtf", 0, 1, true)} does keep the
 * name, because a {@code count} of 1 suppresses the {@code %g} generation
 * suffix - but only for the first handler to open it. A second one (a second
 * server instance, or a restart while the old JVM is still exiting) silently
 * becomes {@code Log_Zakum.rtf.1}, which is the {@code %u} rule and cannot be
 * turned off.</li>
 * <li>It writes a {@code Log_Zakum.rtf.lck} beside every topic file, so
 * {@code Logs/} grows a second file per topic that nothing documents.</li>
 * <li>It creates the file when the handler is constructed, so every topic that
 * never fires leaves an empty {@code .rtf} behind.</li>
 * <li>It holds the file open, so on Windows the log cannot be deleted or
 * rotated while the server runs - which is how these files have always been
 * cleared - and {@code LogManager}'s shutdown hook closes it while
 * {@code ShutdownServer} is still running on a thread of its own.</li>
 * </ul>
 *
 * <p>So the handler below keeps the open-append-close-per-entry behaviour this
 * had before. At these volumes (exception reports and boss kills) the extra
 * {@code open}/{@code close} is free, and it is what makes the two invariants
 * below hold.
 *
 * <h2>The two things that must not regress</h2>
 *
 * <p><b>One entry is one write.</b> Each entry used to be written in two calls,
 * a header and then the body. Appends are atomic per write, so two threads
 * logging at the same moment could interleave into "header, header, body,
 * body" and file a stack trace under the wrong timestamp. The formatter builds
 * the whole entry - separator, timestamp, message, stack trace - into one
 * string and the handler makes exactly one {@code write} of it to a stream
 * opened in append mode.
 *
 * <p><b>The timestamps are thread-safe.</b> They were formatted through two
 * shared static {@code SimpleDateFormat} instances, which keep parse state in a
 * field: two threads formatting at once corrupted each other, usually into a
 * mangled date and sometimes into an {@code ArrayIndexOutOfBoundsException}
 * thrown from inside {@code Calendar} - out of the error logger, on the error
 * path. {@link DateTimeFormatter} is immutable and has no such state.
 *
 * <p>Neither the topic files nor the API here depend on
 * {@code Libs/logging.properties} being present: the handlers are attached in
 * code. The config file governs the console and the levels.
 */
public class FileoutputUtil {

    // Logging output file
    public static final String Acc_Stuck = "Logs/Log_AccountStuck.rtf",
            Login_Error = "Logs/Log_Login_Error.rtf",
            //Timer_Log = "Log_Timer_Except.rtf",
            //MapTimer_Log = "Log_MapTimer_Except.rtf",
            IP_Log = "Logs/Log_AccountIP.rtf",
            //GMCommand_Log = "Log_GMCommand.rtf",
            Zakum_Log = "Logs/Log_Zakum.rtf",
            Horntail_Log = "Logs/Log_Horntail.rtf",
            Pinkbean_Log = "Logs/Log_Pinkbean.rtf",
            ScriptEx_Log = "Logs/Log_Script_Except.rtf",
            PacketEx_Log = "Logs/Log_Packet_Except.rtf" // I cba looking for every error, adding this back in.
            + "";
    // End
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /** The rule an entry's header is drawn with, unchanged since OdinMS. */
    private static final String RULE = "------------------------";

    /** The topic files that have a name of their own rather than a derived one. */
    private static final Map<String, String> TOPIC_NAMES = Map.of(
            Acc_Stuck, "tms.account.stuck",
            IP_Log, "tms.account.ip",
            Login_Error, "tms.login",
            Zakum_Log, "tms.boss.zakum",
            Horntail_Log, "tms.boss.horntail",
            Pinkbean_Log, "tms.boss.pinkbean",
            ScriptEx_Log, "tms.script",
            PacketEx_Log, "tms.packet");

    /** File name to the logger the entries for it go through. */
    private static final ConcurrentMap<String, System.Logger> TOPICS = new ConcurrentHashMap<>();

    /**
     * Logger name to its {@code java.util.logging} counterpart.
     *
     * <p>Two jobs. It is the "this one is already configured" set, so a handler
     * is attached exactly once even if two file names derive the same name. And
     * it is a strong reference: {@code LogManager} keeps only a weak one, so a
     * topic logger that nothing else held could be collected and take its
     * handler - and the file it writes - with it.
     */
    private static final ConcurrentMap<String, java.util.logging.Logger> BACKING = new ConcurrentHashMap<>();

    /**
     * Creates the log directories, called once from {@code Start.main} and
     * again the first time each topic is used.
     *
     * <p>Nothing else created them and {@code Logs/} is not in the repo, so on a
     * fresh checkout every entry failed into the swallowed {@code IOException}
     * and every error log went nowhere, while the per-player {@code !monitor}
     * log in {@code MapleServerHandler} threw {@code FileNotFoundException} the
     * first time a cheat check armed it. {@code mkdirs} makes {@code Logs}
     * itself on the way.
     */
    public static void ensureLogDirs() {
        new File("Logs/MonitorLogs").mkdirs();
    }

    /** Files an informational entry - a boss kill, a state dump - under {@code file}. */
    public static void log(final String file, final String msg) {
        topic(file).log(System.Logger.Level.INFO, msg);
    }

    /**
     * Files {@code t} under {@code file}.
     *
     * <p>The {@code Throwable} is carried on the record rather than rendered
     * here, so the backend formats the trace - and so a later
     * {@link System.LoggerFinder} can do something better with it than a wall
     * of text.
     */
    public static void outputFileError(final String file, final Throwable t) {
        topic(file).log(System.Logger.Level.ERROR, "", t);
    }

    public static String CurrentReadable_Date() {
        return DATE.format(LocalDateTime.now());
    }

    public static String CurrentReadable_Time() {
        return TIME.format(LocalDateTime.now());
    }

    public static String getString(final Throwable e) {
        final StringWriter sw = new StringWriter();
        try (PrintWriter pw = new PrintWriter(sw)) {
            e.printStackTrace(pw);
        }
        return sw.toString();
    }

    /** The logger that writes {@code file}, created and wired on first use. */
    private static System.Logger topic(final String file) {
        return TOPICS.computeIfAbsent(file, f -> {
            final String name = loggerName(f);
            BACKING.computeIfAbsent(name, n -> {
                // A missing Logs/ is not fatal and never has been, but it is
                // worth one mkdirs before deciding the entry cannot be written.
                ensureLogDirs();
                final java.util.logging.Logger jul = java.util.logging.Logger.getLogger(n);
                final LogManager manager = LogManager.getLogManager();
                if (manager.getProperty(n + ".useParentHandlers") == null) {
                    jul.setUseParentHandlers(false);
                }
                if (jul.getLevel() == null) {
                    jul.setLevel(Level.ALL);
                }
                jul.addHandler(new AppendHandler(f));
                return jul;
            });
            return System.getLogger(name);
        });
    }

    /**
     * {@code Logs/Log_AccountStuck.rtf} to {@code tms.account.stuck}.
     *
     * <p>The named topics come from {@link #TOPIC_NAMES}; everything else is
     * derived, because {@code server.Timer} builds
     * {@code "Logs/Log_" + name + "_Except.rtf"} at runtime and no table here
     * can anticipate its pools without duplicating what {@code Timer} knows.
     * The derivation strips the directory, the {@code Log_} prefix and the
     * {@code .rtf} / {@code _Except} decoration, then splits what is left on
     * camel case: {@code Log_MapTimer_Except.rtf} becomes {@code tms.timer.map}.
     */
    private static String loggerName(final String file) {
        final String known = TOPIC_NAMES.get(file);
        if (known != null) {
            return known;
        }
        String s = file.substring(Math.max(file.lastIndexOf('/'), file.lastIndexOf('\\')) + 1);
        if (s.endsWith(".rtf")) {
            s = s.substring(0, s.length() - 4);
        }
        if (s.startsWith("Log_")) {
            s = s.substring(4);
        }
        if (s.endsWith("_Except")) {
            s = s.substring(0, s.length() - "_Except".length());
        }
        // Timer's nine pools are a family and should read as one, so that
        // tms.timer.level turns all of them off: MapTimer -> TimerMap.
        if (s.endsWith("Timer") && s.length() > "Timer".length()) {
            s = "Timer" + s.substring(0, s.length() - "Timer".length());
        }
        final StringBuilder sb = new StringBuilder("tms.");
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                if (Character.isUpperCase(c) && i > 0 && !Character.isUpperCase(s.charAt(i - 1))
                        && sb.charAt(sb.length() - 1) != '.') {
                    sb.append('.');
                }
                sb.append(Character.toLowerCase(c));
            } else if (sb.charAt(sb.length() - 1) != '.') {
                sb.append('.');
            }
        }
        while (sb.charAt(sb.length() - 1) == '.') {
            sb.setLength(sb.length() - 1);
        }
        return sb.length() <= "tms".length() ? "tms.log" : sb.toString();
    }

    /**
     * Appends one formatted entry to one file, opening and closing it each
     * time.
     *
     * <p>There is no lock here and none is needed: the entry arrives already
     * formatted and goes out in a single {@code write} to a stream opened in
     * append mode, which the platform makes atomic with respect to the end of
     * the file. That is also what lets something outside this JVM - an editor,
     * a {@code tail}, another process - hold the same file open without either
     * of them losing an entry.
     */
    private static final class AppendHandler extends Handler {

        private final String file;

        AppendHandler(final String file) {
            this.file = file;
            setLevel(Level.ALL);
            setFormatter(new EntryFormat());
        }

        @Override
        public void publish(final LogRecord record) {
            if (!isLoggable(record)) {
                return;
            }
            final String entry;
            try {
                entry = getFormatter().format(record);
            } catch (final RuntimeException e) {
                return; // A formatter that throws must not take the caller with it.
            }
            try (FileOutputStream out = new FileOutputStream(file, true)) {
                out.write(entry.getBytes(Encodings.NATIVE));
            } catch (final IOException | RuntimeException e) {
                // The logger is the last resort; there is nowhere left to
                // report to. A missing or unwritable Logs/ drops the entry,
                // which is what it has always done.
            }
        }

        /** Nothing is buffered - every entry is already on disk. */
        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }

    /**
     * One entry: a ruled header with the time the event happened, then the
     * message, then the stack trace if there is one.
     *
     * <p>{@code Encodings.NATIVE}, not UTF-8: these files are written for a
     * human to open later, and appending UTF-8 to a file a JDK 8 run wrote in
     * MS950 produces a mixture of two encodings in one file.
     */
    private static final class EntryFormat extends Formatter {

        @Override
        public String format(final LogRecord record) {
            final StringBuilder sb = new StringBuilder(256);
            sb.append('\n').append(RULE).append(' ')
                    .append(TIME.format(LocalDateTime.ofInstant(record.getInstant(), ZoneId.systemDefault())))
                    .append(' ').append(RULE).append('\n');
            // getMessage, not formatMessage: a body here is a stack trace or a
            // map dump, and must never be read as a MessageFormat pattern.
            final String msg = record.getMessage();
            final boolean hasMsg = msg != null && !msg.isEmpty();
            if (hasMsg) {
                sb.append(msg);
            }
            final Throwable thrown = record.getThrown();
            if (thrown != null) {
                if (hasMsg) {
                    sb.append('\n');
                }
                sb.append(getString(thrown));
            }
            return sb.toString();
        }
    }
}
