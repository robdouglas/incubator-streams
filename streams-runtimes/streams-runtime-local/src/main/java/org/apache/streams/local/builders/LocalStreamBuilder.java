/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.streams.local.builders;

import com.google.common.util.concurrent.Uninterruptibles;
import org.apache.streams.core.*;
import org.apache.streams.local.counters.StreamsTaskCounter;
import org.apache.streams.local.executors.ShutdownStreamOnUnhandleThrowableThreadPoolExecutor;
import org.apache.streams.local.queues.ThroughputQueue;
import org.apache.streams.local.tasks.*;
import org.apache.streams.monitoring.tasks.BroadcastMonitorThread;
import org.joda.time.DateTime;
import org.slf4j.Logger;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * {@link org.apache.streams.local.builders.LocalStreamBuilder} implementation to run a data processing stream in a single
 * JVM across many threads.  Depending on your data stream, the JVM heap may need to be set to a high value. Default
 * implementation uses unbound {@link java.util.concurrent.ConcurrentLinkedQueue} to connect stream components.
 */
public class LocalStreamBuilder implements StreamBuilder {

    private static final Logger LOGGER = org.slf4j.LoggerFactory.getLogger(LocalStreamBuilder.class);
    private static final int DEFAULT_QUEUE_SIZE = 500;

    public static final String TIMEOUT_KEY = "TIMEOUT";
    public static final String BROADCAST_KEY = "broadcastURI";
    public static final String STREAM_IDENTIFIER_KEY = "streamsID";
    public static final String BROADCAST_INTERVAL_KEY = "monitoring_broadcast_interval_ms";
    public static final String DEFAULT_STREAM_IDENTIFIER = "Unknown_Stream";
    public static final String DEFAULT_STARTED_AT_KEY = "startedAt";

    private Map<String, StreamComponent> providers;
    private Map<String, StreamComponent> components;
    private Map<String, Object> streamConfig;
    private Map<StreamsTask, Future> futures;
    private ExecutorService executor;
    private ExecutorService monitor;
    private int totalTasks;
    private int monitorTasks;
    private LocalStreamProcessMonitorThread monitorThread;
    private Map<String, List<StreamsTask>> tasks;
    private Thread shutdownHook;
    private BroadcastMonitorThread broadcastMonitor;
    private int maxQueueCapacity;
    private String streamIdentifier = DEFAULT_STREAM_IDENTIFIER;
    private DateTime startedAt = new DateTime();
    private boolean useDeprecatedMonitors;

    /**
     * Creates a local stream builder with no config object and default maximum internal queue size of 500
     */
    public LocalStreamBuilder(){
        this(DEFAULT_QUEUE_SIZE, null);
    }

    /**
     * Creates a local stream builder with a config object and default maximum internal queue size of 500
     * @param streamConfig
     */
    public LocalStreamBuilder(Map<String, Object> streamConfig) {
        this(DEFAULT_QUEUE_SIZE, streamConfig);
    }

    /**
     * Creates a local stream builder with no config object. If maxQueueCapacity is less than 1 the queue is
     * unbounded.
     * @param maxQueueCapacity
     */
    public LocalStreamBuilder(int maxQueueCapacity) {
        this(maxQueueCapacity, null);
    }

    /**
     * Creates a local stream builder with a config object. If maxQueueCapacity is less than 1 the queue is
     * unbounded.
     * @param maxQueueCapacity
     * @param streamConfig
     */
    public LocalStreamBuilder(int maxQueueCapacity, Map<String, Object> streamConfig) {
        this.providers = new HashMap<String, StreamComponent>();
        this.components = new HashMap<String, StreamComponent>();
        this.streamConfig = streamConfig;
        this.totalTasks = 0;
        this.monitorTasks = 0;
        this.maxQueueCapacity = maxQueueCapacity;
        final LocalStreamBuilder self = this;
        this.shutdownHook = new Thread() {
            @Override
            public void run() {
                LOGGER.debug("Shutdown hook received.  Beginning shutdown");
                self.stopInternal(true);
            }
        };

        setStreamIdentifier();
        if(this.streamConfig != null) {
            this.streamConfig.put(DEFAULT_STARTED_AT_KEY, startedAt.getMillis());
        }
        this.useDeprecatedMonitors = false;
        this.broadcastMonitor = new BroadcastMonitorThread(this.streamConfig);

        this.futures = new HashMap<>();
    }

