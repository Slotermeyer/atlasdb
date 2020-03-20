/*
 * (c) Copyright 2018 Palantir Technologies Inc. All rights reserved.
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

package com.palantir.atlasdb.autobatch;

import java.io.Closeable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Maps;
import com.google.common.util.concurrent.AsyncFunction;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.lmax.disruptor.EventHandler;
import com.lmax.disruptor.EventTranslator;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.TimeoutException;
import com.lmax.disruptor.dsl.Disruptor;
import com.palantir.logsafe.Preconditions;

/**
 * While this class is public, it shouldn't be used as API outside of AtlasDB because we don't guarantee we won't break
 * it.
 */
public final class DisruptorAutobatcher<T, R>
        implements AsyncFunction<T, R>, Function<T, ListenableFuture<R>>, Closeable {

    private static final Logger log = LoggerFactory.getLogger(DisruptorAutobatcher.class);

    /*
        By memoizing thread factories per loggable purpose, the thread names are numbered uniquely for multiple
        instances of the same autobatcher function.
     */
    private static final ConcurrentMap<String, ThreadFactory> threadFactories = Maps.newConcurrentMap();

    private static ThreadFactory threadFactory(String safeLoggablePurpose) {
        return threadFactories.computeIfAbsent(safeLoggablePurpose, DisruptorAutobatcher::createThreadFactory);
    }

    private static ThreadFactory createThreadFactory(String safeLoggablePurpose) {
        String namePrefix = String.format("autobatcher.%s-", safeLoggablePurpose);
        return new ThreadFactoryBuilder()
                .setDaemon(true)
                .setNameFormat(namePrefix + "%d")
                .build();
    }

    private final Disruptor<DefaultBatchElement<T, R>> disruptor;
    private final RingBuffer<DefaultBatchElement<T, R>> buffer;
    private volatile boolean closed = false;

    DisruptorAutobatcher(
            Disruptor<DefaultBatchElement<T, R>> disruptor,
            RingBuffer<DefaultBatchElement<T, R>> buffer) {
        this.disruptor = disruptor;
        this.buffer = buffer;
    }

    @Override
    public ListenableFuture<R> apply(T argument) {
        Preconditions.checkState(!closed, "Autobatcher is already shut down");
        SettableFuture<R> result = SettableFuture.create();
        buffer.publishEvent((refresh, sequence) -> {
            refresh.result = result;
            refresh.argument = argument;
        });
        return result;
    }

    public List<ListenableFuture<R>> applyBatch(List<T> arguments) {
        Preconditions.checkState(!closed, "Autobatcher is already shut down");
        List<ListenableFuture<R>> results = new ArrayList<>();
        buffer.publishEvents((EventTranslator<DefaultBatchElement<T, R>>[]) arguments.stream().map(argument -> {
            SettableFuture<R> result = SettableFuture.create();
            EventTranslator<DefaultBatchElement<T, R>> translator = (refresh, sequence) -> {
                refresh.result = result;
                refresh.argument = argument;
            };
            results.add(result);
            return translator;
        }).collect(Collectors.toList()).toArray());

        return results;
    }

    @Override
    public void close() {
        closed = true;
        try {
            disruptor.shutdown(10, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            log.warn("Disruptor took more than 10 seconds to shutdown. "
                    + "Ensure that handlers aren't uninterruptibly blocking and ensure that they are closed.", e);
        }
    }

    private static final class DefaultBatchElement<T, R> implements BatchElement<T, R> {
        private T argument;
        private SettableFuture<R> result;

        @Override
        public T argument() {
            return argument;
        }

        @Override
        public SettableFuture<R> result() {
            return result;
        }

    }

    static <T, R> DisruptorAutobatcher<T, R> create(
            EventHandler<BatchElement<T, R>> eventHandler,
            int bufferSize,
            String safeLoggablePurpose) {
        Disruptor<DefaultBatchElement<T, R>> disruptor =
                new Disruptor<>(DefaultBatchElement::new, bufferSize, threadFactory(safeLoggablePurpose));
        disruptor.handleEventsWith(eventHandler);
        disruptor.start();
        return new DisruptorAutobatcher<>(disruptor, disruptor.getRingBuffer());
    }
}
