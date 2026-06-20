/**
 * Copyright (c) 2010-2023 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.automation.jrule.test;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.openhab.core.events.Event;
import org.openhab.core.events.EventPublisher;
import org.openhab.core.items.events.ItemCommandEvent;
import org.openhab.core.items.events.ItemStateEvent;

/**
 * Collects all item events produced by rules (via sendCommand/postUpdate) so
 * tests can assert on what the rule sent to the event bus.
 *
 * <p>
 * An instance is available as {@link JRuleTestBase#eventCollector} after
 * calling {@link JRuleTestBase#initEngine()}.
 */
public class JRuleEventCollector implements EventPublisher {

    private final List<Container> events = new ArrayList<>();

    @Override
    public void post(Event event) throws IllegalArgumentException, IllegalStateException {
        events.add(new Container(ZonedDateTime.now(), event));
    }

    /**
     * Returns {@code true} if at least one command event for the given item
     * and command value has been collected.
     */
    public boolean hasCommandEvent(String itemName, Object command) {
        return events.stream().filter(e -> e.event instanceof ItemCommandEvent).map(e -> (ItemCommandEvent) e.event)
                .anyMatch(e -> e.getTopic().equals(commandTopic(itemName))
                        && e.getItemCommand().toString().equals(command.toString()));
    }

    /**
     * Returns all collected command events for the given item.
     */
    public List<Container> getCommandEvents(String itemName) {
        return events.stream().filter(e -> e.event instanceof ItemCommandEvent)
                .filter(e -> e.event.getTopic().equals(commandTopic(itemName))).collect(Collectors.toList());
    }

    /**
     * Counts the number of command events for the given item and command value.
     */
    public long countCommandEvent(String itemName, Object command) {
        return events.stream().filter(e -> e.event instanceof ItemCommandEvent).map(e -> (ItemCommandEvent) e.event)
                .filter(e -> e.getTopic().equals(commandTopic(itemName))
                        && e.getItemCommand().toString().equals(command.toString()))
                .count();
    }

    /**
     * Returns {@code true} if at least one state-update event for the given
     * item and state value has been collected.
     */
    public boolean hasUpdateEvent(String itemName, Object update) {
        return events.stream().filter(e -> e.event instanceof ItemStateEvent).map(e -> (ItemStateEvent) e.event)
                .anyMatch(e -> e.getTopic().equals(stateTopic(itemName))
                        && e.getItemState().toString().equals(update.toString()));
    }

    /**
     * Returns {@code true} if the most recent command event for the given item
     * matches the given command value.
     */
    public boolean isLastCommandEvent(String itemName, Object command) {
        return events.stream().filter(e -> e.event instanceof ItemCommandEvent)
                .filter(c -> c.event.getTopic().equals(commandTopic(itemName)))
                .reduce((a, b) -> a.time.isAfter(b.time) ? a : b)
                .filter(c -> ((ItemCommandEvent) c.event).getItemCommand().toString().equals(command.toString()))
                .isPresent();
    }

    /** Removes all collected events. Useful when asserting in multiple steps within one test. */
    public void clear() {
        events.clear();
    }

    private String commandTopic(String itemName) {
        return "openhab/items/" + itemName + "/command";
    }

    private String stateTopic(String itemName) {
        return "openhab/items/" + itemName + "/state";
    }

    /** Wraps a captured event with its collection timestamp. */
    public static final class Container {
        private final ZonedDateTime time;
        private final Event event;

        public Container(ZonedDateTime time, Event event) {
            this.time = time;
            this.event = event;
        }

        public ZonedDateTime getTime() {
            return time;
        }

        public Event getEvent() {
            return event;
        }
    }
}
