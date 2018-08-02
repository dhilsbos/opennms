/*******************************************************************************
 * This file is part of OpenNMS(R).
 *
 * Copyright (C) 2018 The OpenNMS Group, Inc.
 * OpenNMS(R) is Copyright (C) 1999-2018 The OpenNMS Group, Inc.
 *
 * OpenNMS(R) is a registered trademark of The OpenNMS Group, Inc.
 *
 * OpenNMS(R) is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License,
 * or (at your option) any later version.
 *
 * OpenNMS(R) is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with OpenNMS(R).  If not, see:
 *      http://www.gnu.org/licenses/
 *
 * For more information contact:
 *     OpenNMS(R) Licensing <license@opennms.org>
 *     http://www.opennms.org/
 *     http://www.opennms.com/
 *******************************************************************************/

package org.opennms.minion.status;

import java.util.Collection;

import org.opennms.netmgt.model.minion.OnmsMinion;

public interface MinionStatusTracker {
    /** refresh all minion state from the database */
    public void refresh();

    /** get all minions currently being tracked */
    public Collection<OnmsMinion> getMinions();

    /**
     * Given a foreign ID, retrieve the status associated with it.
     * @param foreignId the foreign id
     * @return a {@link MinionStatus}
     */
    public MinionStatus getStatus(final String foreignId);

    /**
     * Given a minion, retrieve the status associated with it.
     * @param minion the minion
     * @return a {@link MinionStatus}
     */
    public MinionStatus getStatus(final OnmsMinion minion);
}
