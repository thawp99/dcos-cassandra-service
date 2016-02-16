package com.mesosphere.dcos.cassandra.scheduler.config;

import com.google.common.net.InetAddresses;
import com.google.inject.Inject;
import com.google.inject.name.Named;
import com.mesosphere.dcos.cassandra.common.config.CassandraApplicationConfig;
import com.mesosphere.dcos.cassandra.common.config.CassandraConfig;
import com.mesosphere.dcos.cassandra.common.serialization.Serializer;
import com.mesosphere.dcos.cassandra.common.tasks.CassandraDaemonStatus;
import com.mesosphere.dcos.cassandra.common.tasks.CassandraDaemonTask;
import com.mesosphere.dcos.cassandra.common.tasks.CassandraMode;
import com.mesosphere.dcos.cassandra.common.tasks.CassandraTaskExecutor;
import com.mesosphere.dcos.cassandra.scheduler.persistence.PersistenceException;
import com.mesosphere.dcos.cassandra.scheduler.persistence.PersistenceFactory;
import com.mesosphere.dcos.cassandra.scheduler.persistence.PersistentReference;
import io.dropwizard.lifecycle.Managed;
import org.apache.mesos.Protos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;


public class ConfigurationManager implements Managed {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(ConfigurationManager.class);

    private final PersistentReference<CassandraConfig> cassandraRef;

    private final PersistentReference<ExecutorConfig> executorRef;
    private final PersistentReference<Integer> serversRef;
    private final PersistentReference<Integer> seedsRef;
    private volatile CassandraConfig cassandraConfig;
    private volatile ExecutorConfig executorConfig;
    private volatile int servers;
    private volatile int seeds;
    private volatile boolean updateConfig;
    private final String placementStrategy;
    private final String planStrategy;
    private final String seedsUrl;

    private void reconcileConfiguration() throws PersistenceException {
        LOGGER.info("Reconciling remote and persisted configuration");

        Optional<Integer> serversOption = serversRef.load();

        if (updateConfig) {
            LOGGER.info("Configuration update requested");

            if (serversOption.isPresent()) {
                int servers = serversOption.get();
                if (servers > this.servers) {
                    String error = String.format("The number of configured " +
                                    "servers (%d) is less than the current " +
                                    "number of configured servers (%d). Reduce the " +
                                    "number of servers by removing them from the cluster",
                            servers,
                            this.servers);
                    LOGGER.error(error);
                    throw new IllegalStateException(error);
                }

            }

            if (seeds > servers) {
                String error = String.format("The number of configured " +
                                "seeds (%d) is less than the current number " +
                                "of configured servers (%d). Reduce the " +
                                "number of seeds or increase the number of servers",
                        servers,
                        this.servers);
                LOGGER.error(error);
                throw new IllegalStateException(error);
            }

            serversRef.store(servers);
            seedsRef.store(seeds);
            cassandraRef.store(cassandraConfig);
            executorRef.store(executorConfig);

        } else {
            LOGGER.info("Using persisted configuration");
            servers = serversRef.putIfAbsent(servers);
            seeds = seedsRef.putIfAbsent(seeds);
            cassandraConfig = cassandraRef.putIfAbsent(cassandraConfig);
            executorConfig = executorRef.putIfAbsent(executorConfig);
        }
    }

