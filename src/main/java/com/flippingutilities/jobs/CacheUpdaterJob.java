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
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Watches account files and notifies subscribers until this job is stopped. */
@Slf4j
public class CacheUpdaterJob
{
	private final ScheduledExecutorService executor;
	private final Path directory;
	private final List<Consumer<String>> subscribers = new CopyOnWriteArrayList<>();
	private final Map<String, Long> lastEvents = new HashMap<>();
	private volatile boolean stopped;
	private Future<?> realTimeUpdateTask;
	private int failureCount;

	public CacheUpdaterJob()
	{
		this(TradePersister.PARENT_DIRECTORY.toPath(), Executors.newSingleThreadScheduledExecutor());
	}

	CacheUpdaterJob(Path directory, ScheduledExecutorService executor)
	{
		this.directory = directory;
		this.executor = executor;
	}

	public void subscribe(Consumer<String> callback)
	{
		subscribers.add(callback);
	}

	public synchronized void start()
	{
		if (!stopped && realTimeUpdateTask == null)
		{
			realTimeUpdateTask = executor.schedule(this::updateCacheRealTime, 1000, TimeUnit.MILLISECONDS);
		}
	}

	/** Terminal and idempotent, including before start or during a pending retry. */
	public synchronized void stop()
	{
		stopped = true;
		if (realTimeUpdateTask != null)
		{
			realTimeUpdateTask.cancel(true);
		}
		executor.shutdownNow();
	}

	public void updateCacheRealTime()
	{
		if (stopped)
		{
			return;
		}
		try (WatchService watchService = directory.getFileSystem().newWatchService())
		{
			directory.register(watchService, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY);
			while (!stopped)
			{
				WatchKey key = watchService.take();
				for (WatchEvent<?> event : key.pollEvents())
				{
					// Overflow has no filename; it must not terminate the watcher.
					if (event.kind() == StandardWatchEventKinds.OVERFLOW)
					{
						log.warn("Account directory watcher overflowed; some file changes may have been missed");
						continue;
					}
					String fileName = event.context().toString();
					if (stopped || isDuplicateEvent(fileName))
					{
						continue;
					}
					for (Consumer<String> subscriber : subscribers)
					{
						if (stopped)
						{
							break;
						}
						try
						{
							subscriber.accept(fileName);
						}
						catch (RuntimeException e)
						{
							log.warn("Account file change subscriber failed for {}", fileName, e);
						}
					}
				}
				if (!key.reset())
				{
					throw new IOException("Account directory is no longer watchable: " + directory);
				}
				failureCount = 0;
			}
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
			retryAfterFailure(e);
		}
		catch (IOException | RuntimeException e)
		{
			retryAfterFailure(e);
		}
	}

	private synchronized void retryAfterFailure(Exception failure)
	{
		if (stopped)
		{
			return;
		}
		log.warn("Account directory watcher failed", failure);
		if (++failureCount <= 2)
		{
			realTimeUpdateTask = executor.schedule(this::updateCacheRealTime, 1000, TimeUnit.MILLISECONDS);
		}
		else
		{
			log.warn("Account directory watcher exceeded its retry limit");
			executor.shutdown();
		}
	}

	private boolean isDuplicateEvent(String fileName)
	{
		long lastModified = directory.resolve(fileName).toFile().lastModified();
		Long previous = lastEvents.get(fileName);
		if (previous != null && Math.abs(lastModified - previous) < 5)
		{
			return true;
		}
		lastEvents.put(fileName, lastModified);
		return false;
	}
}
