package org.apache.cassandra.db;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

import org.apache.cassandra.SchemaLoader;
import org.apache.cassandra.db.compaction.CompactionManager;
import org.apache.cassandra.db.compaction.SizeTieredCompactionStrategy;
import org.apache.cassandra.db.filter.QueryPath;
import org.apache.cassandra.utils.ByteBufferUtil;
import org.apache.cassandra.utils.FBUtilities;

import static junit.framework.Assert.assertEquals;

public class HintedHandOffTest extends SchemaLoader
{

    public static final String TABLE4 = "Keyspace4";
    public static final String STANDARD1_CF = "Standard1";
    public static final String COLUMN1 = "column1";

    // Test compaction of hints column family. It shouldn't remove all columns on compaction.
    @Test
    public void testCompactionOfHintsCF() throws Exception
    {
        // prepare hints column family
        Table systemTable = Table.open("system");
        ColumnFamilyStore hintStore = systemTable.getColumnFamilyStore(SystemTable.HINTS_CF);
        hintStore.clearUnsafe();
        hintStore.metadata.gcGraceSeconds(36000); // 10 hours
        hintStore.setCompactionStrategyClass(SizeTieredCompactionStrategy.class.getCanonicalName());
        hintStore.disableAutoCompaction();

        // insert 1 hint
        RowMutation rm = new RowMutation(TABLE4, ByteBufferUtil.bytes(1));
        rm.add(new QueryPath(STANDARD1_CF,
                             null,
                             ByteBufferUtil.bytes(String.valueOf(COLUMN1))),
               ByteBufferUtil.EMPTY_BYTE_BUFFER,
               System.currentTimeMillis());

        RowMutation.hintFor(rm, UUID.randomUUID()).apply();

        // flush data to disk
        hintStore.forceBlockingFlush();
        assertEquals(1, hintStore.getSSTables().size());

        // submit compaction
        FBUtilities.waitOnFuture(HintedHandOffManager.instance.compact());
        while (CompactionManager.instance.getPendingTasks() > 0 || CompactionManager.instance.getActiveCompactions() > 0)
            TimeUnit.SECONDS.sleep(1);

        // single row should not be removed because of gc_grace_seconds
        // is 10 hours and there are no any tombstones in sstable
        assertEquals(1, hintStore.getSSTables().size());
    }
}
