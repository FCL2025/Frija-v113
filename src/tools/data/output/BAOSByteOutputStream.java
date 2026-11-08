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
package tools.data.output;

import java.io.ByteArrayOutputStream;

/**
 * The growing byte[] a packet is built into.
 *
 * <p>This was the only implementation {@code ByteOutputStream} ever had: an
 * interface whose whole contract was "take one byte", wrapped around a
 * {@link ByteArrayOutputStream} that already does exactly that.
 * {@link GenericLittleEndianWriter} now holds the stream itself, so what is
 * left is the handle the round-trip test names.
 *
 * <p>The backing store stays a {@code ByteArrayOutputStream} on purpose: it
 * grows. A packet has no size known up front - the character list and the cash
 * shop catalogue run to tens of kilobytes - so a fixed-capacity buffer would
 * fail on exactly the packets that matter most.
 *
 * @author Frz
 * @version 1.0
 * @since Revision 352
 */
public final class BAOSByteOutputStream {

    /**
     * Read directly by the writers in this package.
     */
    final ByteArrayOutputStream baos;

    /**
     * Class constructor - wraps the stream around a Java BAOS.
     *
     * @param baos The <code>ByteArrayOutputStream</code> to wrap this around.
     */
    public BAOSByteOutputStream(final ByteArrayOutputStream baos) {
        this.baos = baos;
    }
}
