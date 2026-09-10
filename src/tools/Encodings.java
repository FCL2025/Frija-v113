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

import java.nio.charset.Charset;

/**
 * The two charsets this server depends on, resolved once.
 *
 * Nothing here relies on Charset.defaultCharset(). Up to and including JDK 17
 * the default was the platform encoding, which on a zh-TW Windows box is MS950,
 * so every implicit-charset read and write in this server happened to agree with
 * the BIG5 data it was handling. JDK 18 made the default UTF-8 (JEP 400), which
 * silently changed both the bytes written to the log and *.sql files and - far
 * worse - the byte lengths the packet padding helpers in StringUtil compute. The
 * call sites that used to depend on the default now name a charset from here.
 */
public final class Encodings {

    /**
     * The client's encoding: every wire string, every script under Libs/scripts
     * and every player-visible name is BIG5.
     */
    public static final Charset BIG5 = Charset.forName("BIG5");

    /**
     * The platform's own encoding, i.e. what Charset.defaultCharset() returned
     * before JDK 18. Files this server writes for a human to open later - the
     * Logs/*.rtf files, the packet logs, the *.sql dumps - keep using it so that
     * appending to an existing file from a JDK 8 run does not produce a mixture
     * of two encodings.
     */
    public static final Charset NATIVE = resolveNative();

    private Encodings() {
    }

    private static Charset resolveNative() {
        // "native.encoding" is standard since JDK 17 and is unaffected by JEP 400.
        final String name = System.getProperty("native.encoding");
        if (name != null) {
            try {
                return Charset.forName(name);
            } catch (final Exception e) {
                // Unknown or unsupported name - fall through to BIG5.
            }
        }
        return BIG5;
    }
}
