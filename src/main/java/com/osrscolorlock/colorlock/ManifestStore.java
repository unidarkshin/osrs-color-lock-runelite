package com.osrscolorlock.colorlock;

import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemManager;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.CRC32;

@Singleton
public class ManifestStore
{
	private static final Logger log = Logger.getLogger(ManifestStore.class.getName());

	private final ClientThread clientThread;
	private final ColorLockGroupSync groupSync;
	private final ColorLockConfig config;

	private volatile Map<Integer, ManifestItem> byId = Collections.emptyMap();
	private volatile int manifestSchemaVersion = -1;
	private volatile String loadError;

	/** After first successful load; used with payload CRC to spot silent manifest drift. */
	private volatile int lastSnapshotItemCount = -1;
	private volatile int lastSnapshotSchemaVersion = Integer.MIN_VALUE;
	private volatile long lastManifestPayloadCrc = Long.MIN_VALUE;

	@Inject
	public ManifestStore(ClientThread clientThread, ColorLockGroupSync groupSync, ColorLockConfig config)
	{
		this.clientThread = clientThread;
		this.groupSync = groupSync;
		this.config = config;
	}

	public String lastLoadError()
	{
		return loadError;
	}

	public int manifestSchemaVersion()
	{
		return manifestSchemaVersion;
	}

	public int itemCount()
	{
		return byId.size();
	}

	public ManifestItem getManifestItem(int itemId)
	{
		return byId.get(itemId);
	}

	/**
	 * Row with non-empty usableColors for this id, or for {@link ItemManager#canonicalize(int)} when the
	 * variant id is missing from the manifest (e.g. charged gear).
	 */
	public ManifestItem getListedManifestItem(int itemId, ItemManager itemManager)
	{
		ManifestItem row = byId.get(itemId);
		if (hasListedColors(row))
		{
			return row;
		}
		if (itemManager != null)
		{
			int canon = itemManager.canonicalize(itemId);
			if (canon != itemId)
			{
				ManifestItem alt = byId.get(canon);
				if (hasListedColors(alt))
				{
					return alt;
				}
			}
		}
		return row;
	}

	public boolean isUsableByAssignment(int itemId, ColorLockColor assignment)
	{
		return ManifestRules.isUsableByAssignment(byId.get(itemId), assignment);
	}

	/** True when manifest lists usableColors and your lock is not among them. Unknown items are not restricted. */
	public boolean isRestrictedForAssignment(int itemId, ColorLockColor assignment)
	{
		return isRestrictedForAssignment(itemId, assignment, null);
	}

	/**
	 * Like {@link #isRestrictedForAssignment(int, ColorLockColor)} but resolves manifest rows via
	 * {@link #getListedManifestItem(int, ItemManager)} so variant ids match canonical entries.
	 */
	public boolean isRestrictedForAssignment(int itemId, ColorLockColor assignment, ItemManager itemManager)
	{
		Set<String> crew = groupSync.manifestRuleCrewFilter(config);
		ManifestItem row = itemManager != null ? getListedManifestItem(itemId, itemManager) : byId.get(itemId);
		return ManifestRules.isRestrictedForAssignment(row, assignment, crew);
	}

	private static boolean hasListedColors(ManifestItem row)
	{
		return row != null && !row.getUsableColors().isEmpty();
	}

	public void downloadAsync(ColorLockConfig config, Runnable onClientThreadFinish)
	{
		new Thread(() -> {
			try
			{
				loadBlocking(config.itemsUrl());
			}
			catch (IOException e)
			{
				loadError = e.getMessage();
				log.log(Level.WARNING, "color-lock manifest fetch failed", e);
			}
			clientThread.invokeLater(onClientThreadFinish);
		}, "osrs-color-lock-manifest").start();
	}

	void loadBlocking(String url) throws IOException
	{
		loadError = null;
		HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
		conn.setRequestMethod("GET");
		conn.setUseCaches(false);
		conn.setConnectTimeout(20_000);
		conn.setReadTimeout(120_000);
		conn.setRequestProperty("User-Agent", "osrs-color-lock-runelite/1.0 (https://github.com/unidarkshin/osrs-color-lock)");
		conn.setRequestProperty("Cache-Control", "no-cache");
		conn.setRequestProperty("Pragma", "no-cache");

		int httpCode = conn.getResponseCode();
		if (httpCode != HttpURLConnection.HTTP_OK)
		{
			throw new IOException("items manifest HTTP " + httpCode);
		}

		byte[] raw;
		try (InputStream in = conn.getInputStream())
		{
			raw = in.readAllBytes();
		}
		finally
		{
			conn.disconnect();
		}

		long crcVal = crc32(raw);
		List<ManifestItem> list = ManifestJson.readItemsUtf8(raw);

		HashMap<Integer, ManifestItem> next = new HashMap<>(Math.max(list.size() * 2, 64));
		int duplicateIds = 0;
		Integer schemaSeen = null;
		for (ManifestItem row : list)
		{
			if (schemaSeen == null && row.getSchemaVersionNumber() >= 0)
			{
				schemaSeen = row.getSchemaVersionNumber();
			}
			if (next.put(row.getId(), row) != null)
			{
				duplicateIds++;
			}
		}
		if (duplicateIds > 0)
		{
			log.warning("Items manifest ignored " + duplicateIds + " duplicate id row(s); last row per id wins");
		}

		byId = Collections.unmodifiableMap(next);
		manifestSchemaVersion = schemaSeen == null ? -1 : schemaSeen;

		boolean firstLoad = lastSnapshotItemCount < 0;
		boolean drift = !firstLoad && crcVal != lastManifestPayloadCrc;
		int n = byId.size();
		int s = manifestSchemaVersion;

		lastManifestPayloadCrc = crcVal;
		lastSnapshotItemCount = n;
		lastSnapshotSchemaVersion = s;

		if (drift)
		{
			log.info(() -> String.format(
				"Items manifest payload changed (%d entries, schema %d); rules updated from host",
				n,
				s
			));
		}
		if (firstLoad || drift)
		{
			log.info(() -> String.format(
				"color-lock manifest loaded %d items schemaVersion=%s",
				byId.size(),
				Integer.toString(manifestSchemaVersion)
			));
		}
		else
		{
			log.fine(() -> String.format(
				"manifest refresh unchanged (%d entries, crc32=%08x)",
				n,
				crcVal
			));
		}
	}

	private static long crc32(byte[] raw)
	{
		CRC32 c = new CRC32();
		c.update(raw);
		return c.getValue();
	}
}
