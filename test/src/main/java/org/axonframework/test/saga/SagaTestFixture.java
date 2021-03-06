/*
 * Copyright (c) 2010-2016. Axon Framework
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.axonframework.test.saga;

import org.axonframework.commandhandling.gateway.CommandGatewayFactory;
import org.axonframework.commandhandling.gateway.DefaultCommandGateway;
import org.axonframework.common.ReflectionUtils;
import org.axonframework.eventhandling.EventBus;
import org.axonframework.eventhandling.EventMessage;
import org.axonframework.eventhandling.GenericEventMessage;
import org.axonframework.eventhandling.SimpleEventBus;
import org.axonframework.eventhandling.saga.AnnotatedSagaManager;
import org.axonframework.eventhandling.saga.SagaRepository;
import org.axonframework.eventhandling.saga.repository.AnnotatedSagaRepository;
import org.axonframework.eventhandling.saga.repository.inmemory.InMemorySagaStore;
import org.axonframework.eventsourcing.GenericDomainEventMessage;
import org.axonframework.messaging.annotation.ClasspathParameterResolverFactory;
import org.axonframework.messaging.annotation.ParameterResolverFactory;
import org.axonframework.messaging.annotation.SimpleResourceParameterResolverFactory;
import org.axonframework.messaging.unitofwork.DefaultUnitOfWork;
import org.axonframework.test.FixtureExecutionException;
import org.axonframework.test.eventscheduler.StubEventScheduler;
import org.axonframework.test.matchers.FieldFilter;
import org.axonframework.test.matchers.IgnoreField;
import org.axonframework.test.utils.AutowiredResourceInjector;
import org.axonframework.test.utils.CallbackBehavior;
import org.axonframework.test.utils.RecordingCommandBus;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.StreamSupport;

import static java.lang.String.format;
import static org.axonframework.common.ReflectionUtils.fieldsOf;
import static org.axonframework.messaging.annotation.MultiParameterResolverFactory.ordered;

/**
 * Fixture for testing Annotated Sagas based on events and time passing. This fixture allows resources to be configured
 * for the sagas to use.
 *
 * @author Allard Buijze
 * @since 1.1
 */
public class SagaTestFixture<T> implements FixtureConfiguration, ContinuedGivenState {

    private final StubEventScheduler eventScheduler;
    private final List<Object> registeredResources = new LinkedList<>();
    private final Map<Object, AggregateEventPublisherImpl> aggregatePublishers = new HashMap<>();
    private final FixtureExecutionResultImpl<T> fixtureExecutionResult;
    private final RecordingCommandBus commandBus;
    private final MutableFieldFilter fieldFilters = new MutableFieldFilter();
    private final Class<T> sagaType;
    private final InMemorySagaStore sagaStore;
    private AnnotatedSagaManager<T> sagaManager;
    private boolean transienceCheckEnabled = true;

    /**
     * Creates an instance of the AnnotatedSagaTestFixture to test sagas of the given {@code sagaType}.
     *
     * @param sagaType The type of saga under test
     */
    @SuppressWarnings({"unchecked"})
    public SagaTestFixture(Class<T> sagaType) {
        this.sagaType = sagaType;
        eventScheduler = new StubEventScheduler();
        EventBus eventBus = new SimpleEventBus();
        sagaStore = new InMemorySagaStore();
        registeredResources.add(eventBus);
        commandBus = new RecordingCommandBus();
        registeredResources.add(commandBus);
        registeredResources.add(eventScheduler);
        registeredResources.add(new DefaultCommandGateway(commandBus));
        fixtureExecutionResult = new FixtureExecutionResultImpl<>(sagaStore, eventScheduler, eventBus, commandBus,
                                                                  sagaType, fieldFilters);
    }

    /**
     * Handles the given {@code event} in the scope of a Unit of Work. If handling the event results in an exception
     * the exception will be wrapped in a {@link FixtureExecutionException}.
     *
     * @param event The event message to handle
     */
    protected void handleInSaga(EventMessage<?> event) {
        ensureSagaManagerInitialized();
        try {
            DefaultUnitOfWork.startAndGet(event).executeWithResult(() -> sagaManager.handle(event));
        } catch (Exception e) {
            throw new FixtureExecutionException("Exception occurred while handling an event", e);
        }
    }

    /**
     * Initializes the SagaManager if it hasn't already done so. If once the SagaManager is initialized, this method
     * does nothing.
     */
    protected void ensureSagaManagerInitialized() {
        if (sagaManager == null) {
            ParameterResolverFactory parameterResolverFactory = ordered(new SimpleResourceParameterResolverFactory(registeredResources),
                                                                        ClasspathParameterResolverFactory.forClass(sagaType));
            SagaRepository<T> sagaRepository = new AnnotatedSagaRepository<>(sagaType, sagaStore,
                                                                             new TransienceValidatingResourceInjector(),
                                                                             parameterResolverFactory);
            sagaManager = new AnnotatedSagaManager<>(sagaType, sagaRepository, parameterResolverFactory);
            sagaManager.setSuppressExceptions(false);
        }
    }

