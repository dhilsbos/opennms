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

package org.opennms.core.health.shell;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import org.apache.karaf.shell.api.action.Action;
import org.apache.karaf.shell.api.action.Command;
import org.apache.karaf.shell.api.action.lifecycle.Reference;
import org.apache.karaf.shell.api.action.lifecycle.Service;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;

import com.codahale.metrics.ConsoleReporter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.MetricSet;

@Command(scope = "metrics", name = "display", description="Displays metrics from the available metric sets.")
@Service
public class MetricsCommand implements Action {

    private static final String NAME_PROP_KEY = "name";
    private static final String DESCRIPTION_PROP_KEY = "description";

    @Reference
    private BundleContext bundleContext;

    @Override
    public Object execute() throws Exception {
        final List<NamedMetricSet> metricSets = new ArrayList<>();

        // Gather the available metric sets from the service registry
        final Collection<ServiceReference<MetricSet>> metricSetRefs = bundleContext.getServiceReferences(MetricSet.class, null);
        for (ServiceReference<MetricSet> metricSetRef : metricSetRefs) {
            final String name = (String)metricSetRef.getProperty(NAME_PROP_KEY);
            final String description = (String)metricSetRef.getProperty(DESCRIPTION_PROP_KEY);
            final MetricSet metricSet = bundleContext.getService(metricSetRef);
            metricSets.add(new NamedMetricSet(metricSet, name, description));
        }

        if (metricSets.size() < 1) {
            System.out.println("(No metrics are currently available.)");
            return null;
        }

        // Sort them by name
        metricSets.sort(Comparator.comparing(NamedMetricSet::getName));

        boolean first = true;
        for (NamedMetricSet namedMetricSet : metricSets) {
            // Add some extract spacing between the report
            if (first) {
                first = false;
            } else {
                System.out.println("\n\n");
            }

            // Print a header
            System.out.printf("%s%s\n", namedMetricSet.getName(),
                    namedMetricSet.hasDescription() ? String.format(" (%s)",namedMetricSet.getDescription()) : "");
            // Add the metrics to a new registry and use the console reporter to display the results
            final MetricRegistry metricRegistry = namedMetricSet.toMetricRegistry();
            final ConsoleReporter consoleReporter = ConsoleReporter.forRegistry(metricRegistry)
                    .convertDurationsTo(TimeUnit.MILLISECONDS)
                    .convertRatesTo(TimeUnit.SECONDS)
                    .build();
            consoleReporter.report();
        }

        return null;
    }

    private static class NamedMetricSet {
        private final MetricSet metricSet;
        private final String name;
        private final String description;

        public NamedMetricSet(MetricSet metricSet, String name, String description) {
            this.metricSet = Objects.requireNonNull(metricSet);
            this.name = name;
            this.description = description;
        }

        public String getName() {
            return name;
        }

        public boolean hasDescription() {
            return description != null && description.length() > 0;
        }

        public String getDescription() {
            return description;
        }

        public MetricRegistry toMetricRegistry() {
            final MetricRegistry metricRegistry = new MetricRegistry();
            metricRegistry.registerAll(metricSet);
            return metricRegistry;
        }
    }
}
