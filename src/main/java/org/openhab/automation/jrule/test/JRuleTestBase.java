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
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import javax.measure.Unit;

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
import org.openhab.automation.jrule.items.JRuleItemDefinition;
import org.openhab.automation.jrule.items.JRuleItemRegistry;
import org.openhab.automation.jrule.persistence.JRuleHistoricState;
import org.openhab.automation.jrule.rules.JRule;
import org.openhab.automation.jrule.rules.value.JRuleValue;
import org.openhab.core.events.Event;
import org.openhab.core.events.EventPublisher;
import org.openhab.core.i18n.UnitProvider;
import org.openhab.core.items.GenericItem;
import org.openhab.core.items.GroupItem;
import org.openhab.core.items.Item;
import org.openhab.core.items.ItemNotFoundException;
import org.openhab.core.items.ItemRegistry;
import org.openhab.core.items.Metadata;
import org.openhab.core.items.MetadataKey;
import org.openhab.core.items.MetadataRegistry;
import org.openhab.core.items.events.ItemEventFactory;
import org.openhab.core.library.CoreItemFactory;
import org.openhab.core.persistence.HistoricItem;
import org.openhab.core.persistence.extensions.PersistenceExtensions;
import org.openhab.core.scheduler.CronScheduler;
import org.openhab.core.scheduler.ScheduledCompletableFuture;
import org.openhab.core.types.Command;
import org.openhab.core.types.State;
import org.openhab.core.types.UnDefType;

