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

package com.hazelcast.jet.impl.execution;

import com.hazelcast.internal.util.concurrent.update.ConcurrentConveyor;
import com.hazelcast.internal.util.concurrent.update.OneToOneConcurrentArrayQueue;
import com.hazelcast.internal.util.concurrent.update.QueuedPipe;
import com.hazelcast.jet.JetException;
import com.hazelcast.jet.Punctuation;
import com.hazelcast.jet.impl.util.ProgressState;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;

import static com.hazelcast.jet.impl.execution.DoneItem.DONE_ITEM;
import static com.hazelcast.jet.impl.util.ProgressState.DONE;
import static com.hazelcast.jet.impl.util.ProgressState.MADE_PROGRESS;
import static org.junit.Assert.assertEquals;

public class ConcurrentInboundEdgeStreamTest {

    @Rule
    public ExpectedException exception = ExpectedException.none();
    private static final Object senderGone = new Object();
    private OneToOneConcurrentArrayQueue<Object> q1, q2;
    private ConcurrentInboundEdgeStream stream;

    @Before
    public void setUp() {
        q1 = new OneToOneConcurrentArrayQueue<>(128);
        q2 = new OneToOneConcurrentArrayQueue<>(128);
        //noinspection unchecked
        ConcurrentConveyor<Object> conveyor = ConcurrentConveyor.concurrentConveyor(senderGone, q1, q2);

        stream = new ConcurrentInboundEdgeStream(conveyor, 0, 0);
    }

    @Test
    public void when_twoEmittersOneDoneFirst_then_madeProgress() {
        ArrayList<Object> list = new ArrayList<>();
        q1.add(1);
        q1.add(2);
        q1.add(DONE_ITEM);
        q2.add(6);
        ProgressState progressState = stream.drainTo(list);
        assertEquals(Arrays.asList(1, 2, 6), list);
        assertEquals(MADE_PROGRESS, progressState);

        list.clear();
        q2.add(7);
        q2.add(DONE_ITEM);
        progressState = stream.drainTo(list);
        // emitter2 returned 7 and now both emitters are done
        assertEquals(Collections.singletonList(7), list);
        assertEquals(DONE, progressState);

        // both emitters are now done and made no progress since last call
        list.clear();
        progressState = stream.drainTo(list);
        assertEquals(0, list.size());
        assertEquals(ProgressState.WAS_ALREADY_DONE, progressState);
    }

    @Test
    public void when_twoEmittersDrainedAtOnce_then_firstCallDone() {
        ArrayList<Object> list = new ArrayList<>();
        q1.add(1);
        q1.add(2);
        q1.add(DONE_ITEM);
        q2.add(6);
        q2.add(DONE_ITEM);
        ProgressState progressState = stream.drainTo(list);

        // emitter1 returned 1 and 2; emitter2 returned 6
        // both are now done
        assertEquals(Arrays.asList(1, 2, 6), list);
        assertEquals(DONE, progressState);
    }

    @Test
    public void when_allEmittersInitiallyDone_then_firstCallDone() {
        ArrayList<Object> list = new ArrayList<>();
        q1.add(DONE_ITEM);
        q2.add(DONE_ITEM);
        ProgressState progressState = stream.drainTo(list);

        assertEquals(0, list.size());
        assertEquals(ProgressState.DONE, progressState);

        list.clear();
        progressState = stream.drainTo(list);
        assertEquals(0, list.size());
        assertEquals(ProgressState.WAS_ALREADY_DONE, progressState);
    }

