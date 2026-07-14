package com.fleet.vts.analytics.config;

import org.apache.kafka.streams.state.RocksDBConfigSetter;
import org.rocksdb.BlockBasedTableConfig;
import org.rocksdb.Cache;
import org.rocksdb.LRUCache;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.WriteBufferManager;

import java.util.Map;

/**
 * Bounds the native (off-heap) memory of the Kafka Streams state stores.
 *
 * <p>By default every RocksDB instance gets its own block cache and write buffers.
 * With 5 stores across 24 partitions that is ~120 instances, so the defaults scale
 * into hundreds of MB of native memory that no {@code -Xmx} can cap. Here a single
 * LRU cache and a single write-buffer manager are shared by ALL instances, so total
 * RocksDB memory stays bounded regardless of the partition count.
 *
 * <p>The shared objects are created lazily on the first {@link #setConfig} call —
 * not in a static field initialiser — because building an {@link LRUCache} is a JNI
 * call and the RocksDB native library is not guaranteed to be loaded at class-load
 * time (doing it eagerly throws {@code UnsatisfiedLinkError} during startup).
 */
public class BoundedRocksDBConfig implements RocksDBConfigSetter {

    private static final long BLOCK_CACHE_BYTES = 32L * 1024 * 1024;   // 32 MB, shared
    private static final long WRITE_BUFFER_BYTES = 16L * 1024 * 1024;  // 16 MB, shared
    private static final long MEMTABLE_BYTES = 4L * 1024 * 1024;       // per store (default 64 MB)

    private static Cache cache;
    private static WriteBufferManager writeBuffers;

    private static synchronized void ensureShared() {
        if (cache == null) {
            RocksDB.loadLibrary();   // idempotent; must precede any JNI-backed object
            cache = new LRUCache(BLOCK_CACHE_BYTES);
            writeBuffers = new WriteBufferManager(WRITE_BUFFER_BYTES, cache);
        }
    }

    @Override
    public void setConfig(String storeName, Options options, Map<String, Object> configs) {
        ensureShared();

        BlockBasedTableConfig table = (BlockBasedTableConfig) options.tableFormatConfig();
        table.setBlockCache(cache);
        // Index/filter blocks are charged to the shared cache instead of growing freely.
        table.setCacheIndexAndFilterBlocks(true);
        options.setTableFormatConfig(table);

        options.setWriteBufferManager(writeBuffers);
        options.setWriteBufferSize(MEMTABLE_BYTES);
        options.setMaxWriteBufferNumber(2);
    }

    @Override
    public void close(String storeName, Options options) {
        // cache / writeBuffers are shared across every store — closing them here would
        // pull the rug out from under the others. They live for the JVM's lifetime.
    }
}
