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

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.opennms.core.criteria.Criteria;
import org.opennms.core.criteria.CriteriaBuilder;
import org.opennms.netmgt.dao.api.MinionDao;
import org.opennms.netmgt.dao.api.NodeDao;
import org.opennms.netmgt.dao.api.OutageDao;
import org.opennms.netmgt.dao.api.ServiceTypeDao;
import org.opennms.netmgt.events.api.EventConstants;
import org.opennms.netmgt.events.api.annotations.EventHandler;
import org.opennms.netmgt.events.api.annotations.EventListener;
import org.opennms.netmgt.model.OnmsNode;
import org.opennms.netmgt.model.OnmsOutage;
import org.opennms.netmgt.model.minion.OnmsMinion;
import org.opennms.netmgt.xml.event.Event;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;

@EventListener(name="minionStatusTracker", logPrefix="minion")
public class MinionStatusTrackerImpl implements InitializingBean, MinionStatusTracker {
    private static final Logger LOG = LoggerFactory.getLogger(MinionStatusTrackerImpl.class);

    ScheduledExecutorService m_executor = Executors.newSingleThreadScheduledExecutor();

    static final String MINION_HEARTBEAT = "Minion-Heartbeat";
    static final String MINION_RPC = "Minion-RPC";

    @Autowired
    NodeDao m_nodeDao;

    @Autowired
    MinionDao m_minionDao;

    @Autowired
    ServiceTypeDao m_serviceTypeDao;

    @Autowired
    OutageDao m_outageDao;

    private long m_refreshRate = TimeUnit.MINUTES.toMillis(30);

    // by default, minions are updated every 30 seconds
    // TODO: make this configurable
    private long m_period = 2 * TimeUnit.SECONDS.toMillis(30);

    Map<Integer,OnmsMinion> m_minionNodes = new ConcurrentHashMap<>();
    Map<String,OnmsMinion> m_minions = new ConcurrentHashMap<>();
    Map<String,AggregateMinionStatus> m_state = new ConcurrentHashMap<>();

    @Override
    public void afterPropertiesSet() throws Exception {
        Assert.notNull(m_nodeDao);
        Assert.notNull(m_minionDao);
        Assert.notNull(m_serviceTypeDao);
        Assert.notNull(m_outageDao);
        m_executor.scheduleAtFixedRate(this::refresh, m_refreshRate, m_refreshRate, TimeUnit.MILLISECONDS);
    }

    public long getRefreshRate() {
        return m_refreshRate;
    }
    
    public void setRefreshRate(final long refresh) {
        m_refreshRate = refresh;
    }

    @EventHandler(uei=EventConstants.NODE_GAINED_SERVICE_EVENT_UEI)
    @Transactional
    public void onNodeGainedService(final Event e) {
        if (!MINION_HEARTBEAT.equals(e.getService()) && !MINION_RPC.equals(e.getService())) {
            return;
        }

        assertHasNodeId(e);

        final Integer nodeId = e.getNodeid().intValue();
        final OnmsMinion minion = getMinionForNodeId(nodeId);
        if (minion == null) {
            LOG.debug("No minion found for node ID {}", nodeId);
            return;
        }

        LOG.debug("Node gained a Minion service: {}", e);

        final String minionId = minion.getId();
        AggregateMinionStatus state = m_state.get(minionId);
        if (state == null) {
            LOG.debug("Found new Minion node: {}", minion);
            state = "down".equals(minion.getStatus())? AggregateMinionStatus.down() : AggregateMinionStatus.up();
        }
        
        if (MINION_HEARTBEAT.equals(e.getService())) {
            state = state.heartbeatUp(e.getTime());
        } else if (MINION_RPC.equals(e.getService())) {
            state = state.rpcUp(e.getTime());
        }
        updateState(minion, state);
    }

    @EventHandler(uei=EventConstants.NODE_LOST_SERVICE_EVENT_UEI)
    @Transactional
    public void onNodeLostService(final Event e) {
        if (!MINION_HEARTBEAT.equals(e.getService()) && !MINION_RPC.equals(e.getService())) {
            return;
        }

        assertHasNodeId(e);

        final Integer nodeId = e.getNodeid().intValue();
        final OnmsMinion minion = getMinionForNodeId(nodeId);
        if (minion == null) {
            LOG.debug("No minion found for node ID {}", nodeId);
            return;
        }

        LOG.debug("Node lost a Minion service: {}", e);

        final String minionId = minion.getId();
        AggregateMinionStatus state = m_state.get(minionId);
        if (state == null) {
            LOG.debug("Found new Minion node: {}", minion);
            state = "down".equals(minion.getStatus())? AggregateMinionStatus.down() : AggregateMinionStatus.up();
        }
        
        if (MINION_HEARTBEAT.equals(e.getService())) {
            state = state.heartbeatDown(e.getTime());
        } else if (MINION_RPC.equals(e.getService())) {
            state = state.rpcDown(e.getTime());
        }
        updateState(minion, state);
    }

