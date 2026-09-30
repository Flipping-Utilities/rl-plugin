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

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.util.Filepath;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;

/** Polls committed account files and notifies subscribers when a file is created or changed. */
@Slf4j
public class CacheUpdaterJob
{
	private final Filepath directory;
	private final ScheduledExecutorService executor;
	private final List<Consumer<String>> subscribers = new ArrayList<>();
	private Map<String, FileMetadata> snapshot;
	private ScheduledFuture<?> updateTask;
	private long generation;

	public CacheUpdaterJob(Filepath directory, ScheduledExecutorService executor)
	{
		this.directory = directory;
		this.executor = executor;
	}

	public synchronized void subscribe(Consumer<String> callback)
	{
		subscribers.add(callback);
	}

	public synchronized void start()
	{
		if (updateTask != null)
		{
			return;
		}

		snapshot = null;
		try
		{
			snapshot = readSnapshot();
		}
		catch (IOException | UncheckedIOException e)
		{
			log.warn("Unable to read account files; retrying on the next cache poll", e);
		}

		long currentGeneration = ++generation;
		updateTask = executor.scheduleWithFixedDelay(() -> poll(currentGeneration), 1, 1, TimeUnit.SECONDS);
	}

	public synchronized void stop()
	{
		if (updateTask != null)
		{
			++generation;
			updateTask.cancel(false);
			updateTask = null;
		}
	}

	private synchronized void poll(long currentGeneration)
	{
		if (updateTask == null || generation != currentGeneration)
		{
			return;
		}

		Map<String, FileMetadata> current;
		try
		{
			current = readSnapshot();
		}
		catch (IOException | UncheckedIOException e)
		{
			// Keep the last successful snapshot so a temporary failure cannot lose changes.
			log.warn("Unable to read account files; retrying on the next cache poll", e);
			return;
		}

		Map<String, FileMetadata> previous = snapshot;
		snapshot = current;
		if (previous == null)
		{
			return;
		}

		List<Consumer<String>> callbacks = new ArrayList<>(subscribers);
		for (Map.Entry<String, FileMetadata> file : current.entrySet())
		{
			if (file.getValue().matches(previous.get(file.getKey())))
			{
				continue;
			}
			for (Consumer<String> callback : callbacks)
			{
				if (generation != currentGeneration)
				{
					return;
				}
				try
				{
					callback.accept(file.getKey());
				}
				catch (RuntimeException e)
				{
					log.warn("Unable to update cache for {}", file.getKey(), e);
				}
			}
		}
	}

	private Map<String, FileMetadata> readSnapshot() throws IOException
	{
		Map<String, FileMetadata> current = new HashMap<>();
		try (Stream<Filepath> files = directory.walk(1))
		{
			Iterator<Filepath> iterator = files.iterator();
			while (iterator.hasNext())
			{
				Filepath file = iterator.next();
				String name = file.getFileName();
				if (!file.equals(directory) && isAccountFile(name) && file.isFile())
				{
					current.put(name, new FileMetadata(file.getLastModifiedTime(), file.size()));
				}
			}
		}
		return current;
	}

	private static boolean isAccountFile(String name)
	{
		return name.endsWith(".json")
			&& !name.endsWith(".backup.json") && !name.endsWith(".special.json");
	}

	private static final class FileMetadata
	{
		private final FileTime lastModified;
		private final long size;

		private FileMetadata(FileTime lastModified, long size)
		{
			this.lastModified = lastModified;
			this.size = size;
		}

		private boolean matches(FileMetadata other)
		{
			return other != null && size == other.size && lastModified.equals(other.lastModified);
		}
	}
}
