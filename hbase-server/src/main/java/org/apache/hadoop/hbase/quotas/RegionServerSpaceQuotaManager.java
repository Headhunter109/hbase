/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hbase.quotas;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Map.Entry;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.classification.InterfaceAudience;
import org.apache.hadoop.hbase.client.Connection;
import org.apache.hadoop.hbase.quotas.SpaceQuotaSnapshot.SpaceQuotaStatus;
import org.apache.hadoop.hbase.regionserver.RegionServerServices;

import com.google.common.annotations.VisibleForTesting;

/**
 * A manager for filesystem space quotas in the RegionServer.
 *
 * This class is the centralized point for what a RegionServer knows about space quotas
 * on tables. For each table, it tracks two different things: the {@link SpaceQuotaSnapshot}
 * and a {@link SpaceViolationPolicyEnforcement} (which may be null when a quota is not
 * being violated). Both of these are sensitive on when they were last updated. The
 * {link SpaceQutoaViolationPolicyRefresherChore} periodically runs and updates
 * the state on <code>this</code>.
 */
@InterfaceAudience.Private
public class RegionServerSpaceQuotaManager {
  private static final Log LOG = LogFactory.getLog(RegionServerSpaceQuotaManager.class);

  private final RegionServerServices rsServices;

  private SpaceQuotaRefresherChore spaceQuotaRefresher;
  private AtomicReference<Map<TableName, SpaceQuotaSnapshot>> currentQuotaSnapshots;
  private boolean started = false;
  private ConcurrentHashMap<TableName,SpaceViolationPolicyEnforcement> enforcedPolicies;
  private SpaceViolationPolicyEnforcementFactory factory;

  public RegionServerSpaceQuotaManager(RegionServerServices rsServices) {
    this(rsServices, SpaceViolationPolicyEnforcementFactory.getInstance());
  }

  @VisibleForTesting
  RegionServerSpaceQuotaManager(
      RegionServerServices rsServices, SpaceViolationPolicyEnforcementFactory factory) {
    this.rsServices = Objects.requireNonNull(rsServices);
    this.factory = factory;
    this.enforcedPolicies = new ConcurrentHashMap<>();
    this.currentQuotaSnapshots = new AtomicReference<>(new HashMap<>());
  }

  public synchronized void start() throws IOException {
    if (!QuotaUtil.isQuotaEnabled(rsServices.getConfiguration())) {
      LOG.info("Quota support disabled, not starting space quota manager.");
      return;
    }

    if (started) {
      LOG.warn("RegionServerSpaceQuotaManager has already been started!");
      return;
    }
    this.spaceQuotaRefresher = new SpaceQuotaRefresherChore(this, rsServices.getClusterConnection());
    rsServices.getChoreService().scheduleChore(spaceQuotaRefresher);
    started = true;
  }

  public synchronized void stop() {
    if (null != spaceQuotaRefresher) {
      spaceQuotaRefresher.cancel();
      spaceQuotaRefresher = null;
    }
    started = false;
  }

  /**
   * @return if the {@code Chore} has been started.
   */
  public boolean isStarted() {
    return started;
  }

  /**
   * Copies the last {@link SpaceQuotaSnapshot}s that were recorded. The current view
   * of what the RegionServer thinks the table's utilization is.
   */
  public Map<TableName,SpaceQuotaSnapshot> copyQuotaSnapshots() {
    return new HashMap<>(currentQuotaSnapshots.get());
  }

  /**
   * Updates the current {@link SpaceQuotaSnapshot}s for the RegionServer.
   *
   * @param newSnapshots The space quota snapshots.
   */
  public void updateQuotaSnapshot(Map<TableName,SpaceQuotaSnapshot> newSnapshots) {
    currentQuotaSnapshots.set(Objects.requireNonNull(newSnapshots));
  }

  /**
   * Creates an object well-suited for the RegionServer to use in verifying active policies.
   */
  public ActivePolicyEnforcement getActiveEnforcements() {
    return new ActivePolicyEnforcement(copyActiveEnforcements(), copyQuotaSnapshots(), rsServices);
  }