    @EventHandler(uei=EventConstants.NODE_DELETED_EVENT_UEI)
    @Transactional
    public void onNodeDeleted(final Event e) {
        assertHasNodeId(e);
        final Integer nodeId = e.getNodeid().intValue();
        OnmsMinion minion = getMinionForNodeId(nodeId);
        m_minionNodes.remove(nodeId);
        if (minion != null) {
            updateState(minion, AggregateMinionStatus.down());
            m_minions.remove(minion.getId());
            m_state.remove(minion.getId());
        }
    }

    @EventHandler(ueis= {
            EventConstants.OUTAGE_CREATED_EVENT_UEI,
            EventConstants.OUTAGE_RESOLVED_EVENT_UEI
    })
    @Transactional
    public void onOutageEvent(final Event e) {
        if (!MINION_HEARTBEAT.equals(e.getService()) && !MINION_RPC.equals(e.getService())) {
            return;
        }

        assertHasNodeId(e);

        final OnmsMinion minion = getMinionForNodeId(e.getNodeid().intValue());
        final String minionId = minion.getId();
        final String minionLabel = minion.getLabel();

        AggregateMinionStatus status = m_state.get(minionId);
        if (status == null) {
            status = AggregateMinionStatus.down();
        }

        final String uei = e.getUei();
        if (MINION_HEARTBEAT.equalsIgnoreCase(e.getService())) {
            if (EventConstants.OUTAGE_CREATED_EVENT_UEI.equals(uei)) {
                status = status.heartbeatDown(e.getTime());
            } else if (EventConstants.OUTAGE_RESOLVED_EVENT_UEI.equals(uei)) {
                status = status.heartbeatUp(e.getTime());
            }
            final MinionStatus heartbeatStatus = status.getHeartbeatStatus();
            LOG.debug("{}({}) heartbeat is {} as of {}", minionLabel, minionId, heartbeatStatus.getState(), heartbeatStatus.lastSeen());
        } else if (MINION_RPC.equalsIgnoreCase(e.getService())) {
            if (EventConstants.OUTAGE_CREATED_EVENT_UEI.equals(uei)) {
                status = status.rpcDown(e.getTime());
            } else if (EventConstants.OUTAGE_RESOLVED_EVENT_UEI.equals(uei)) {
                status = status.rpcUp(e.getTime());
            }
            final MinionStatus rpcStatus = status.getRpcStatus();
            LOG.debug("{}({}) RPC is {} as of {}", minionLabel, minionId, rpcStatus.getState(), rpcStatus.lastSeen());
        }

        updateState(minion, status);
    }

