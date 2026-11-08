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
 * The accessor every packet handler gets, via
 * {@code handling.MapleServerHandler.messageReceived}.
 *
 * <p>Seeking and {@code getPosition} are {@link GenericLittleEndianAccessor}'s
 * own now - a cursor into a {@code byte[]} can always be moved - so all that is
 * left here is the one thing this subclass ever really changed: {@code skip}
 * jumps the cursor instead of reading its way forward, which means skipped
 * bytes do not count towards {@code getBytesRead()}. That counter has no caller
 * outside the tests, but the two forms are kept apart rather than quietly
 * merged.
 *
 * @author Frz
 * @version 1.0
 * @since Revision 323
 * @see GenericLittleEndianAccessor
 */
public final class GenericSeekableLittleEndianAccessor extends GenericLittleEndianAccessor implements SeekableLittleEndianAccessor {

    /**
     * Class constructor.
     *
     * @param bs The byte stream to wrap this around.
     */
    public GenericSeekableLittleEndianAccessor(final ByteArrayByteStream bs) {
        super(bs);
    }

    /**
     * Skip <code>num</code> number of bytes in the stream.
     *
     * @param num The number of bytes to skip.
     */
    @Override
    public void skip(final int num) {
        seek(getPosition() + num);
    }
}
