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

import java.io.StringWriter;
import java.io.PrintWriter;
import java.io.File;
import java.io.IOException;
import java.io.FileOutputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Append-only logging to the {@code Logs/*.rtf} files, from any thread.
 *
 * <p>Two things in here were not safe to call concurrently, and everything
 * calls them concurrently - every swallowed packet exception, every script
 * error, every timer task that throws.
 *
 * <p>The timestamps were formatted through two shared static
 * {@code SimpleDateFormat} instances. {@code SimpleDateFormat} keeps parse
 * state in a field, so two threads formatting at once corrupt each other:
 * usually a mangled date, sometimes an {@code ArrayIndexOutOfBoundsException}
 * or a {@code NumberFormatException} thrown from inside {@code Calendar} - out
 * of the error logger, on the error path. {@link DateTimeFormatter} is
 * immutable and has no such state.
 *
 * <p>And each entry was written to the file in two calls, a header and then the
 * body. Appends are atomic per write, so two threads logging at the same moment
 * could interleave into "header, header, body, body" and leave a stack trace
 * filed under the wrong timestamp. Each entry is now one write.
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

    /**
     * Creates the log directories, called once from {@code Start.main}.
     *
     * <p>Nothing else created them and {@code Logs/} is not in the repo, so on a
     * fresh checkout every {@link #append} failed into the swallowed
     * {@code IOException} and every error log went nowhere, while the per-player
     * {@code !monitor} log in {@code MapleServerHandler} threw
     * {@code FileNotFoundException} the first time a cheat check armed it.
     * {@code mkdirs} makes {@code Logs} itself on the way.
     */
    public static void ensureLogDirs() {
        new File("Logs/MonitorLogs").mkdirs();
    }

    public static void log(final String file, final String msg) {
        append(file, msg);
    }

    public static void outputFileError(final String file, final Throwable t) {
        append(file, getString(t));
    }

    /** One entry, one header, one write. */
    private static void append(final String file, final String body) {
        final String entry = "\n------------------------ " + CurrentReadable_Time()
                + " ------------------------\n" + body;
        try (FileOutputStream out = new FileOutputStream(file, true)) {
            out.write(entry.getBytes(Encodings.NATIVE));
        } catch (final IOException ess) {
            // The logger is the last resort; there is nowhere left to report to.
        }
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
}