    public void setUseDeprecatedMonitors(boolean useDeprecatedMonitors) {
        this.useDeprecatedMonitors = useDeprecatedMonitors;
    }

    @Override
    public StreamBuilder newPerpetualStream(String id, StreamsProvider provider) {
        validateId(id);
        this.providers.put(id, new StreamComponent(id, provider, true, streamConfig));
        ++this.totalTasks;
        if(this.useDeprecatedMonitors && provider instanceof DatumStatusCountable )
            ++this.monitorTasks;
        return this;
    }

    @Override
    public StreamBuilder newReadCurrentStream(String id, StreamsProvider provider) {
        validateId(id);
        this.providers.put(id, new StreamComponent(id, provider, false, streamConfig));
        ++this.totalTasks;
        if(this.useDeprecatedMonitors && provider instanceof DatumStatusCountable )
            ++this.monitorTasks;
        return this;
    }

    @Override
    public StreamBuilder newReadNewStream(String id, StreamsProvider provider, BigInteger sequence) {
        validateId(id);
        this.providers.put(id, new StreamComponent(id, provider, sequence, streamConfig));
        ++this.totalTasks;
        if(this.useDeprecatedMonitors && provider instanceof DatumStatusCountable )
            ++this.monitorTasks;
        return this;
    }

    @Override
    public StreamBuilder newReadRangeStream(String id, StreamsProvider provider, DateTime start, DateTime end) {
        validateId(id);
        this.providers.put(id, new StreamComponent(id, provider, start, end, streamConfig));
        ++this.totalTasks;
        if(this.useDeprecatedMonitors && provider instanceof DatumStatusCountable )
            ++this.monitorTasks;
        return this;
    }

    @Override
    public StreamBuilder addStreamsProcessor(String id, StreamsProcessor processor, int numTasks, String... inBoundIds) {
        validateId(id);
        StreamComponent comp = new StreamComponent(id, processor, new ThroughputQueue<StreamsDatum>(this.maxQueueCapacity, id, streamIdentifier, startedAt.getMillis()), numTasks, streamConfig);
        this.components.put(id, comp);
        connectToOtherComponents(inBoundIds, comp);
        this.totalTasks += numTasks;
        if(this.useDeprecatedMonitors && processor instanceof DatumStatusCountable )
            ++this.monitorTasks;
        return this;
    }

    @Override
    public StreamBuilder addStreamsPersistWriter(String id, StreamsPersistWriter writer, int numTasks, String... inBoundIds) {
        validateId(id);
        StreamComponent comp = new StreamComponent(id, writer, new ThroughputQueue<StreamsDatum>(this.maxQueueCapacity, id, streamIdentifier, startedAt.getMillis()), numTasks, streamConfig);
        this.components.put(id, comp);
        connectToOtherComponents(inBoundIds, comp);
        this.totalTasks += numTasks;
        if(this.useDeprecatedMonitors && writer instanceof DatumStatusCountable )
            ++this.monitorTasks;
        return this;
    }

    /**
     * Runs the data stream in the this JVM and blocks till completion.
     */
    @Override
    public void start() {
        attachShutdownHandler();
        boolean isRunning = true;
        this.executor = new ShutdownStreamOnUnhandleThrowableThreadPoolExecutor(this.totalTasks, this);
        this.monitor = Executors.newCachedThreadPool();
        Map<String, StreamsProviderTask> provTasks = new HashMap<String, StreamsProviderTask>();
        tasks = new HashMap<String, List<StreamsTask>>();
        boolean forcedShutDown = false;

        try {
            if (this.useDeprecatedMonitors) {
                monitorThread = new LocalStreamProcessMonitorThread(executor, 10);
                this.monitor.submit(monitorThread);
            }
            setupComponentTasks(tasks);
            setupProviderTasks(provTasks);
            LOGGER.info("Started stream with {} components", tasks.size());
            while(isRunning) {
                isRunning = false;
                for(StreamsProviderTask task : provTasks.values()) {
                    isRunning = isRunning || task.isRunning();
                }
                for(StreamComponent task: components.values()) {
                    boolean tasksRunning = false;
                    for(StreamsTask t : task.getStreamsTasks()) {
                        if(t instanceof BaseStreamsTask) {
                            tasksRunning = tasksRunning || ((BaseStreamsTask) t).isRunning();
                        }
                    }
                    isRunning = isRunning || (tasksRunning && task.getInBoundQueue().size() > 0);
                }
                if(isRunning) {
                    Uninterruptibles.sleepUninterruptibly(2500, TimeUnit.MILLISECONDS);
                }
            }
            LOGGER.info("Components are no longer running or timed out");
        } catch (Exception e){
            LOGGER.warn("Runtime exception.  Beginning shutdown");
            forcedShutDown = true;
        } finally{
            LOGGER.info("Stream has completed, pausing @ {}", System.currentTimeMillis());
            Uninterruptibles.sleepUninterruptibly(5000, TimeUnit.MILLISECONDS);
            LOGGER.info("Stream has completed, shutting down @ {}", System.currentTimeMillis());
            stopInternal(forcedShutDown);
        }

    }

