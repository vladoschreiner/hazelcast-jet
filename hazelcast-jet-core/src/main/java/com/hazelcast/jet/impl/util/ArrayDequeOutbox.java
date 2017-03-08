/*
 * Copyright (c) 2008-2017, Hazelcast, Inc. All Rights Reserved.
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

package com.hazelcast.jet.impl.util;

import com.hazelcast.jet.Outbox;

import javax.annotation.Nonnull;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Queue;

/**
 * Implements {@code Outbox} with an {@link ArrayDeque}.
 */
public final class ArrayDequeOutbox implements Outbox {

    private final ArrayDeque<Object>[] buckets;
    private final int[] outboxLimits;

    private boolean didAdd;

    public ArrayDequeOutbox(int size, int[] outboxLimits) {
        this.outboxLimits = outboxLimits.clone();
        this.buckets = new ArrayDeque[size];
        Arrays.setAll(buckets, i -> new ArrayDeque());
    }

    @Override
    public int bucketCount() {
        return buckets.length;
    }

    @Override
    public void add(int ordinal, @Nonnull Object item) {
        didAdd = true;
        if (ordinal != -1) {
            buckets[ordinal].add(item);
        } else {
            for (ArrayDeque<Object> queue : buckets) {
                queue.add(item);
            }
        }
    }

    @Override
    public boolean hasReachedLimit(int ordinal) {
        if (ordinal != -1) {
            return buckets[ordinal].size() >= outboxLimits[ordinal];
        }
        for (int i = 0; i < buckets.length; i++) {
            if (buckets[i].size() >= outboxLimits[i]) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String toString() {
        return Arrays.toString(buckets);
    }

    // Private API

    public Queue<Object> queueWithOrdinal(int ordinal) {
        return buckets[ordinal];
    }

    public void resetDidAdd() {
        didAdd = false;
    }

    public boolean didAdd() {
        return didAdd;
    }
}
