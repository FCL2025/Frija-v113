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

import java.util.logging.Formatter;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.LogRecord;
import java.util.logging.StreamHandler;

/**
 * The server's one logging mechanism, and the rules for using it.
 *
 * <h2>What to call</h2>
 *
 * Everything logs through {@link System.Logger}, the JDK's own facade. It needs
 * no jar, it is what a future Log4j2/Logback swap plugs into (one jar plus a
 * {@link System.LoggerFinder}), and with nothing else on the classpath it is
 * backed by {@code java.util.logging} - which is also where MINA ends up, since
 * {@code slf4j-jdk14} routes slf4j to JUL. So there is exactly one set of
 * handlers, configured in {@code Libs/logging.properties}
 * ({@code Launcher.bat} passes {@code -Djava.util.logging.config.file}).
 *
 * <h2>How a logger is named</h2>
 *
 * <b>Per class, for ordinary application logging.</b> One field, this exact
 * shape, so the sweep stays greppable:
 *
 * <pre>{@code
 *     private static final System.Logger LOG = System.getLogger(MapleMap.class.getName());
 * }</pre>
 *
 * The name is then the fully-qualified class name, so an operator can raise or
 * lower a whole package from the config file
 * ({@code handling.channel.handler.level = FINE}). Do not invent per-subsystem
 * names for this; the class name already says where the line came from, and
 * anything else has to be kept in step by hand.
 *
 * <p><b>Per topic, for the {@code Logs/*.rtf} files.</b> Those seven files are
 * not classes - 61 different classes write to {@code Log_Script_Except.rtf}
 * alone - they are destinations, so they get names of their own in a reserved
 * {@code tms.} namespace that no package in this tree can collide with
 * ({@code client}, {@code server}, {@code handling}, {@code scripting},
 * {@code provider}, {@code database}, {@code constants}, {@code tools}):
 *
 * <pre>
 *     Logs/Log_Zakum.rtf           tms.boss.zakum
 *     Logs/Log_Horntail.rtf        tms.boss.horntail
 *     Logs/Log_Pinkbean.rtf        tms.boss.pinkbean
 *     Logs/Log_Script_Except.rtf   tms.script
 *     Logs/Log_Packet_Except.rtf   tms.packet
 *     Logs/Log_AccountStuck.rtf    tms.account.stuck
 *     Logs/Log_AccountIP.rtf       tms.account.ip
 *     Logs/Log_Login_Error.rtf     tms.login
 *     Logs/Log_MapTimer_Except.rtf tms.timer.map   (server.Timer mints these)
 * </pre>
 *
 * {@link FileoutputUtil} owns that namespace and is the only thing that should
 * ever create a logger in it. The three boss files really are one family - all
 * three are written from the one {@code MapleMap.killMonster} branch set, with
 * the same {@code MapDebug_Log()} body - so {@code tms.boss.level = OFF} turns
 * off boss bookkeeping without touching the exception logs, which is the split
 * an operator actually wants.
 *
 * <p>So: <b>both</b>. Per class is the default; {@code tms.*} is a closed list
 * that exists because a file, not a class, is the thing being addressed.
 *
 * <h2>Which level</h2>
 *
 * The tree has four ways of reporting things today. They map like this:
 *
 * <pre>
 *   System.out.println(...)            INFO     progress and state an operator reads
 *   System.err.println(...)            WARNING  something is wrong, the server carries on
 *   System.err.println(...) + giving   ERROR    ... and the operation was abandoned
 *       up on the operation
 *   t.printStackTrace()                ERROR    always with the Throwable passed in,
 *                                               never printed by the call site:
 *                                               LOG.log(ERROR, "what failed", t);
 *   FileoutputUtil.log(...)            INFO     on the topic logger
 *   FileoutputUtil.outputFileError(..) ERROR    on the topic logger
 *   tms.Debug packet hex dumps         DEBUG    off unless a level is lowered
 * </pre>
 *
 * A {@code catch} that deliberately printed only {@code e.toString()} stays
 * terse - pass the message, not the {@code Throwable}. Attaching the
 * {@code Throwable} is what turns a one-line report into a full stack trace.
 *
 * <p>{@code System.Logger} levels reach JUL renamed, which is what the config
 * file and the console prefix show: {@code TRACE}&rarr;{@code FINER},
 * {@code DEBUG}&rarr;{@code FINE}, {@code INFO}&rarr;{@code INFO},
 * {@code WARNING}&rarr;{@code WARNING}, {@code ERROR}&rarr;{@code SEVERE}.
 *
 * <p>One event is one record. A multi-line report is one {@code log} call whose
 * message contains {@link System#lineSeparator()} - not one call per line, or
 * the level prefix repeats down the block and two threads can interleave into
 * the middle of it.
 *
 * <h2>What must not be converted</h2>
 *
 * <ul>
 * <li><b>The three packet logs are not application logging.</b> The 10k
 * in-memory ring in {@code MapleServerHandler} flushed by its MBean's
 * {@code writeLog}, {@code !monitor <player>} writing
 * {@code Logs/MonitorLogs/<name>_log.txt}, and the per-IP monitor writing
 * {@code Logs/PacketLog_<ip>.txt} are forensic captures with their own
 * lifetimes, their own file names and their own exclusion list - the
 * password-bearing opcodes in {@code MapleServerHandler.sensitive}, which stay
 * excluded from all three. Routing them through a logger would put them behind
 * a level, mix them with everything else, and invite someone to widen them.
 * Leave all three exactly as they are.</li>
 *
 * <li><b>{@code tools/wztosql/*}.</b> Those are standalone {@code main}s run
 * from {@code Launcher.bat cashshop}; their {@code System.out} is their user
 * interface, not a log.</li>
 *
 * <li><b>{@code server.Start}'s startup banner stays on the console.</b> It is
 * converted (it is the pilot) but it stays {@code INFO} and it stays
 * unadorned, because an operator reads it while waiting for the server to come
 * up. That is why {@link Console} writes to {@code System.out} with no
 * timestamp and no prefix below {@code WARNING}.</li>
 *
 * <li><b>Anything on a MINA I/O thread, and any last-resort {@code catch},
 * must stay non-throwing.</b> Logging from there is fine - {@code System.Logger}
 * does not throw - but do not add work that can: no string building that can
 * dereference a half-disconnected player, no database read to decorate a
 * message.</li>
 * </ul>
 *
 * @see FileoutputUtil
 */
