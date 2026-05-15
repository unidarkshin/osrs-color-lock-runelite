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
import java.util.concurrent.ScheduledExecutorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.zip.CRC32;

@Singleton
public class ManifestStore
{
	private static final Logger log = LoggerFactory.getLogger(ManifestStore.class);

	private final ClientThread clientThread;
	private final ColorLockGroupSync groupSync;
	private final ColorLockConfig config;
	private final ScheduledExecutorService executor;

	private volatile Map<Integer, ManifestItem> byId = Collections.emptyMap();
	private volatile int manifestSchemaVersion = -1;
	private volatile String loadError;
	/** Absolute URL passed to {@link #loadBlocking}; used to detect hub items pointer changes without refetching. */
	private volatile String lastLoadedManifestUrl;

	/** After first successful load; used with payload CRC to spot silent manifest drift. */
	private volatile int lastSnapshotItemCount = -1;
	private volatile long lastManifestPayloadCrc = Long.MIN_VALUE;

	@Inject
	public ManifestStore(ClientThread clientThread, ColorLockGroupSync groupSync, ColorLockConfig config,
		ScheduledExecutorService executor)
	{
		this.clientThread = clientThread;
		this.groupSync = groupSync;
		this.config = config;
		this.executor = executor;
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

	/**
	 * True when the manifest lists {@code usableColors} for this id (or its canonical alias) and the
	 * supplied {@code assignment} is not in the set. Unknown items are not restricted.
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

	public String lastLoadedManifestUrl()
	{
		return lastLoadedManifestUrl;
	}

	public void downloadAsync(Runnable onClientThreadFinish)
	{
		final String primary = groupSync.getEffectiveItemsManifestUrl(config);
		final String fallbackV1 = groupSync.getDefaultItemsManifestUrl();
		final String fallbackLegacy = groupSync.getLegacyItemsManifestUrl();
		executor.execute(() -> {
			log.info("color-lock manifest fetching {}", primary);
			if (tryLoadOrLog(primary))
			{
				clientThread.invokeLater(onClientThreadFinish);
				return;
			}
			if (fallbackV1 != null && !fallbackV1.isEmpty() && !fallbackV1.equals(primary))
			{
				log.warn("color-lock manifest fetch failed at {} - retrying default {}", primary, fallbackV1);
				groupSync.clearItemsUrlOverride();
				if (tryLoadOrLog(fallbackV1))
				{
					clientThread.invokeLater(onClientThreadFinish);
					return;
				}
			}
			if (fallbackLegacy != null && !fallbackLegacy.isEmpty()
				&& !fallbackLegacy.equals(primary) && !fallbackLegacy.equals(fallbackV1))
			{
				log.warn("color-lock manifest fetch retrying deprecated {}", fallbackLegacy);
				tryLoadOrLog(fallbackLegacy);
			}
			clientThread.invokeLater(onClientThreadFinish);
		});
	}

	private boolean tryLoadOrLog(String url)
	{
		try
		{
			loadBlocking(url);
			loadError = null;
			return true;
		}
		catch (IOException e)
		{
			loadError = e.getMessage();
			log.warn("color-lock manifest fetch failed at {}", url, e);
			return false;
		}
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
			log.warn("Items manifest ignored {} duplicate id row(s); last row per id wins", duplicateIds);
		}

		byId = Collections.unmodifiableMap(next);
		manifestSchemaVersion = schemaSeen == null ? -1 : schemaSeen;
		lastLoadedManifestUrl = url;

		boolean firstLoad = lastSnapshotItemCount < 0;
		boolean drift = !firstLoad && crcVal != lastManifestPayloadCrc;
		int n = byId.size();
		int s = manifestSchemaVersion;

		lastManifestPayloadCrc = crcVal;
		lastSnapshotItemCount = n;

		if (drift)
		{
			log.info("Items manifest payload changed ({} entries, schema {}); rules updated from host", n, s);
		}
		if (firstLoad || drift)
		{
			log.info("color-lock manifest loaded {} items schemaVersion={}", byId.size(), manifestSchemaVersion);
		}
		else
		{
			log.debug("manifest refresh unchanged ({} entries, crc32={})", n, String.format("%08x", crcVal));
		}
	}

	private static long crc32(byte[] raw)
	{
		CRC32 c = new CRC32();
		c.update(raw);
		return c.getValue();
	}
}