    private void attachShutdownHandler() {
        LOGGER.debug("Attaching shutdown handler");
        Runtime.getRuntime().addShutdownHook(shutdownHook);
    }

    private void detachShutdownHandler() {
        LOGGER.debug("Detaching shutdown handler");
        Runtime.getRuntime().removeShutdownHook(shutdownHook);
    }

    protected void forceShutdown(Map<String, List<StreamsTask>> streamsTasks) {
        LOGGER.debug("Shutdown failed.  Forcing shutdown");
        for(List<StreamsTask> tasks : streamsTasks.values()) {
            for(StreamsTask task : tasks) {
                task.stopTask();
                if(task.isWaiting()) {
                    this.futures.get(task).cancel(true);
                }
            }
        }
        this.executor.shutdown();
        this.monitor.shutdown();
        try {
            if(!this.executor.awaitTermination(3, TimeUnit.SECONDS)){
                this.executor.shutdownNow();
            }
            if(!this.monitor.awaitTermination(3, TimeUnit.SECONDS)){
                this.monitor.shutdownNow();
            }
        }catch (InterruptedException ie) {
            this.executor.shutdownNow();
            this.monitor.shutdownNow();
            throw new RuntimeException(ie);
        }
    }

    protected void shutdown(Map<String, List<StreamsTask>> streamsTasks) throws InterruptedException {
        LOGGER.info("Attempting to shutdown tasks");
        if (this.monitorThread != null) {
            this.monitorThread.shutdown();
        }
        this.executor.shutdown();
        //complete stream shut down gracfully
        for(StreamComponent prov : this.providers.values()) {
            shutDownTask(prov, streamsTasks);
        }
        //need to make this configurable
        if(!this.executor.awaitTermination(10, TimeUnit.SECONDS)) { // all threads should have terminated already.
            this.executor.shutdownNow();
            this.executor.awaitTermination(10, TimeUnit.SECONDS);
        }
        if(!this.monitor.awaitTermination(5, TimeUnit.SECONDS)) { // all threads should have terminated already.
            this.monitor.shutdownNow();
            this.monitor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    protected void setupProviderTasks(Map<String, StreamsProviderTask> provTasks) {
        for(StreamComponent prov : this.providers.values()) {
            StreamsTask task = prov.createConnectedTask(getTimeout());
            task.setStreamConfig(this.streamConfig);
            StreamsTaskCounter counter = new StreamsTaskCounter(prov.getId(), streamIdentifier, startedAt.getMillis());
            task.setStreamsTaskCounter(counter);
            this.executor.submit(task);
            provTasks.put(prov.getId(), (StreamsProviderTask) task);
            if(this.useDeprecatedMonitors && prov.isOperationCountable() ) {
                this.monitor.submit(new StatusCounterMonitorThread((DatumStatusCountable) prov.getOperation(), 10));
                this.monitor.submit(new StatusCounterMonitorThread((DatumStatusCountable) task, 10));
            }
        }
    }

    protected void setupComponentTasks(Map<String, List<StreamsTask>> streamsTasks) {
        for(StreamComponent comp : this.components.values()) {
            int tasks = comp.getNumTasks();
            List<StreamsTask> compTasks = new LinkedList<StreamsTask>();
            StreamsTaskCounter counter = new StreamsTaskCounter(comp.getId(), streamIdentifier, startedAt.getMillis());
            for(int i=0; i < tasks; ++i) {
                StreamsTask task = comp.createConnectedTask(getTimeout());
                task.setStreamsTaskCounter(counter);
                task.setStreamConfig(this.streamConfig);
                this.futures.put(task, this.executor.submit(task));
                compTasks.add(task);
                if(this.useDeprecatedMonitors &&  comp.isOperationCountable() ) {
                    this.monitor.submit(new StatusCounterMonitorThread((DatumStatusCountable) comp.getOperation(), 10));
                    this.monitor.submit(new StatusCounterMonitorThread((DatumStatusCountable) task, 10));
                }
                this.monitor.submit(broadcastMonitor);
            }
            streamsTasks.put(comp.getId(), compTasks);
        }
    }

    /**
     * Shutsdown the running tasks in sudo depth first search kind of way. Checks that the upstream components have
     * finished running before shutting down. Waits till inbound queue is empty to shutdown.
     * @param comp StreamComponent to shut down.
     * @param streamTasks the list of non-StreamsProvider tasks for this stream.
     * @throws InterruptedException
     */
    private void shutDownTask(StreamComponent comp, Map<String, List<StreamsTask>> streamTasks) throws InterruptedException {
        List<StreamsTask> tasks = streamTasks.get(comp.getId());
        if(tasks != null) { //not a StreamProvider
            boolean parentsShutDown = true;
            for(StreamComponent parent : comp.getUpStreamComponents()) {
                List<StreamsTask> parentTasks = streamTasks.get(parent.getId());
                //if parentTask == null, its a provider and is not running anymore
                if(parentTasks != null) {
                    for(StreamsTask task : parentTasks) {
                        parentsShutDown = parentsShutDown && !task.isRunning();
                    }
                }
            }
            if(parentsShutDown) {
                for(StreamsTask task : tasks) {
                    task.stopTask();
                    if(task.isWaiting()) {
                        this.futures.get(task).cancel(true); // no data to process, interrupt block queue
                    }
                }
                for(StreamsTask task : tasks) {
                    int count = 0;
                    while(count < 20 && task.isRunning()) {
                        Thread.sleep(500);
                        count++;
                    }
                    if(task.isRunning()) {
                        LOGGER.warn("Task {} failed to terminate in allotted timeframe", task.toString());
                    }
                }
            }
        }
        Collection<StreamComponent> children = comp.getDownStreamComponents();
        if(children != null) {
            for(StreamComponent child : comp.getDownStreamComponents()) {
                shutDownTask(child, streamTasks);
            }
        }
    }

    /**
     * NOT IMPLEMENTED.
     */
    @Override
    public void stop() {
        stopInternal(false);
    }



    protected void stopInternal(boolean systemExiting) {
        try {
            shutdown(tasks);
        } catch (Exception e) {
            LOGGER.error("Exception while trying to shutdown Stream: {}", e);
            forceShutdown(tasks);
        } finally {
            if(!systemExiting) {
                detachShutdownHandler();
            }
        }
    }

    private void connectToOtherComponents(String[] conntectToIds, StreamComponent toBeConnected) {
        for(String id : conntectToIds) {
            StreamComponent upStream = null;
            if(this.providers.containsKey(id)) {
                upStream = this.providers.get(id);
            }
            else if(this.components.containsKey(id)) {
                upStream = this.components.get(id);
            }
            else {
                throw new InvalidStreamException("Cannot connect to id, "+id+", because id does not exist.");
            }
            upStream.addOutBoundQueue(toBeConnected, toBeConnected.getInBoundQueue());
            toBeConnected.addInboundQueue(upStream);
        }
    }

    private void validateId(String id) {
        if(this.providers.containsKey(id) || this.components.containsKey(id)) {
            throw new InvalidStreamException("Duplicate id. "+id+" is already assigned to another component");
        } else if(id.contains(":")) {
            throw new InvalidStreamException("Invalid character, ':', in component id : "+id);
        }
    }

    protected int getTimeout() {
        //Set the timeout of it is configured, otherwise signal downstream components to use their default
        return streamConfig != null && streamConfig.containsKey(TIMEOUT_KEY) ? (Integer)streamConfig.get(TIMEOUT_KEY) : -1;
    }

    private void setStreamIdentifier() {
        if(streamConfig != null &&
                streamConfig.containsKey(STREAM_IDENTIFIER_KEY) &&
                streamConfig.get(STREAM_IDENTIFIER_KEY) != null &&
                streamConfig.get(STREAM_IDENTIFIER_KEY).toString().length() > 0) {
            this.streamIdentifier = streamConfig.get(STREAM_IDENTIFIER_KEY).toString();
        } else {
            this.streamIdentifier = DEFAULT_STREAM_IDENTIFIER;
        }
    }

}