public final class Logging {

    private Logging() {
    }

    /**
     * The console handler, named from {@code Libs/logging.properties}.
     *
     * <p>It is not {@code java.util.logging.ConsoleHandler} for two reasons.
     *
     * <p><b>Encoding.</b> A handler's charset is the settable property
     * {@code <handler class>.encoding}, but a properties file cannot say
     * "whatever {@code native.encoding} is" - and this console is MS950, while
     * {@code Charset.defaultCharset()} has been UTF-8 since JDK 18 (JEP 400).
     * Hard-coding {@code MS950} in the config file would be wrong on the next
     * machine, so the charset is taken from {@link Encodings#NATIVE} in code
     * and the property is left as an override. (JDK 19 and later happen to
     * default {@code ConsoleHandler} to {@code stderr.encoding}, which is
     * right here by accident; {@code FileHandler} gets no such treatment, and
     * CLAUDE.md's rule is to name the charset rather than inherit one.)
     *
     * <p><b>Stream.</b> It writes to {@code System.out}, not {@code System.err}.
     * {@code ConsoleHandler}'s stderr is a desktop convention; this is a server
     * console where an operator wants one ordered stream, and during the sweep
     * it also keeps converted lines in order with the hundreds of
     * {@code System.out.println} calls that have not been converted yet.
     * {@code StreamHandler} is extended rather than {@code ConsoleHandler}
     * because {@code setOutputStream} closes the stream it replaces, so a
     * {@code ConsoleHandler} redirected to stdout would close {@code System.err}
     * on the way.
     *
     * <p>Configurable as {@code tools.Logging$Console.level} /
     * {@code .formatter} / {@code .encoding} / {@code .filter}; anything set
     * there wins over the defaults applied here.
     */
    public static final class Console extends StreamHandler {

        public Console() {
            final LogManager manager = LogManager.getLogManager();
            final String cname = Console.class.getName();
            if (manager.getProperty(cname + ".level") == null) {
                // StreamHandler defaults a handler to INFO; let the loggers decide.
                setLevel(Level.ALL);
            }
            if (manager.getProperty(cname + ".formatter") == null) {
                setFormatter(new ConsoleFormat());
            }
            if (manager.getProperty(cname + ".encoding") == null) {
                try {
                    setEncoding(Encodings.NATIVE.name());
                } catch (final Exception e) {
                    // A charset that exists cannot be unsupported; if the JDK
                    // ever disagrees, a default-encoded console beats none.
                }
            }
            // Last: the writer is built here, with the encoding set above.
            setOutputStream(System.out);
        }

        /** {@code StreamHandler} buffers; a server console has to be live. */
        @Override
        public synchronized void publish(final LogRecord record) {
            super.publish(record);
            flush();
        }

        /** Flush, never close - {@code System.out} outlives this handler. */
        @Override
        public synchronized void close() {
            flush();
        }
    }

    /**
     * The console layout: the message, and nothing else until something is
     * wrong.
     *
     * <p>{@code SimpleFormatter} prints two lines per record with a timestamp
     * and the source class, which would bury the startup banner it took a
     * decade to make readable. Below {@code WARNING} a record prints exactly
     * the string that was logged, so
     * {@code System.out.println("[啟動] …")} and
     * {@code LOG.log(INFO, "[啟動] …")} put the same bytes on the console;
     * at {@code WARNING} and above it is prefixed with the level name and any
     * {@code Throwable} is appended in full, because that is the line an
     * operator has to be able to find.
     *
     * <p>For a timestamped console instead, put this in
     * {@code Libs/logging.properties}:
     *
     * <pre>
     *     tools.Logging$Console.formatter = java.util.logging.SimpleFormatter
     *     java.util.logging.SimpleFormatter.format = %1$tF %1$tT %4$s %5$s%6$s%n
     * </pre>
     */
    public static final class ConsoleFormat extends Formatter {

        @Override
        public String format(final LogRecord record) {
            final StringBuilder sb = new StringBuilder(128);
            if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                sb.append('[').append(record.getLevel().getName()).append("] ");
            }
            sb.append(formatMessage(record));
            final Throwable thrown = record.getThrown();
            if (thrown != null) {
                // getString already ends with a line separator.
                sb.append(System.lineSeparator()).append(FileoutputUtil.getString(thrown));
                return sb.toString();
            }
            sb.append(System.lineSeparator());
            return sb.toString();
        }
    }
}