/**
 * Base class for unit testing JRule rules without a running OpenHAB instance.
 *
 * <p>
 * Extend this class in your test, annotate it with
 * {@code @TestInstance(TestInstance.Lifecycle.PER_CLASS)}, and implement
 * {@link #registerAllItems()} to call {@link #registerAllItems(Class)} with
 * the generated {@code JRuleItemNames} class. Fire item events via
 * {@link #fireChange}, {@link #fireCommand}, or {@link #fireUpdate} and assert
 * on {@link #eventCollector} or the provided assertion helpers.
 *
 * @param <R> the rule class under test
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class JRuleTestBase<R extends JRule> {

    protected ItemRegistry itemRegistry;
    protected JRuleEventCollector eventCollector;
    protected R rule;

    private final Map<String, GenericItem> trackedItems = new LinkedHashMap<>();
    private final JRuleMockedEventBus eventBus = new JRuleMockedEventBus();
    protected MockedStatic<PersistenceExtensions> persistenceExtensions;

    /**
     * Backing store for the fake {@link MetadataRegistry} installed by
     * {@link #installMetadataRegistry()}. {@code JRuleItemRegistry} caches
     * {@code JRuleItem} wrappers for the lifetime of the JVM, each permanently
     * bound to whichever {@link MetadataRegistry} instance was active the first
     * time that item was resolved - across potentially many test classes sharing
     * one Surefire fork. So instead of swapping in a fresh mock per test class
     * (which would only ever affect items nobody has touched yet), a single fake
     * registry backed by this mutable list is installed once, ever, and kept for
     * the rest of the run. Use {@link #setItemMetadata} / {@link #clearAllMetadata}
     * to control it from test code.
     */
    private static final List<Metadata> METADATA_STORE = new CopyOnWriteArrayList<>();
    private static volatile boolean metadataRegistryInstalled = false;

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    @AfterAll
    protected void shutdown() {
        eventBus.stop();
    }

    @BeforeAll
    @SuppressWarnings({ "unchecked", "rawtypes" })
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

        installMetadataRegistry();

        eventCollector = new JRuleEventCollector();
        JRuleEventHandler.setEventPublisher(eventCollector);

        // Mock CronScheduler so rules with @JRuleWhenCronTrigger can be loaded
        // without an OSGi runtime.
        CronScheduler cronScheduler = Mockito.mock(CronScheduler.class);
        ScheduledCompletableFuture mockFuture = Mockito.mock(ScheduledCompletableFuture.class);
        Mockito.when(cronScheduler.schedule(Mockito.any(), Mockito.anyString())).thenReturn(mockFuture);
        JRuleEngine.get().setCronScheduler(cronScheduler);

        eventBus.start();

        // Pre-register all items so rule static initializers that access JRuleItems
        // can load without NullPointerExceptions during class loading.
        registerAllItems();
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
        trackedItems.values().forEach(item -> item.setState(UnDefType.NULL));
        clearAllMetadata();
    }

    @AfterEach
    protected void closePersistence() {
        if (persistenceExtensions != null && !persistenceExtensions.isClosed()) {
            persistenceExtensions.close();
            persistenceExtensions = null;
        }
    }

    /**
     * Called from {@link #initEngine()} before any rules are loaded. Subclasses
     * must implement this and call {@link #registerAllItems(Class)} with the
     * project-specific generated {@code JRuleItemNames} class.
     */
    protected abstract void registerAllItems();

    // -------------------------------------------------------------------------
    // Rule registration
    // -------------------------------------------------------------------------

    /**
     * Instantiates the given rule class as a Mockito spy and registers it with
     * the JRuleEngine. The engine is reset before registration so each call
     * starts with a clean rule set.
     */
    protected <T extends JRule> T initRule(Class<T> rule) {
        T spyRule = Mockito.spy(rule);
        JRuleEngine.get().reset();
        JRuleEngine.get().add(spyRule, true);
        return spyRule;
    }

    /**
     * Adds an additional rule to the engine without resetting existing rules.
     * Use this in {@code @BeforeEach} to register secondary rules that the
     * primary rule under test depends on.
     */
    protected <T extends JRule> T addRule(Class<T> ruleClass) {
        T spy = Mockito.spy(ruleClass);
        JRuleEngine.get().add(spy, true);
        return spy;
    }

    // -------------------------------------------------------------------------
    // Item registration
    // -------------------------------------------------------------------------

    /**
     * Populates the mocked item registry from the generated {@code JRuleItemNames}
     * class. Reads every field annotated with {@link JRuleItemDefinition}, creates
     * the corresponding openHAB item via {@link CoreItemFactory}, and registers it
     * with an initial state of {@code NULL}.
     *
     * <p>
     * Idempotent: if items were already registered (e.g. from {@code @BeforeAll}),
     * subsequent calls only reset all item states to {@code NULL} without
     * re-creating items.
     *
     * @param itemNamesClass the generated JRuleItemNames class of the project
     */
    @SuppressWarnings("PMD.EmptyCatchBlock")
    protected void registerAllItems(Class<?> itemNamesClass) {
        if (!trackedItems.isEmpty()) {
            trackedItems.values().forEach(item -> item.setState(UnDefType.NULL));
            return;
        }
        CoreItemFactory factory = new CoreItemFactory(UNIT_PROVIDER);
        for (Field field : itemNamesClass.getDeclaredFields()) {
            JRuleItemDefinition def = field.getDeclaredAnnotation(JRuleItemDefinition.class);
            if (def == null) {
                continue;
            }
            GenericItem item = factory.createItem(def.type(), def.name());
            if (item == null) {
                item = new GroupItem(def.name(), null);
            }
            item.addGroupNames(Arrays.asList(def.groupNames()));
            try {
                registerItem(item, UnDefType.NULL);
            } catch (ItemNotFoundException e) {
                // never thrown by this impl
            }
        }
        // GenericItem.addGroupNames() only records, on the member, which groups it
        // belongs to. A GroupItem's own membership (used by memberItems()/getMembers())
        // is a separate, forward-only list that openHAB's real ItemRegistry normally
        // wires up automatically; since JRuleItemDefinition fields can be declared in
        // any order, group members have to be linked in a second pass, once every item
        // referenced by a groupName is guaranteed to already be tracked.
        trackedItems.values().forEach(item -> item.getGroupNames().forEach(groupName -> {
            if (trackedItems.get(groupName) instanceof GroupItem groupItem) {
                groupItem.addMember(item);
            }
        }));
    }

    /**
     * Registers a single item in the mocked registry with an initial state.
     * The item is tracked so its state is updated automatically when the rule
     * calls {@code sendCommand} or {@code postUpdate}.
     */
    protected void registerItem(GenericItem item, State state) throws ItemNotFoundException {
        item.setState(state);
        trackedItems.put(item.getName(), item);
        when(itemRegistry.getItem(item.getName())).thenReturn(item);
        when(itemRegistry.getItems()).thenReturn((Collection) trackedItems.values());
        eventCollector.registerItem(item);
    }

    // -------------------------------------------------------------------------
    // Metadata helpers
    // -------------------------------------------------------------------------

    /**
     * Installs the shared fake {@link MetadataRegistry} exactly once per JVM.
     * {@code JRuleItemRegistry} caches each {@code JRuleItem} wrapper forever
     * once created, permanently bound to whichever registry was live at that
     * moment - so replacing the registry with a fresh mock per test class (as
     * earlier versions of this base class did) only ever takes effect for
     * items no test has touched yet. Installing one mutable, shared fake here
     * and never swapping it out again means every test class - regardless of
     * execution order within the same Surefire fork - can rely on
     * {@link #setItemMetadata} actually being visible to already-cached items.
     */
    private static synchronized void installMetadataRegistry() {
        if (metadataRegistryInstalled) {
            return;
        }
        MetadataRegistry fake = Mockito.mock(MetadataRegistry.class);
        Mockito.when(fake.stream()).thenAnswer(invocation -> METADATA_STORE.stream());
        Mockito.when(fake.get(Mockito.any())).thenAnswer(invocation -> {
            MetadataKey key = invocation.getArgument(0);
            return METADATA_STORE.stream().filter(m -> m.getUID().equals(key)).findFirst().orElse(null);
        });
        JRuleItemRegistry.setMetadataRegistry(fake);
        metadataRegistryInstalled = true;
    }

    /**
     * Sets a metadata namespace/value pair for an item (e.g. the "location"
     * namespace used by project-specific room lookups), visible to rule code
     * via {@code JRuleItem.getMetadata()}. Replaces any existing value for the
     * same item/namespace.
     */
    protected void setItemMetadata(String itemName, String namespace, String value) {
        setItemMetadata(itemName, namespace, value, Map.of());
    }

    /** Same as {@link #setItemMetadata(String, String, String)} with an explicit metadata configuration map. */
    protected void setItemMetadata(String itemName, String namespace, String value, Map<String, Object> configuration) {
        MetadataKey key = new MetadataKey(namespace, itemName);
        METADATA_STORE.removeIf(m -> m.getUID().equals(key));
        METADATA_STORE.add(new Metadata(key, value, configuration));
    }

    /** Removes all metadata (all namespaces) for a single item. */
    protected void clearItemMetadata(String itemName) {
        METADATA_STORE.removeIf(m -> m.getUID().getItemName().equals(itemName));
    }

    /**
     * Removes all metadata for all items. Called automatically from
     * {@link #resetBetweenTests()}; project-specific caches that memoize
     * metadata lookups (e.g. a location cache) are not covered by this and
     * must be cleared separately by the project's own test support code.
     */
    protected void clearAllMetadata() {
        METADATA_STORE.clear();
    }

    // -------------------------------------------------------------------------
    // State helpers
    // -------------------------------------------------------------------------

    /**
     * Sets item state directly without firing events (for test setup).
     */
    protected void setState(String itemName, JRuleValue value) {
        GenericItem item = trackedItems.get(itemName);
        if (item == null) {
            throw new IllegalStateException("Item not registered: " + itemName);
        }
        item.setState(value.toOhState());
    }

    @SuppressWarnings("unchecked")
    protected <S extends JRuleValue> S getState(String itemName) {
        State state = eventCollector.getItemState(itemName);
        return (S) JRuleEventHandler.toValue(state);
    }

    @SuppressWarnings("unchecked")
    protected <S extends JRuleValue> S getState(String itemName, Class<S> type) {
        State state = eventCollector.getItemState(itemName);
        return (S) JRuleEventHandler.toValue(state);
    }

    /** Returns the raw openHAB {@link State} of a registered item. */
    public State getItemState(String itemName) {
        return eventCollector.getItemState(itemName);
    }

    protected void assertState(String itemName, JRuleValue value) {
        Assertions.assertEquals(value, getState(itemName), "Item '" + itemName + "' state mismatch");
    }

    // -------------------------------------------------------------------------
    // Event-firing bridges
    // -------------------------------------------------------------------------

    /**
     * Fires a synchronous state-changed event for {@code itemName}.
     */
    protected void fireEvents(boolean async, List<Event> events) {
        eventBus.fire(async, events);
    }

    /** Fires a state-changed event (old state is the item's current state). */
    protected void fireChange(String itemName, JRuleValue newState) {
        State oldState = Optional.ofNullable(trackedItems.get(itemName)).map(GenericItem::getState)
                .orElse(UnDefType.NULL);
        if (trackedItems.containsKey(itemName)) {
            trackedItems.get(itemName).setState(newState.toOhState());
        }
        fireStateChanged(itemName, newState.toOhState(), oldState);
    }

    /** Fires a state-changed event with explicit old and new state. */
    protected void fireStateChanged(String itemName, State newState, State oldState) {
        fireEvents(false, List.of(ItemEventFactory.createStateChangedEvent(itemName, newState, oldState, null, null)));
    }

    /** Fires an item command event. */
    protected void fireCommand(String itemName, JRuleValue command) {
        Command ohCommand = command.toOhCommand();
        // command-only types such as INCREASE/DECREASE have no state counterpart and leave the state alone
        if (trackedItems.containsKey(itemName) && ohCommand instanceof State state) {
            trackedItems.get(itemName).setState(state);
        }
        fireEvents(false, List.of(ItemEventFactory.createCommandEvent(itemName, ohCommand)));
    }

    /** Fires a state-updated event. */
    protected void fireUpdate(String itemName, JRuleValue newState) {
        if (trackedItems.containsKey(itemName)) {
            trackedItems.get(itemName).setState(newState.toOhState());
        }
        fireEvents(false, List.of(ItemEventFactory.createStateUpdatedEvent(itemName, newState.toOhState(), null)));
    }

    /** Fires a group-member state-changed event. */
    @SuppressWarnings("null")
    protected void fireGroupChange(String groupName, String memberName, JRuleValue newState) {
        GenericItem existing = trackedItems.get(groupName);
        State oldState = (existing != null && existing.getState() != null) ? existing.getState() : UnDefType.NULL;
        if (existing != null) {
            existing.setState(newState.toOhState());
        }
        fireEvents(false, List.of(ItemEventFactory.createGroupStateChangedEvent(groupName, memberName,
                newState.toOhState(), oldState, null, null)));
    }

    // -------------------------------------------------------------------------
    // Persistence mocking
    // -------------------------------------------------------------------------

    /**
     * Stubs a {@link PersistenceExtensions} static call so that it returns a
     * {@link HistoricItem} wrapping {@code value} when called for {@code itemName}.
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

    /**
     * Stubs a {@link PersistenceExtensions} static call so that it returns a raw
     * {@link State} (not a {@link HistoricItem}) for {@code itemName}. Use this
     * for methods like {@code averageSince} that return a {@code State} directly.
     */
    protected void mockPersistenceState(MockedStatic.Verification verification, String itemName, JRuleValue value) {
        persistenceExtensions.when(verification).thenAnswer(invocation -> {
            Item item = invocation.getArgument(0, Item.class);
            if (item.getName().equals(itemName)) {
                return value.toOhState();
            }
            throw new IllegalStateException("not mocked: " + item.getName());
        });
    }

    /**
     * Convenience stub for {@link PersistenceExtensions#averageSince}.
     * Matches regardless of whether the rule passes null or a named persistence service.
     */
    protected void mockAverageSince(String itemName, JRuleValue value) {
        mockPersistenceState(() -> PersistenceExtensions.averageSince(any(Item.class), any(ZonedDateTime.class),
                nullable(String.class)), itemName, value);
    }

    /** Convenience stub for {@link PersistenceExtensions#getAllStatesBetween}. */
    protected void mockAllStatesBetween(String itemName, List<JRuleHistoricState> values) {
        mockPersistence(() -> PersistenceExtensions.getAllStatesBetween(any(Item.class), any(ZonedDateTime.class),
                any(ZonedDateTime.class), nullable(String.class)), itemName, values);
    }

    /**
     * Stubs a {@link PersistenceExtensions} static call to return a list of
     * {@link HistoricItem}s when called for {@code itemName}.
     */
    protected void mockPersistence(MockedStatic.Verification verification, String itemName,
            List<JRuleHistoricState> values) {
        persistenceExtensions.when(verification).thenAnswer(invocation -> {
            Item item = invocation.getArgument(0, Item.class);
            if (item.getName().equals(itemName)) {
                return values.stream().map(v -> (HistoricItem) new ListHistoricItem(v)).toList();
            }
            throw new IllegalStateException("not mocked: " + item.getName());
        });
    }

    // -------------------------------------------------------------------------
    // Dynamic-test helpers
    // -------------------------------------------------------------------------

    /**
     * Resets state between dynamic tests within a {@code @TestFactory}.
     * Clears the spy's recorded calls, all timers, the event collector, and
     * all item states.
     */
    protected void resetDynamicTest(Object spyRule) {
        Mockito.reset(spyRule);
        JRuleTimerHandler.get().cancelAll();
        eventCollector.clear();
        trackedItems.values().forEach(item -> item.setState(UnDefType.NULL));
    }

    // -------------------------------------------------------------------------
    // Command / update / timer assertions
    // -------------------------------------------------------------------------

    protected void assertCommandSent(String itemName, Object expectedCommand) {
        Assertions.assertTrue(eventCollector.hasCommandEvent(itemName, expectedCommand),
                "Expected command '" + expectedCommand + "' to be sent to item '" + itemName + "'");
    }

    protected void assertNoCommandSent(String itemName) {
        Assertions.assertTrue(eventCollector.getCommandEvents(itemName).isEmpty(),
                "Expected no commands to be sent to item '" + itemName + "', but "
                        + eventCollector.getCommandEvents(itemName).size() + " were recorded");
    }

    protected void assertNoUpdateSent(String itemName) {
        Assertions.assertTrue(eventCollector.getUpdateEvents(itemName).isEmpty(),
                "Expected no updates to be posted to item '" + itemName + "', but "
                        + eventCollector.getUpdateEvents(itemName).size() + " were recorded");
    }

    protected void assertUpdateSent(String itemName, Object expectedState) {
        Assertions.assertTrue(eventCollector.hasUpdateEvent(itemName, expectedState),
                "Expected update '" + expectedState + "' to be posted to item '" + itemName + "'");
    }

    protected void assertItemHasState(String itemName, Object expectedState) {
        State actual = eventCollector.getItemState(itemName);
        Assertions.assertEquals(expectedState.toString(), actual.toString(), "Item '" + itemName + "' state mismatch");
    }

    protected List<JRuleTimerHandler.JRuleTimer> getTimers(String timerName) {
        return JRuleTimerHandler.get().getTimers(timerName);
    }

    protected void invokeTimer(String timerName) {
        List<JRuleTimerHandler.JRuleTimer> timers = JRuleTimerHandler.get().getTimers(timerName);
        Assertions.assertFalse(timers.isEmpty(),
                "No timer found with name '" + timerName + "' — rule may not have created it");
        timers.get(0).invoke();
    }

    protected void assertTimerRunning(String timerName) {
        Assertions.assertFalse(JRuleTimerHandler.get().getTimers(timerName).isEmpty(),
                "Expected timer '" + timerName + "' to be running, but none was found");
    }

    protected void assertNoTimerRunning(String timerName) {
        Assertions.assertTrue(JRuleTimerHandler.get().getTimers(timerName).isEmpty(),
                "Expected no timer '" + timerName + "' to be running, but one was found");
    }

    // -------------------------------------------------------------------------
    // Async assertion helpers
    // -------------------------------------------------------------------------

    protected void assertUpdateSentEventually(String itemName, Object expectedState, Duration timeout) {
        Awaitility.await().atMost(timeout.toMillis(), TimeUnit.MILLISECONDS).pollInterval(Duration.ofMillis(100))
                .untilAsserted(() -> assertUpdateSent(itemName, expectedState));
    }

    protected void assertUpdateSentEventually(String itemName, Object expectedState) {
        assertUpdateSentEventually(itemName, expectedState, Duration.ofSeconds(3));
    }

    // -------------------------------------------------------------------------
    // Rule-method assertions
    // -------------------------------------------------------------------------

    protected void assertRuleMethodCalled(Consumer<R> verification) {
        verification.accept(Mockito.verify(rule, Mockito.atLeastOnce()));
    }

    protected void assertRuleMethodCalled(Consumer<R> verification, int times) {
        verification.accept(Mockito.verify(rule, Mockito.times(times)));
    }

    protected void assertRuleMethodNeverCalled(Consumer<R> verification) {
        verification.accept(Mockito.verify(rule, Mockito.never()));
    }

    @SuppressWarnings("unchecked")
    protected <T> T anyArg() {
        return (T) Mockito.any();
    }

    // -------------------------------------------------------------------------
    // Inner classes
    // -------------------------------------------------------------------------

    private static final class OhHistoricItem implements HistoricItem {
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

    private static final class ListHistoricItem implements HistoricItem {
        private final JRuleHistoricState state;

        ListHistoricItem(JRuleHistoricState state) {
            this.state = state;
        }

        @Override
        public ZonedDateTime getTimestamp() {
            return state.getTimestamp();
        }

        @Override
        public State getState() {
            return state.getValue().toOhState();
        }

        @Override
        public String getName() {
            return "";
        }
    }

    // -------------------------------------------------------------------------
    // UnitProvider
    // -------------------------------------------------------------------------

    /**
     * A minimal {@link UnitProvider} for tests. Uses Celsius for temperature
     * (not Kelvin) so that numeric comparisons in rules work with the expected
     * scale. Falls back to dimensionless (ONE) for openHAB-custom dimensions
     * (EnergyPrice, DataAmount, etc.) that are not in the indriya SI system.
     */
    protected static final UnitProvider UNIT_PROVIDER = new UnitProvider() {
        @Override
        @SuppressWarnings({ "unchecked", "null" })
        public <T extends javax.measure.@org.eclipse.jdt.annotation.NonNull Quantity<T>> Unit<T> getUnit(
                Class<T> dimension) {
            // Rules compare temperatures in Celsius (e.g. targetTemp < 20).
            // Using KELVIN (SI base) would shift values by ~273 and break comparisons.
            if (dimension.equals(javax.measure.quantity.Temperature.class)) {
                return (Unit<T>) tech.units.indriya.unit.Units.CELSIUS;
            }
            Unit<T> unit = tech.units.indriya.unit.Units.getInstance().getUnit(dimension);
            // openHAB-specific dimensions (EnergyPrice, DataAmount, …) are not in the
            // indriya SI registry. Fall back to ONE so items still accept any QuantityType.
            return unit != null ? unit
                    : (Unit<T>) tech.units.indriya.unit.Units.getInstance()
                            .getUnit(javax.measure.quantity.Dimensionless.class);
        }

        @Override
        public javax.measure.spi.SystemOfUnits getMeasurementSystem() {
            return tech.units.indriya.unit.Units.getInstance();
        }

        @Override
        public java.util.Collection<Class<? extends javax.measure.Quantity<?>>> getAllDimensions() {
            return List.of();
        }
    };
}