    @Inject
    public ConfigurationManager(
            @Named("ConfiguredCassandraConfig") CassandraConfig cassandraConfig,
            @Named("ConfiguredExecutorConfig") ExecutorConfig executorConfig,
            @Named("ConfiguredServers") int servers,
            @Named("ConfiguredSeeds") int seeds,
            @Named("ConfiguredUpdateConfig") boolean updateConfig,
            @Named("ConfiguredPlacementStrategy") String placementStrategy,
            @Named("ConfiguredPlanStrategy") String planStrategy,
            @Named("SeedsUrl") String seedsUrl,
            PersistenceFactory persistenceFactory,
            Serializer<CassandraConfig> cassandraConfigSerializer,
            Serializer<ExecutorConfig> executorConfigSerializer,
            Serializer<Integer> intSerializer) {
        this.cassandraRef = persistenceFactory.createReference(
                "cassandraConfig",
                cassandraConfigSerializer);
        this.executorRef = persistenceFactory.createReference(
                "executorConfig",
                executorConfigSerializer);
        this.serversRef = persistenceFactory.createReference(
                "servers",
                intSerializer);
        this.seedsRef = persistenceFactory.createReference(
                "seeds",
                intSerializer);
        this.cassandraConfig = cassandraConfig;
        this.executorConfig = executorConfig;
        this.servers = servers;
        this.seeds = seeds;
        this.updateConfig = updateConfig;
        this.placementStrategy = placementStrategy;
        this.planStrategy = planStrategy;
        this.seedsUrl = seedsUrl;

        try{
            reconcileConfiguration();
        } catch(Throwable throwable){
            throw new IllegalStateException("Failed to reconcile " +
                    "configuration",
                    throwable);
        }
    }

    public CassandraConfig getCassandraConfig() {

        return cassandraConfig;
    }

    public ExecutorConfig getExecutorConfig() {

        return executorConfig;
    }

    public int getServers() {
        return servers;
    }

    public int getSeeds() {
        return seeds;
    }

    public String getPlacementStrategy() {
        return placementStrategy;
    }

    public String getPlanStrategy() {
        return planStrategy;
    }

    public void setCassandraConfig(final CassandraConfig cassandraConfig)
            throws PersistenceException {

        synchronized (cassandraRef) {
            cassandraRef.store(cassandraConfig);
            this.cassandraConfig = cassandraConfig;
        }
    }

    public void setExecutorConfig(ExecutorConfig executorConfig)
            throws PersistenceException {

        synchronized (executorRef) {
            executorRef.store(executorConfig);
            this.executorConfig = executorConfig;
        }
    }

    public void setSeeds(int seeds) throws PersistenceException {

        synchronized (seedsRef) {
            seedsRef.store(seeds);
            this.seeds = seeds;
        }
    }

    public void setServers(int servers) throws PersistenceException {

        synchronized (serversRef) {
            serversRef.store(servers);
            this.servers = servers;
        }
    }

    public CassandraTaskExecutor createExecutor(String frameworkId,
                                                String id) {

        return CassandraTaskExecutor.create(
                frameworkId,
                id,
                executorConfig.getCommand(),
                executorConfig.getArguments(),
                executorConfig.getCpus(),
                executorConfig.getMemoryMb(),
                executorConfig.getDiskMb(),
                executorConfig.getHeapMb(),
                executorConfig.getApiPort(),
                executorConfig.getAdminPort(),
                Arrays.asList(executorConfig.getJreLocation(),
                        executorConfig.getExecutorLocation(),
                        executorConfig.getCassandraLocation()),
                executorConfig.getJavaHome()
        );
    }

    public CassandraDaemonTask createDaemon(String frameworkId,
                                            String slaveId,
                                            String hostname,
                                            String name,
                                            String role,
                                            String principal) {

        String unique = UUID.randomUUID().toString();

        String id = name + "_" + unique;

        String executor = name + "_" + unique + "_executor";


        return CassandraDaemonTask.create(
                id,
                slaveId,
                hostname,
                createExecutor(frameworkId, executor),
                name,
                role,
                principal,
                cassandraConfig.getCpus(),
                cassandraConfig.getMemoryMb(),
                cassandraConfig.getDiskMb(),
                cassandraConfig.mutable().setVolume(
                        cassandraConfig.getVolume().withId()).
                setApplication(cassandraConfig.getApplication()
                .toBuilder().setSeedProvider(
                                CassandraApplicationConfig
                                .createDcosSeedProvider(seedsUrl))
                        .build())
                        .build(),
                CassandraDaemonStatus.create(Protos.TaskState.TASK_STAGING,
                        id,
                        slaveId,
                        name,
                        Optional.empty(),
                        CassandraMode.STARTING)

        );
    }

    @Override
    public void start() throws Exception {

    }

    @Override
    public void stop() throws Exception {

    }
}