    @Override
    public FixtureConfiguration withTransienceCheckDisabled() {
        this.transienceCheckEnabled = false;
        return this;
    }

    @Override
    public FixtureExecutionResult whenTimeElapses(Duration elapsedTime) {
        try {
            fixtureExecutionResult.startRecording();
            eventScheduler.advanceTimeBy(elapsedTime, this::handleInSaga);
        } catch (Exception e) {
            throw new FixtureExecutionException("Exception occurred while trying to advance time " +
                                                        "and handle scheduled events", e);
        }
        return fixtureExecutionResult;
    }

    @Override
    public FixtureExecutionResult whenTimeAdvancesTo(Instant newDateTime) {
        try {
            fixtureExecutionResult.startRecording();
            eventScheduler.advanceTimeTo(newDateTime, this::handleInSaga);
        } catch (Exception e) {
            throw new FixtureExecutionException("Exception occurred while trying to advance time " +
                                                        "and handle scheduled events", e);
        }

        return fixtureExecutionResult;
    }

    @Override
    public void registerResource(Object resource) {
        registeredResources.add(resource);
    }

    @Override
    public void setCallbackBehavior(CallbackBehavior callbackBehavior) {
        commandBus.setCallbackBehavior(callbackBehavior);
    }

    @Override
    public GivenAggregateEventPublisher givenAggregate(String aggregateIdentifier) {
        return getPublisherFor(aggregateIdentifier);
    }

    @Override
    public ContinuedGivenState givenAPublished(Object event) {
        handleInSaga(timeCorrectedEventMessage(event));
        return this;
    }

    @Override
    public WhenState givenNoPriorActivity() {
        return this;
    }

    @Override
    public GivenAggregateEventPublisher andThenAggregate(String aggregateIdentifier) {
        return givenAggregate(aggregateIdentifier);
    }

    @Override
    public ContinuedGivenState andThenTimeElapses(final Duration elapsedTime) throws Exception {
        eventScheduler.advanceTimeBy(elapsedTime, this::handleInSaga);
        return this;
    }

    @Override
    public ContinuedGivenState andThenTimeAdvancesTo(final Instant newDateTime) throws Exception {
        eventScheduler.advanceTimeTo(newDateTime, this::handleInSaga);
        return this;
    }

    @Override
    public ContinuedGivenState andThenAPublished(Object event) throws Exception {
        handleInSaga(timeCorrectedEventMessage(event));
        return this;
    }

    @Override
    public WhenAggregateEventPublisher whenAggregate(String aggregateIdentifier) {
        fixtureExecutionResult.startRecording();
        return getPublisherFor(aggregateIdentifier);
    }

    @Override
    public FixtureExecutionResult whenPublishingA(Object event) {
        fixtureExecutionResult.startRecording();
        handleInSaga(timeCorrectedEventMessage(event));
        return fixtureExecutionResult;
    }

    private EventMessage<Object> timeCorrectedEventMessage(Object event) {
        EventMessage<?> msg = GenericEventMessage.asEventMessage(event);
        return new GenericEventMessage<>(msg.getIdentifier(), msg.getPayload(), msg.getMetaData(), currentTime());
    }

    @Override
    public Instant currentTime() {
        return eventScheduler.getCurrentDateTime();
    }

    @Override
    public <I> I registerCommandGateway(Class<I> gatewayInterface) {
        return registerCommandGateway(gatewayInterface, null);
    }

    @Override
    public <I> I registerCommandGateway(Class<I> gatewayInterface, final I stubImplementation) {
        CommandGatewayFactory factory = new StubAwareCommandGatewayFactory(stubImplementation,
                                                                           SagaTestFixture.this.commandBus);
        final I gateway = factory.createGateway(gatewayInterface);
        registerResource(gateway);
        return gateway;
    }

    @Override
    public FixtureConfiguration registerFieldFilter(FieldFilter fieldFilter) {
        this.fieldFilters.add(fieldFilter);
        return this;
    }

    @Override
    public FixtureConfiguration registerIgnoredField(Class<?> declaringClass, String fieldName) {
        return registerFieldFilter(new IgnoreField(declaringClass, fieldName));
    }

    private AggregateEventPublisherImpl getPublisherFor(String aggregateIdentifier) {
        if (!aggregatePublishers.containsKey(aggregateIdentifier)) {
            aggregatePublishers.put(aggregateIdentifier, new AggregateEventPublisherImpl(aggregateIdentifier));
        }
        return aggregatePublishers.get(aggregateIdentifier);
    }

