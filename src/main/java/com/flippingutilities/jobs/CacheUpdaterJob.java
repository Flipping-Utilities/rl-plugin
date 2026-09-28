/*
 * Copyright (c) 2020, Belieal <https://github.com/Belieal>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.flippingutilities.jobs;

import com.flippingutilities.db.TradePersister;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Checks the two storage files without walking account histories or retaining a WatchService. */
@Slf4j
public class CacheUpdaterJob {
    private final Path directory;
    private final List<Consumer<String>> subscribers = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(
        new ThreadFactoryBuilder().setNameFormat("flipping-json-watch-%d").setDaemon(true).build());
    private ScheduledFuture<?> task;
    private Object previous;

    public CacheUpdaterJob() {
        this(TradePersister.PARENT_DIRECTORY.toPath().resolve("json-v2"));
    }

    CacheUpdaterJob(Path directory) {
        this.directory = directory;
    }

    public void subscribe(Consumer<String> callback) { subscribers.add(callback); }

    public synchronized void start() {
        if (task == null && !executor.isShutdown()) {
            task = executor.scheduleWithFixedDelay(this::updateCacheRealTime, 1, 1, TimeUnit.SECONDS);
        }
    }

    public synchronized void stop() {
        if (task != null) task.cancel(false);
        executor.shutdown();
    }

    public void updateCacheRealTime() {
        try {
            Object current = Arrays.asList(stamp(directory.resolve("checkpoint.json")),
                stamp(directory.resolve("journal.jsonl")));
            if (!Objects.equals(previous, current)) {
                previous = current;
                subscribers.forEach(callback -> callback.accept("journal.jsonl"));
            }
        } catch (IOException | RuntimeException failure) {
            log.warn("Could not check JSON storage for updates; will retry", failure);
        }
    }

    private Object stamp(Path path) throws IOException {
        if (!Files.exists(path)) return null;
        BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
        return Arrays.asList(attrs.fileKey(), attrs.size(), attrs.lastModifiedTime());
    }
}
