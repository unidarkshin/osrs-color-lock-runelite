package com.osrscolorlock.colorlock;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Collections;
import java.util.List;

import net.runelite.client.RuneLite;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Persists the last successful items manifest under {@link RuneLite#RUNELITE_DIR}/color-lock-helper/. */
final class ManifestDiskCache
{
	private static final Logger log = LoggerFactory.getLogger(ManifestDiskCache.class);
	private static final int CACHE_FORMAT_VERSION = 1;

	private final Gson gson;
	private final Path cacheFile;

	ManifestDiskCache(Gson gson)
	{
		this(gson, defaultCachePath());
	}

	ManifestDiskCache(Gson gson, Path cacheFile)
	{
		this.gson = gson;
		this.cacheFile = cacheFile;
	}

	static Path defaultCachePath()
	{
		return Paths.get(RuneLite.RUNELITE_DIR.getAbsolutePath(), "color-lock-helper", "items-manifest.json");
	}

	Snapshot load()
	{
		if (!Files.isRegularFile(cacheFile))
		{
			return null;
		}
		try (Reader reader = Files.newBufferedReader(cacheFile, StandardCharsets.UTF_8))
		{
			Snapshot snap = gson.fromJson(reader, Snapshot.class);
			if (snap == null || snap.items == null || snap.items.isEmpty())
			{
				return null;
			}
			return snap;
		}
		catch (IOException | RuntimeException e)
		{
			log.warn("Could not read color-lock manifest disk cache at {}", cacheFile, e);
			return null;
		}
	}

	void save(String sourceUrl, int schemaVersion, List<ManifestItem> items)
	{
		if (items == null || items.isEmpty())
		{
			return;
		}
		Snapshot snap = new Snapshot();
		snap.cacheFormatVersion = CACHE_FORMAT_VERSION;
		snap.savedAt = Instant.now().toString();
		snap.sourceUrl = sourceUrl == null ? "" : sourceUrl;
		snap.schemaVersion = schemaVersion;
		snap.items = items;

		Path parent = cacheFile.getParent();
		Path tmp = cacheFile.resolveSibling(cacheFile.getFileName().toString() + ".tmp");
		try
		{
			if (parent != null)
			{
				Files.createDirectories(parent);
			}
			try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8))
			{
				gson.toJson(snap, writer);
			}
			try
			{
				Files.move(tmp, cacheFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			}
			catch (AtomicMoveNotSupportedException e)
			{
				Files.move(tmp, cacheFile, StandardCopyOption.REPLACE_EXISTING);
			}
			log.debug("Wrote color-lock manifest disk cache ({} items, schema {})", items.size(), schemaVersion);
		}
		catch (IOException e)
		{
			log.warn("Could not write color-lock manifest disk cache at {}", cacheFile, e);
			try
			{
				Files.deleteIfExists(tmp);
			}
			catch (IOException ignored)
			{
			}
		}
	}

	static final class Snapshot
	{
		@SerializedName("cacheFormatVersion")
		int cacheFormatVersion;

		@SerializedName("savedAt")
		String savedAt;

		@SerializedName("sourceUrl")
		String sourceUrl;

		@SerializedName("schemaVersion")
		int schemaVersion;

		@SerializedName("items")
		List<ManifestItem> items = Collections.emptyList();
	}
}
