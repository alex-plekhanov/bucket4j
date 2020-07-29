package io.github.bucket4j.distributed.proxy;

import io.github.bucket4j.Bucket;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.BucketExceptions;
import io.github.bucket4j.distributed.AsyncBucket;
import io.github.bucket4j.distributed.remote.CommandResult;
import io.github.bucket4j.distributed.remote.RemoteCommand;
import io.github.bucket4j.distributed.remote.commands.GetConfigurationCommand;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

public abstract class AbstractBackend<K> implements Backend<K> {

    private static final RecoveryStrategy DEFAULT_RECOVERY_STRATEGY = RecoveryStrategy.RECONSTRUCT;
    private static final RequestOptimizer DEFAULT_REQUEST_OPTIMIZER = RequestOptimizer.NONE_OPTIMIZED;

    private AsyncBackend<K> asyncView = new AsyncBackend<K>() {
        @Override
        public CompletableFuture<Optional<BucketConfiguration>> getProxyConfiguration(K key) {
            GetConfigurationCommand cmd = new GetConfigurationCommand();
            return executeAsync(key, cmd).thenApply(result -> {
                if (result.isBucketNotFound()) {
                    return Optional.empty();
                }
                return Optional.of(result.getData());
            });
        }

        @Override
        public RemoteAsyncBucketBuilder<K> builder() {
            return new DefaultAsyncRemoteBucketBuilder();
        }
    };

    @Override
    public AsyncBackend<K> asAsync() {
        if (!isAsyncModeSupported()) {
            throw BucketExceptions.asyncModeIsNotSupported();
        }
        return asyncView;
    }

    @Override
    public RemoteBucketBuilder<K> builder() {
        return new DefaultRemoteBucketBuilder();
    }

    @Override
    public Optional<BucketConfiguration> getProxyConfiguration(K key) {
        GetConfigurationCommand cmd = new GetConfigurationCommand();
        CommandResult<BucketConfiguration> result = this.execute(key, cmd);
        if (result.isBucketNotFound()) {
            return Optional.empty();
        }
        return Optional.of(result.getData());
    }

    private class DefaultAsyncRemoteBucketBuilder implements RemoteAsyncBucketBuilder<K> {

        private RecoveryStrategy recoveryStrategy = DEFAULT_RECOVERY_STRATEGY;
        private RequestOptimizer asyncRequestOptimizer = DEFAULT_REQUEST_OPTIMIZER;

        @Override
        public DefaultAsyncRemoteBucketBuilder withRecoveryStrategy(RecoveryStrategy recoveryStrategy) {
            this.recoveryStrategy = Objects.requireNonNull(recoveryStrategy);
            return this;
        }

        @Override
        public DefaultAsyncRemoteBucketBuilder withRequestOptimizer(RequestOptimizer requestOptimizer) {
            this.asyncRequestOptimizer = Objects.requireNonNull(requestOptimizer);
            return this;
        }

        @Override
        public AsyncBucket buildProxy(K key, BucketConfiguration configuration) {
            if (configuration == null) {
                throw BucketExceptions.nullConfiguration();
            }
            return buildProxy(key, () -> CompletableFuture.completedFuture(configuration));
        }

        @Override
        public AsyncBucket buildProxy(K key, Supplier<CompletableFuture<BucketConfiguration>> configurationSupplier) {
            if (configurationSupplier == null) {
                throw BucketExceptions.nullConfigurationSupplier();
            }

            AsyncCommandExecutor commandExecutor = new AsyncCommandExecutor() {
                @Override
                public <T> CompletableFuture<CommandResult<T>> executeAsync(RemoteCommand<T> command) {
                    return AbstractBackend.this.executeAsync(key, command);
                }

                @Override
                public CompletableFuture<Void> flushAsync() {
                    return CompletableFuture.completedFuture(null);
                }
            };
            commandExecutor = asyncRequestOptimizer.optimize(commandExecutor);

            return new AsyncBucketProxy(commandExecutor, recoveryStrategy, configurationSupplier);
        }

    }

    private class DefaultRemoteBucketBuilder implements RemoteBucketBuilder<K> {

        private RecoveryStrategy recoveryStrategy = DEFAULT_RECOVERY_STRATEGY;
        private RequestOptimizer requestOptimizer = DEFAULT_REQUEST_OPTIMIZER;

        @Override
        public RemoteBucketBuilder<K> withRecoveryStrategy(RecoveryStrategy recoveryStrategy) {
            this.recoveryStrategy = Objects.requireNonNull(recoveryStrategy);
            return this;
        }

        @Override
        public RemoteBucketBuilder<K> withRequestOptimizer(RequestOptimizer requestOptimizer) {
            this.requestOptimizer = Objects.requireNonNull(requestOptimizer);
            return this;
        }

        @Override
        public Bucket buildProxy(K key, BucketConfiguration configuration) {
            if (configuration == null) {
                throw BucketExceptions.nullConfiguration();
            }
            return buildProxy(key, () -> configuration);
        }

        @Override
        public Bucket buildProxy(K key, Supplier<BucketConfiguration> configurationSupplier) {
            if (configurationSupplier == null) {
                throw BucketExceptions.nullConfigurationSupplier();
            }

            CommandExecutor commandExecutor = new CommandExecutor() {
                @Override
                public <T> CommandResult<T> execute(RemoteCommand<T> command) {
                    return AbstractBackend.this.execute(key, command);
                }

                @Override
                public void flush() {
                    // do nothing
                }
            };
            commandExecutor = requestOptimizer.optimize(commandExecutor);

            return new BucketProxy(configurationSupplier, commandExecutor, recoveryStrategy);
        }

    }

    // TODO javadocs
    abstract protected <T> CommandResult<T> execute(K key, RemoteCommand<T> command);

    // TODO javadocs
    abstract protected <T> CompletableFuture<CommandResult<T>> executeAsync(K key, RemoteCommand<T> command);

}
