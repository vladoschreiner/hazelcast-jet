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

package com.hazelcast.jet.windowing.example;

import com.hazelcast.jet.AbstractProcessor;
import com.hazelcast.jet.DAG;
import com.hazelcast.jet.Jet;
import com.hazelcast.jet.JetInstance;
import com.hazelcast.jet.Vertex;
import com.hazelcast.jet.config.InstanceConfig;
import com.hazelcast.jet.config.JetConfig;
import com.hazelcast.jet.stream.IStreamMap;

import javax.annotation.Nonnull;
import java.util.HashMap;
import java.util.Map;

import static com.hazelcast.jet.Edge.between;
import static com.hazelcast.jet.Edge.from;
import static com.hazelcast.jet.KeyExtractors.entryKey;
import static com.hazelcast.jet.Partitioner.HASH_CODE;
import static com.hazelcast.jet.Processors.readMap;
import static com.hazelcast.jet.windowing.example.CombineFramesP.combineFrames;
import static com.hazelcast.jet.windowing.example.GroupByFrameP.groupByFrame;
import static com.hazelcast.jet.windowing.example.SnapshottingCollectors.mapping;
import static com.hazelcast.jet.windowing.example.SnapshottingCollectors.summingLong;
import static java.lang.Runtime.getRuntime;

public class TradeMonitor {

    private static final Map<String, Integer> TICKERS = new HashMap<String, Integer>() {{
        put("GOOG", 10000);
        put("AAPL", 20000);
        put("FB", 15000);
    }};

    public static void main(String[] args) throws Exception {
        System.setProperty("hazelcast.logging.type", "log4j");
        try {
            JetConfig cfg = new JetConfig();
            final int defaultLocalParallelism = Math.max(1, getRuntime().availableProcessors() / 2);
            cfg.setInstanceConfig(new InstanceConfig().setCooperativeThreadCount(defaultLocalParallelism));

            Jet.newJetInstance();
            JetInstance jet = Jet.newJetInstance(cfg);

            IStreamMap<String, Integer> initial = jet.getMap("initial");
            initial.putAll(TICKERS);

            DAG dag = new DAG();
            Vertex tickerSource = dag.newVertex("ticker-source", readMap(initial.getName()));
            Vertex generateEvents = dag.newVertex("generate-events", () -> new TradeGeneratorP(100));
            Vertex peek = dag.newVertex("peek", PeekP::new);
            Vertex groupByFrame = dag.newVertex("group-by-frame",
                    groupByFrame(4, t -> System.currentTimeMillis(),
                            ts -> ts / 1_000, mapping(Trade::getQuantity, summingLong())));
            Vertex combineFrames = dag.newVertex("combine-frames",
                    combineFrames(summingLong(), defaultLocalParallelism * 2));

            dag.edge(between(tickerSource, generateEvents).partitioned(entryKey()))
               .edge(between(generateEvents, groupByFrame).partitioned(Trade::getTicker, HASH_CODE))
               .edge(between(groupByFrame, combineFrames.localParallelism(1)).allToOne().distributed())

               .edge(from(generateEvents, 1).to(peek))
               .edge(from(groupByFrame, 1).to(peek, 1));

            jet.newJob(dag).execute().get();
        } finally {
            Jet.shutdownAll();
        }
    }

    public static class PeekP extends AbstractProcessor {
        @Override
        protected boolean tryProcess(int ordinal, @Nonnull Object item) throws Exception {
            getLogger().info(item.toString());
            return true;
        }
    }
}