    @Override
    @Transactional
    public void refresh() {
        LOG.info("Refreshing minion status from the outages database.");

        final Map<String,OnmsMinion> minions = new ConcurrentHashMap<>();
        final Map<Integer,OnmsMinion> minionNodes = new ConcurrentHashMap<>();
        final Map<String,AggregateMinionStatus> state = new ConcurrentHashMap<>();

        final List<OnmsMinion> dbMinions = m_minionDao.findAll();

        // populate the foreignId -> minion map
        LOG.debug("Populating minion state from the database: {}", dbMinions.stream().map(OnmsMinion::getId).collect(Collectors.toList()));
        final AggregateMinionStatus upStatus = AggregateMinionStatus.up();
        final AggregateMinionStatus downStatus = AggregateMinionStatus.down();
        dbMinions.forEach(minion -> {
            final String minionId = minion.getId();
            minions.put(minionId, minion);
            if ("down".equals(minion.getStatus())) {
                state.put(minionId, downStatus);
            } else {
                state.put(minionId, upStatus);
            }
        });

        // populate the nodeId -> minion map
        final Criteria c = new CriteriaBuilder(OnmsNode.class)
                .in("foreignId", minions.keySet())
                .distinct()
                .toCriteria();
        final List<OnmsNode> nodes = m_nodeDao.findMatching(c);
        LOG.debug("Mapping {} node IDs to minions: {}", nodes.size(), nodes.stream().map(OnmsNode::getId).collect(Collectors.toList()));
        nodes.forEach(node -> {
            final OnmsMinion m = minions.get(node.getForeignId());
            if (m.getLocation().equals(node.getLocation().getLocationName())) {
                minionNodes.put(node.getId(), m);
            }
        });

        final Integer heartbeatId = m_serviceTypeDao.findByName(MINION_HEARTBEAT).getId();
        final Integer rpcId = m_serviceTypeDao.findByName(MINION_RPC).getId();

        // populate the foreignId -> state map
        final Criteria outageCriteria = new CriteriaBuilder(OnmsOutage.class)
                .alias("monitoredService.serviceType", "serviceType")
                .alias("serviceType.name", "serviceName")
                .in("serviceName", Arrays.asList(MINION_HEARTBEAT, MINION_RPC))
                .distinct()
                .orderBy("ifLostService", true)
                .orderBy("ifRegainedService", true)
                .toCriteria();
        final List<OnmsOutage> outages = m_outageDao.findMatching(outageCriteria);

        // keep state for any minions without explicit outages, if there is any
        final List<String> minionsWithOutages = outages.stream().map(OnmsOutage::getForeignId).distinct().collect(Collectors.toList());
        final List<String> minionsWithoutOutages = state.keySet().stream().filter(id -> !minionsWithOutages.contains(id)).collect(Collectors.toList());
        LOG.debug("Attempting to preserve state for minions without explicit outage records: {}", minionsWithoutOutages);
        minionsWithoutOutages.forEach(minionId -> {
            if (m_state.containsKey(minionId)) {
                state.put(minionId, m_state.get(minionId));
            }
        });

        LOG.debug("Processing {} outage records.", outages.size());
        outages.forEach(outage -> {
            final String foreignId = outage.getForeignId();
            final String minionLabel = minions.get(foreignId).getLabel();
            final AggregateMinionStatus status = state.get(foreignId);
            if (outage.getIfRegainedService() != null) {
                if (heartbeatId == outage.getServiceId()) {
                    state.put(foreignId, status.heartbeatUp(outage.getIfRegainedService()));
                } else if (rpcId == outage.getServiceId()) {
                    state.put(foreignId, status.rpcUp(outage.getIfRegainedService()));
                } else {
                    LOG.warn("Unhandled 'up' outage record: {}", outage);
                }
                final MinionStatus heartbeatStatus = status.getHeartbeatStatus();
                LOG.debug("{}({}) heartbeat is {} as of {}", minionLabel, foreignId, heartbeatStatus.getState(), heartbeatStatus.lastSeen());
            } else {
                if (heartbeatId == outage.getServiceId()) {
                    state.put(foreignId, status.heartbeatDown(outage.getIfRegainedService()));
                } else if (rpcId == outage.getServiceId()) {
                    state.put(foreignId, status.rpcDown(outage.getIfRegainedService()));
                } else {
                    LOG.warn("Unhandled 'down' outage record: {}", outage);
                }
                final MinionStatus rpcStatus = status.getRpcStatus();
                LOG.debug("{}({}) RPC is {} as of {}", minionLabel, foreignId, rpcStatus.getState(), rpcStatus.lastSeen());
            }
        });

        m_state = state;
        m_minions = minions;
        m_minionNodes = minionNodes;

        LOG.info("Minion status updated from the outages database.  Next refresh in {} milliseconds.", m_refreshRate);
    }

    @Override
    public Collection<OnmsMinion> getMinions() {
        return m_minions.values();
    }

    @Override
    public MinionStatus getStatus(final String foreignId) {
        return m_state.get(foreignId);
    }

    @Override
    public MinionStatus getStatus(final OnmsMinion minion) {
        return m_state.get(minion.getId());
    }

    private void updateState(final OnmsMinion minion, final AggregateMinionStatus status) {
        minion.setStatus(status.isUp(m_period)? "up":"down");
        m_state.put(minion.getId(), status);
        m_minionDao.saveOrUpdate(minion);
    }

    private OnmsMinion getMinionForNodeId(final Integer nodeId) {
        if (m_minionNodes.containsKey(nodeId)) {
            return m_minionNodes.get(nodeId);
        }
        final OnmsNode node = m_nodeDao.get(nodeId);
        if (node == null) {
            final IllegalStateException ex = new IllegalStateException("Unable to retrieve minion. The node (ID: " + nodeId + ") does not exist!");
            LOG.warn(ex.getMessage());
            throw ex;
        }
        final String minionId = node.getForeignId();
        final OnmsMinion minion = m_minionDao.findById(minionId);
        m_minionNodes.put(nodeId, minion);
        m_minions.put(minionId, minion);
        return minion;
    }

    private void assertHasNodeId(final Event e) {
        if (e.getNodeid() == null || e.getNodeid() == 0) {
            final IllegalStateException ex = new IllegalStateException("Received a nodeGainedService event, but there is no node ID!");
            LOG.warn(ex.getMessage() + " {}", e, ex);
            throw ex;
        }
    }
}