    /**
     * CommandGatewayFactory that is aware of a stub implementation that defines the behavior for the callback.
     */
    private static class StubAwareCommandGatewayFactory extends CommandGatewayFactory {

        private final Object stubImplementation;

        public StubAwareCommandGatewayFactory(Object stubImplementation, RecordingCommandBus commandBus) {
            super(commandBus);
            this.stubImplementation = stubImplementation;
        }

        @Override
        protected <R> InvocationHandler<R> wrapToWaitForResult(final InvocationHandler<CompletableFuture<R>> delegate) {
            return new ReturnResultFromStub<>(delegate, stubImplementation);
        }

        @Override
        protected <R> InvocationHandler<R> wrapToReturnWithFixedTimeout(
                InvocationHandler<CompletableFuture<R>> delegate,
                long timeout, TimeUnit timeUnit) {
            return new ReturnResultFromStub<>(delegate, stubImplementation);
        }

        @Override
        protected <R> InvocationHandler<R> wrapToReturnWithTimeoutInArguments(
                InvocationHandler<CompletableFuture<R>> delegate,
                int timeoutIndex, int timeUnitIndex) {
            return new ReturnResultFromStub<>(delegate, stubImplementation);
        }
    }

    /**
     * Invocation handler that uses a stub implementation (of not {@code null}) to define the value to return from
     * a handler invocation. If none is provided, the returned future is checked for a value. If that future is not
     * "done" (for example because no callback behavior was provided), it returns {@code null}.
     *
     * @param <R> The return type of the method invocation
     */
    private static class ReturnResultFromStub<R> implements CommandGatewayFactory.InvocationHandler<R> {

        private final CommandGatewayFactory.InvocationHandler<CompletableFuture<R>> dispatcher;
        private final Object stubGateway;

        public ReturnResultFromStub(CommandGatewayFactory.InvocationHandler<CompletableFuture<R>> dispatcher,
                                    Object stubGateway) {
            this.dispatcher = dispatcher;
            this.stubGateway = stubGateway;
        }

        @SuppressWarnings("unchecked")
        @Override
        public R invoke(Object proxy, Method invokedMethod, Object[] args) throws Exception {
            Future<R> future = dispatcher.invoke(proxy, invokedMethod, args);
            if (stubGateway != null) {
                return (R) invokedMethod.invoke(stubGateway, args);
            }
            if (future.isDone()) {
                return future.get();
            }
            return null;
        }
    }

    private class AggregateEventPublisherImpl implements GivenAggregateEventPublisher, WhenAggregateEventPublisher {

        private final String aggregateIdentifier, type;
        private int sequenceNumber = 0;

        public AggregateEventPublisherImpl(String aggregateIdentifier) {
            this.aggregateIdentifier = aggregateIdentifier;
            this.type = "Stub_" + aggregateIdentifier;
        }

        @Override
        public ContinuedGivenState published(Object... events) {
            publish(events);
            return SagaTestFixture.this;
        }

        @Override
        public FixtureExecutionResult publishes(Object event) {
            publish(event);
            return fixtureExecutionResult;
        }

        private void publish(Object... events) {
            for (Object event : events) {
                EventMessage<?> eventMessage = GenericEventMessage.asEventMessage(event);
                handleInSaga(new GenericDomainEventMessage<>(type, aggregateIdentifier,
                                                             sequenceNumber++,
                                                             eventMessage.getPayload(),
                                                             eventMessage.getMetaData(),
                                                             eventMessage.getIdentifier(),
                                                             currentTime()));
            }
        }
    }

    private class MutableFieldFilter implements FieldFilter {

        private final List<FieldFilter> filters = new ArrayList<>();

        @Override
        public boolean accept(Field field) {
            for (FieldFilter filter : filters) {
                if (!filter.accept(field)) {
                    return false;
                }
            }
            return true;
        }

        public void add(FieldFilter fieldFilter) {
            filters.add(fieldFilter);
        }
    }

    private class TransienceValidatingResourceInjector extends AutowiredResourceInjector {

        public TransienceValidatingResourceInjector() {
            super(registeredResources);
        }

        @Override
        public void injectResources(Object saga) {
            super.injectResources(saga);
            if (transienceCheckEnabled) {
                StreamSupport.stream(fieldsOf(saga.getClass()).spliterator(), false)
                        .filter(f -> !Modifier.isTransient(f.getModifiers()))
                        .filter(f -> registeredResources.contains(ReflectionUtils.getFieldValue(f, saga)))
                        .findFirst()
                        .ifPresent(field -> {
                            throw new AssertionError(format("Field %s.%s is injected with a resource, " +
                                                                    "but it doesn't have the 'transient' modifier.\n" +
                                                                    "Mark field as 'transient' or disable this check using:\n" +
                                                                    "fixture.withTransienceCheckDisabled()",
                                                            field.getDeclaringClass(), field.getName()));
                        });
            }
        }
    }
}