    @Test
    public void when_oneEmitterWithNoProgress_then_noProgress() {
        ArrayList<Object> list = new ArrayList<>();
        q2.add(1);
        q2.add(DONE_ITEM);
        ProgressState progressState = stream.drainTo(list);

        assertEquals(Collections.singletonList(1), list);
        assertEquals(MADE_PROGRESS, progressState);
        // now emitter2 is done, emitter1 is not but has no progress
        list.clear();
        progressState = stream.drainTo(list);
        assertEquals(0, list.size());
        assertEquals(ProgressState.NO_PROGRESS, progressState);

        // now make emitter1 done, without returning anything
        q1.add(DONE_ITEM);

        list.clear();
        progressState = stream.drainTo(list);
        assertEquals(0, list.size());
        assertEquals(ProgressState.DONE, progressState);

        list.clear();
        progressState = stream.drainTo(list);
        assertEquals(0, list.size());
        assertEquals(ProgressState.WAS_ALREADY_DONE, progressState);
    }

    @Test
    public void when_punctuationFromAllEmittersInSingleDrain_then_emitAtPunc() {
        ArrayList<Object> list = new ArrayList<>();
        for (QueuedPipe<Object> q : Arrays.asList(q1, q2)) {
            q.add(0);
            q.add(1);
            q.add(new Punctuation(1));
            q.add(2);
            q.add(DONE_ITEM);
        }

        ProgressState progressState = stream.drainTo(list);
        assertEquals(Arrays.asList(0, 1, 0, 1, new Punctuation(1)), list);
        assertEquals(MADE_PROGRESS, progressState);

        list.clear();
        progressState = stream.drainTo(list);
        assertEquals(Arrays.asList(2, 2), list);
        assertEquals(DONE, progressState);
    }

    @Test
    public void when_punctuationFromSomeEmitter_then_dontEmit() {
        ArrayList<Object> list = new ArrayList<>();
        q1.add(0);
        q1.add(1);
        q1.add(new Punctuation(1));
        q1.add(2);
        q1.add(DONE_ITEM);
        q2.add(3);
        q2.add(4);
        ProgressState progressState = stream.drainTo(list);
        assertEquals(Arrays.asList(0, 1, 3, 4), list);
        assertEquals(MADE_PROGRESS, progressState);

        list.clear();
        q2.add(5);
        q2.add(6);
        q2.add(new Punctuation(1));
        q2.add(DONE_ITEM);
        progressState = stream.drainTo(list);
        assertEquals(Arrays.asList(5, 6, new Punctuation(1)), list);
        assertEquals(MADE_PROGRESS, progressState);

        list.clear();
        progressState = stream.drainTo(list);
        assertEquals(Collections.singletonList(2), list);
        assertEquals(DONE, progressState);
    }

    @Test
    public void when_punctuationsDontMatch_then_error() {
        Punctuation wm1 = new Punctuation(0);
        Punctuation wm2 = new Punctuation(1);

        ArrayList<Object> list = new ArrayList<>();
        q1.add(wm1);
        q1.add(DONE_ITEM);
        q2.add(wm2);
        q2.add(DONE_ITEM);

        exception.expect(JetException.class);
        exception.expectMessage("Punctuation emitted by one processor not equal to punctuation emitted by another one");
        exception.expectMessage(wm1.toString());
        exception.expectMessage(wm2.toString());
        stream.drainTo(list);
    }

    @Test
    public void when_oneWithPuncOtherDone_then_error() {
        ArrayList<Object> list = new ArrayList<>();
        Punctuation punc = new Punctuation(0);
        q1.add(punc);
        q1.add(DONE_ITEM);
        q2.add(DONE_ITEM);

        exception.expect(JetException.class);
        exception.expectMessage("Processor completed without first emitting a punctuation, that was already emitted by "
                + "another processor (punc=" + punc + ')');
        stream.drainTo(list);
    }

    @Test
    public void when_oneDoneOtherWithPunc_then_error() {
        ArrayList<Object> list = new ArrayList<>();
        Punctuation punc = new Punctuation(0);
        q1.add(DONE_ITEM);
        q2.add(punc);
        q2.add(DONE_ITEM);

        exception.expect(JetException.class);
        exception.expectMessage("Received a new punctuation after some processor already completed (punc=" + punc + ')');
        stream.drainTo(list);
    }
}
