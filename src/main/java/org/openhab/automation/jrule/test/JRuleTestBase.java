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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInstance;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openhab.automation.jrule.internal.JRuleConfig;
import org.openhab.automation.jrule.internal.engine.JRuleEngine;
import org.openhab.automation.jrule.internal.handler.JRuleEventHandler;
import org.openhab.automation.jrule.internal.handler.JRuleTimerHandler;
import org.openhab.automation.jrule.internal.module.JRuleRuleProvider;
import org.openhab.automation.jrule.internal.test.JRuleMockedEventBus;
import org.openhab.automation.jrule.items.JRuleItemRegistry;
import org.openhab.automation.jrule.rules.JRule;
import org.openhab.automation.jrule.rules.value.JRuleValue;
import org.openhab.core.events.Event;
import org.openhab.core.events.EventPublisher;
import org.openhab.core.items.GenericItem;
import org.openhab.core.items.Item;
import org.openhab.core.items.ItemNotFoundException;
import org.openhab.core.items.ItemRegistry;
import org.openhab.core.items.MetadataRegistry;
import org.openhab.core.items.events.ItemEventFactory;
import org.openhab.core.persistence.HistoricItem;
import org.openhab.core.persistence.extensions.PersistenceExtensions;
import org.openhab.core.types.State;