  /**
   * Converts a map of table to {@link SpaceViolationPolicyEnforcement}s into
   * {@link SpaceViolationPolicy}s.
   */
  public Map<TableName, SpaceQuotaSnapshot> getActivePoliciesAsMap() {
    final Map<TableName, SpaceViolationPolicyEnforcement> enforcements =
        copyActiveEnforcements();
    final Map<TableName, SpaceQuotaSnapshot> policies = new HashMap<>();
    for (Entry<TableName, SpaceViolationPolicyEnforcement> entry : enforcements.entrySet()) {
      final SpaceQuotaSnapshot snapshot = entry.getValue().getQuotaSnapshot();
      if (null != snapshot) {
        policies.put(entry.getKey(), snapshot);
      }
    }
    return policies;
  }

  /**
   * Enforces the given violationPolicy on the given table in this RegionServer.
   */
  public void enforceViolationPolicy(TableName tableName, SpaceQuotaSnapshot snapshot) {
    SpaceQuotaStatus status = snapshot.getQuotaStatus();
    if (!status.isInViolation()) {
      throw new IllegalStateException(
          tableName + " is not in violation. Violation policy should not be enabled.");
    }
    if (LOG.isTraceEnabled()) {
      LOG.trace(
          "Enabling violation policy enforcement on " + tableName
          + " with policy " + status.getPolicy());
    }
    // Construct this outside of the lock
    final SpaceViolationPolicyEnforcement enforcement = getFactory().create(
        getRegionServerServices(), tableName, snapshot);
    // "Enables" the policy
    // TODO Should this synchronize on the actual table name instead of the map? That would allow
    // policy enable/disable on different tables to happen concurrently. As written now, only one
    // table will be allowed to transition at a time.
    synchronized (enforcedPolicies) {
      try {
        enforcement.enable();
      } catch (IOException e) {
        LOG.error("Failed to enable space violation policy for " + tableName
            + ". This table will not enter violation.", e);
        return;
      }
      enforcedPolicies.put(tableName, enforcement);
    }
  }

  /**
   * Disables enforcement on any violation policy on the given <code>tableName</code>.
   */
  public void disableViolationPolicyEnforcement(TableName tableName) {
    if (LOG.isTraceEnabled()) {
      LOG.trace("Disabling violation policy enforcement on " + tableName);
    }
    // "Disables" the policy
    // TODO Should this synchronize on the actual table name instead of the map?
    synchronized (enforcedPolicies) {
      SpaceViolationPolicyEnforcement enforcement = enforcedPolicies.remove(tableName);
      if (null != enforcement) {
        try {
          enforcement.disable();
        } catch (IOException e) {
          LOG.error("Failed to disable space violation policy for " + tableName
              + ". This table will remain in violation.", e);
          enforcedPolicies.put(tableName, enforcement);
        }
      }
    }
  }

  /**
   * Returns whether or not compactions should be disabled for the given <code>tableName</code> per
   * a space quota violation policy. A convenience method.
   *
   * @param tableName The table to check
   * @return True if compactions should be disabled for the table, false otherwise.
   */
  public boolean areCompactionsDisabled(TableName tableName) {
    SpaceViolationPolicyEnforcement enforcement = this.enforcedPolicies.get(Objects.requireNonNull(tableName));
    if (null != enforcement) {
      return enforcement.areCompactionsDisabled();
    }
    return false;
  }

  /**
   * Returns the collection of tables which have quota violation policies enforced on
   * this RegionServer.
   */
  Map<TableName,SpaceViolationPolicyEnforcement> copyActiveEnforcements() {
    // Allows reads to happen concurrently (or while the map is being updated)
    return new HashMap<>(this.enforcedPolicies);
  }

  RegionServerServices getRegionServerServices() {
    return rsServices;
  }

  Connection getConnection() {
    return rsServices.getConnection();
  }

  SpaceViolationPolicyEnforcementFactory getFactory() {
    return factory;
  }
}
