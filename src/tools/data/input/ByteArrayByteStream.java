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
package tools.data.input;

/**
 * The bytes of one inbound packet.
 *
 * <p>This used to be a full {@code SeekableInputStreamBytestream} - a cursor, a
 * read counter and a hex dump - sitting behind two interfaces that
 * {@link GenericLittleEndianAccessor} then pulled one byte at a time through.
 * There was only ever this one implementation, so the cursor moved into the
 * accessor and what is left is a name for "the bytes of one packet". It is kept
 * because the two call sites outside this package spell it out:
 * {@code handling.MapleServerHandler} and the round-trip test both say
 * {@code new GenericSeekableLittleEndianAccessor(new ByteArrayByteStream(b))}.
 *
 * @author Frz
 * @version 1.0
 * @since Revision 326
 */
public final class ByteArrayByteStream {

    /**
     * Read directly by the accessors in this package, and never copied: a
     * packet's bytes are handed over once and read once.
     */
    final byte[] arr;

    /**
     * Class constructor.
     *
     * @param arr Array of bytes to wrap the stream around.
     */
    public ByteArrayByteStream(final byte[] arr) {
        this.arr = arr;
    }
}