/**
 * Base class for unit testing JRule rules without a running OpenHAB instance.
 *
 * <p>
 * Extend this class in your test, annotate it with
 * {@code @TestInstance(TestInstance.Lifecycle.PER_CLASS)}, and use
 * {@link #initRule(Class)} to register a rule under test. Use
 * {@link #fireEvents(boolean, List)} to trigger item events and assert on
 * {@link #eventCollector} or the provided assertion helpers to verify
 * commands/updates sent by the rule.
 *
 * @author Arne Seime - Initial contribution (original JRuleAbstractTest)
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class JRuleTestBase<R extends JRule> {

    protected ItemRegistry itemRegistry;
    protected JRuleEventCollector eventCollector;
    protected R rule;

    private final JRuleMockedEventBus eventBus = new JRuleMockedEventBus();
    private MockedStatic<PersistenceExtensions> persistenceExtensions;

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

    @BeforeEach
    @SuppressWarnings("unchecked")
    protected void initTestRule() {
        java.lang.reflect.ParameterizedType type = (java.lang.reflect.ParameterizedType) getClass()
                .getGenericSuperclass();
        Class<R> ruleClass = (Class<R>) type.getActualTypeArguments()[0];
        rule = initRule(ruleClass);
    }

    @BeforeEach
    protected void resetBetweenTests() {
        JRuleTimerHandler.get().cancelAll();
        if (eventCollector != null) {
            eventCollector.clear();
        }
        if (persistenceExtensions == null) {
            persistenceExtensions = Mockito.mockStatic(PersistenceExtensions.class);
        }
    }

    @AfterEach
    protected void closePersistence() {
        if (persistenceExtensions != null && !persistenceExtensions.isClosed()) {
            persistenceExtensions.close();
            persistenceExtensions = null;
        }
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

    /** Fires a synchronous state-changed event for {@code itemName}. */
    protected void fireStateChanged(String itemName, State newState, State oldState) {
        fireEvents(false, List.of(ItemEventFactory.createStateChangedEvent(itemName, newState, oldState, null, null)));
    }

    /**
     * Stubs a {@link PersistenceExtensions} static call so that it returns a
     * {@link HistoricItem} wrapping {@code value} when called for {@code itemName}.
     *
     * @param verification a lambda that makes the exact static call to stub,
     *            e.g. {@code () -> PersistenceExtensions.previousState(any(), isNull())}
     */
    protected void mockPersistence(MockedStatic.Verification verification, String itemName, JRuleValue value) {
        persistenceExtensions.when(verification).thenAnswer(invocation -> {
            Item item = invocation.getArgument(0, Item.class);
            if (item.getName().equals(itemName)) {
                return new OhHistoricItem(value);
            }
            throw new IllegalStateException("not mocked: " + item.getName());
        });
    }

    /** Convenience stub for {@link PersistenceExtensions#previousState(Item, String)}. */
    protected void mockPreviousState(String itemName, JRuleValue value) {
        mockPersistence(() -> PersistenceExtensions.previousState(any(Item.class), isNull()), itemName, value);
    }

    private static class OhHistoricItem implements HistoricItem {
        private final JRuleValue value;

        OhHistoricItem(JRuleValue value) {
            this.value = value;
        }

        @Override
        public ZonedDateTime getTimestamp() {
            return ZonedDateTime.now();
        }

        @Override
        public State getState() {
            return value.toOhState();
        }

        @Override
        public String getName() {
            return "";
        }
    }

    /**
     * Registers an item in the mocked item registry with an initial state.
     * The item is also tracked by the event collector so that sendCommand and
     * postUpdate calls automatically update its state.
     * Call this in {@code @BeforeEach} for every item referenced by the rule.
     *
     * @param item the item to register
     * @param state the initial state
     * @throws ItemNotFoundException never thrown; declared for API compatibility
     */
    protected void registerItem(GenericItem item, State state) throws ItemNotFoundException {
        item.setState(state);
        when(itemRegistry.getItem(item.getName())).thenReturn(item);
        eventCollector.registerItem(item);
    }

    // -------------------------------------------------------------------------
    // Command assertions
    // -------------------------------------------------------------------------

    /**
     * Asserts that at least one {@code sendCommand} call for {@code itemName}
     * with {@code expectedCommand} has been recorded.
     */
    protected void assertCommandSent(String itemName, Object expectedCommand) {
        Assertions.assertTrue(eventCollector.hasCommandEvent(itemName, expectedCommand),
                "Expected command '" + expectedCommand + "' to be sent to item '" + itemName + "'");
    }

    /**
     * Asserts that no {@code sendCommand} was recorded for {@code itemName}.
     */
    protected void assertNoCommandSent(String itemName) {
        Assertions.assertTrue(eventCollector.getCommandEvents(itemName).isEmpty(),
                "Expected no commands to be sent to item '" + itemName + "', but "
                        + eventCollector.getCommandEvents(itemName).size() + " were recorded");
    }

    // -------------------------------------------------------------------------
    // Update assertions
    // -------------------------------------------------------------------------

    /**
     * Asserts that at least one {@code postUpdate} call for {@code itemName}
     * with {@code expectedState} has been recorded.
     */
    protected void assertUpdateSent(String itemName, Object expectedState) {
        Assertions.assertTrue(eventCollector.hasUpdateEvent(itemName, expectedState),
                "Expected update '" + expectedState + "' to be posted to item '" + itemName + "'");
    }

    // -------------------------------------------------------------------------
    // Item-state assertions
    // -------------------------------------------------------------------------

    /**
     * Asserts that the current state of {@code itemName} equals
     * {@code expectedState} (compared via {@code toString}).
     *
     * <p>
     * The state is tracked by the event collector and updated whenever the rule
     * calls {@code sendCommand} or {@code postUpdate} for the item — no
     * OpenHAB binding needed.
     */
    protected void assertItemHasState(String itemName, Object expectedState) {
        State actual = eventCollector.getItemState(itemName);
        Assertions.assertEquals(expectedState.toString(), actual.toString(), "Item '" + itemName + "' state mismatch");
    }

    // -------------------------------------------------------------------------
    // Timer helpers
    // -------------------------------------------------------------------------

    /**
     * Returns all currently scheduled timers with the given name.
     */
    protected List<JRuleTimerHandler.JRuleTimer> getTimers(String timerName) {
        return JRuleTimerHandler.get().getTimers(timerName);
    }

    /**
     * Immediately invokes the first timer registered under {@code timerName},
     * bypassing the configured delay. Use this in tests instead of waiting.
     *
     * @throws AssertionError if no timer with that name is currently scheduled
     */
    protected void invokeTimer(String timerName) {
        List<JRuleTimerHandler.JRuleTimer> timers = JRuleTimerHandler.get().getTimers(timerName);
        Assertions.assertFalse(timers.isEmpty(),
                "No timer found with name '" + timerName + "' — rule may not have created it");
        timers.get(0).invoke();
    }

    /**
     * Asserts that a timer with the given name is currently scheduled.
     */
    protected void assertTimerRunning(String timerName) {
        Assertions.assertFalse(JRuleTimerHandler.get().getTimers(timerName).isEmpty(),
                "Expected timer '" + timerName + "' to be running, but none was found");
    }

    /**
     * Asserts that no timer with the given name is currently scheduled.
     */
    protected void assertNoTimerRunning(String timerName) {
        Assertions.assertTrue(JRuleTimerHandler.get().getTimers(timerName).isEmpty(),
                "Expected no timer '" + timerName + "' to be running, but one was found");
    }

    // -------------------------------------------------------------------------
    // Update assertions (async)
    // -------------------------------------------------------------------------

    /**
     * Polls until a {@code postUpdate} for {@code itemName}/{@code expectedState}
     * appears, or fails after {@code timeout}.
     */
    protected void assertUpdateSentEventually(String itemName, Object expectedState, Duration timeout) {
        Awaitility.await().atMost(timeout.toMillis(), TimeUnit.MILLISECONDS).pollInterval(Duration.ofMillis(100))
                .untilAsserted(() -> assertUpdateSent(itemName, expectedState));
    }

    /** Like {@link #assertUpdateSentEventually(String, Object, Duration)} with a 3-second default timeout. */
    protected void assertUpdateSentEventually(String itemName, Object expectedState) {
        assertUpdateSentEventually(itemName, expectedState, Duration.ofSeconds(3));
    }

    // -------------------------------------------------------------------------
    // Rule-method assertions
    // -------------------------------------------------------------------------

    /**
     * Asserts that a rule method was called at least once.
     *
     * <p>
     * Pass a lambda that invokes the method under test on the provided rule
     * instance. Use {@link #anyArg()} as a stand-in for any argument:
     * 
     * <pre>{@code
     * assertRuleMethodCalled(r -> r.checkTemperature(anyArg()));
     * }</pre>
     */
    protected void assertRuleMethodCalled(Consumer<R> verification) {
        verification.accept(Mockito.verify(rule, Mockito.atLeastOnce()));
    }

    /** Asserts that a rule method was called exactly {@code times} times. */
    protected void assertRuleMethodCalled(Consumer<R> verification, int times) {
        verification.accept(Mockito.verify(rule, Mockito.times(times)));
    }

    /** Asserts that a rule method was never called. */
    protected void assertRuleMethodNeverCalled(Consumer<R> verification) {
        verification.accept(Mockito.verify(rule, Mockito.never()));
    }

    /**
     * Argument placeholder for use inside {@link #assertRuleMethodCalled} lambdas.
     * Matches any value of the inferred type, hiding the Mockito dependency.
     */
    @SuppressWarnings("unchecked")
    protected <T> T anyArg() {
        return (T) Mockito.any();
    }
}
