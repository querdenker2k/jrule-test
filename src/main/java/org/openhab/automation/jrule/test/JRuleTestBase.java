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

import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.mockito.Mockito;
import org.openhab.automation.jrule.internal.JRuleConfig;
import org.openhab.automation.jrule.internal.engine.JRuleEngine;
import org.openhab.automation.jrule.internal.handler.JRuleEventHandler;
import org.openhab.automation.jrule.internal.module.JRuleRuleProvider;
import org.openhab.automation.jrule.internal.test.JRuleMockedEventBus;
import org.openhab.automation.jrule.items.JRuleItemRegistry;
import org.openhab.automation.jrule.rules.JRule;
import org.openhab.core.events.Event;
import org.openhab.core.events.EventPublisher;
import org.openhab.core.items.GenericItem;
import org.openhab.core.items.ItemNotFoundException;
import org.openhab.core.items.ItemRegistry;
import org.openhab.core.items.MetadataRegistry;
import org.openhab.core.types.State;

/**
 * Base class for unit testing JRule rules without a running OpenHAB instance.
 *
 * <p>
 * Extend this class in your test, annotate it with
 * {@code @TestInstance(TestInstance.Lifecycle.PER_CLASS)}, and use
 * {@link #initRule(Class)} to register a rule under test. Use
 * {@link #fireEvents(boolean, List)} to trigger item events and assert on
 * {@link #eventCollector} to verify commands/updates sent by the rule.
 *
 * @author Arne Seime - Initial contribution (original JRuleAbstractTest)
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class JRuleTestBase {

    protected ItemRegistry itemRegistry;
    protected JRuleEventCollector eventCollector;

    private final JRuleMockedEventBus eventBus = new JRuleMockedEventBus();

    @AfterAll
    protected void shutdown() {
        eventBus.stop();
    }

    @BeforeAll
    protected void initEngine() {
        Map<String, Object> properties = new HashMap<>();
        properties.put("org.openhab.automation.jrule.engine.executors.enable", "false");
        JRuleConfig config = new JRuleConfig(properties);
        config.initConfig();

        JRuleEngine engine = JRuleEngine.get();
        engine.setConfig(config);

        itemRegistry = Mockito.mock(ItemRegistry.class);
        JRuleEventHandler.setItemRegistry(itemRegistry);
        JRuleEngine.get().setItemRegistry(itemRegistry);

        JRuleRuleProvider ruleProvider = new JRuleRuleProvider();
        ruleProvider.setEventPublisher(Mockito.mock(EventPublisher.class));
        JRuleEngine.get().setRuleProvider(ruleProvider);

        JRuleItemRegistry.setMetadataRegistry(Mockito.mock(MetadataRegistry.class));

        eventCollector = new JRuleEventCollector();
        JRuleEventHandler.setEventPublisher(eventCollector);

        eventBus.start();
    }

    /**
     * Instantiates the given rule class as a Mockito spy and registers it with
     * the JRuleEngine. The engine is reset before registration so each call
     * starts with a clean rule set.
     *
     * @param <T> the rule type
     * @param rule the rule class to instantiate and register
     * @return a Mockito spy of the rule (allows {@code verify(rule, times(n))})
     */
    protected <T extends JRule> T initRule(Class<T> rule) {
        T spyRule = Mockito.spy(rule);
        JRuleEngine.get().reset();
        JRuleEngine.get().add(spyRule, true);
        return spyRule;
    }

    /**
     * Fires a list of OpenHAB events through the mocked event bus, triggering
     * any matching rule methods.
     *
     * @param async if {@code true} each event is dispatched on a separate thread
     * @param events the events to fire
     */
    protected void fireEvents(boolean async, List<Event> events) {
        eventBus.fire(async, events);
    }

    /**
     * Registers an item in the mocked item registry with an initial state.
     * Call this in {@code @BeforeEach} for every item referenced by the rule
     * under test.
     *
     * @param item the item to register
     * @param state the initial state
     * @throws ItemNotFoundException never thrown; declared for API compatibility
     */
    protected void registerItem(GenericItem item, State state) throws ItemNotFoundException {
        item.setState(state);
        when(itemRegistry.getItem(item.getName())).thenReturn(item);
    }
}
