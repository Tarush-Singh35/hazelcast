/*
 * Copyright (c) 2008-2016, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.map.impl.operation;

import com.hazelcast.core.IMap;
import com.hazelcast.map.impl.mapstore.MapDataStore;
import com.hazelcast.map.impl.mapstore.writebehind.WriteBehindQueue;
import com.hazelcast.map.impl.mapstore.writebehind.WriteBehindStore;
import com.hazelcast.map.impl.mapstore.writebehind.entry.DelayedEntry;
import com.hazelcast.map.impl.recordstore.RecordStore;
import com.hazelcast.nio.ObjectDataInput;
import com.hazelcast.nio.ObjectDataOutput;
import com.hazelcast.spi.PartitionAwareOperation;
import com.hazelcast.spi.ReadonlyOperation;
import com.hazelcast.spi.WaitNotifyKey;
import com.hazelcast.spi.WaitSupport;

import java.io.IOException;

/**
 * {@link IMap#flush()} call waits the end of flush by using this operation.
 *
 * @see NotifyMapFlushOperation
 */
public class AwaitMapFlushOperation
        extends MapOperation implements PartitionAwareOperation, ReadonlyOperation, WaitSupport {

    /**
     * Flush will end after execution of this sequenced store-operation.
     */
    private long sequence;

    private transient WriteBehindStore store;

    public AwaitMapFlushOperation() {
    }

    public AwaitMapFlushOperation(String name, long sequence) {
        super(name);
        this.sequence = sequence;
    }

    @Override
    public void innerBeforeRun() throws Exception {
        super.innerBeforeRun();

        RecordStore recordStore = mapServiceContext.getRecordStore(getPartitionId(), name);

        MapDataStore mapDataStore = recordStore.getMapDataStore();
        if (!(mapDataStore instanceof WriteBehindStore)) {
            return;
        }

        store = (WriteBehindStore) mapDataStore;
    }

    @Override
    public void run() {
        // NOP
    }

    @Override
    public Object getResponse() {
        return Boolean.TRUE;
    }

    @Override
    public WaitNotifyKey getWaitKey() {
        return new MapFlushWaitNotifyKey(name, getPartitionId(), sequence);
    }

    @Override
    public boolean shouldWait() {
        WriteBehindQueue<DelayedEntry> writeBehindQueue = store.getWriteBehindQueue();
        int size = writeBehindQueue.size();
        if (size == 0) {
            return false;
        }

        DelayedEntry entry = writeBehindQueue.peek();
        long currentSequence = entry.getSequence();
        return entry != null && currentSequence <= this.sequence
                && size + currentSequence - 1 >= this.sequence;
    }

    @Override
    public void onWaitExpire() {
        sendResponse(false);
    }

    @Override
    protected void writeInternal(ObjectDataOutput out) throws IOException {
        super.writeInternal(out);
        out.writeLong(sequence);
    }

    @Override
    protected void readInternal(ObjectDataInput in) throws IOException {
        super.readInternal(in);
        sequence = in.readLong();
    }
}
