/**

Copyright (C) SYSTAP, LLC DBA Blazegraph 2006-2016.  All rights reserved.

Contact:
     SYSTAP, LLC DBA Blazegraph
     2501 Calvert ST NW #106
     Washington, DC 20008
     licenses@blazegraph.com

This program is free software; you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation; version 2 of the License.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program; if not, write to the Free Software
Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
*/
package com.bigdata.ha.msg;

import java.util.UUID;

import com.bigdata.rawstore.IRawStore;

/**
 * Message used to communicate the digest of a backing store.
 * 
 * @author <a href="mailto:thompsonbry@users.sourceforge.net">Bryan Thompson</a>
 */
public interface IHADigestResponse extends IHAMessage {

    /**
     * The {@link UUID} identifying the {@link IRawStore} for which the record
     * was requested (optional, defaults to the current Journal).
     * <p>
     * Note: This parameter is intended for scale-out if there is a need to
     * fetch the root block of a historical journal (versus the live journal).
     */
    UUID getStoreUUID();

    /**
     * The computed disgest.
     */
    byte[] getDigest();

}
