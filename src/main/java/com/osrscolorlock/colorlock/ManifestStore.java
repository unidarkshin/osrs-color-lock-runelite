package com.osrscolorlock.colorlock;

import com.google.gson.Gson;
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
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.concurrent.ScheduledExecutorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.zip.CRC32;

@Singleton
public class ManifestStore
{
	private static final Logger log = LoggerFactory.getLogger(ManifestStore.class);
	private static final int MANIFEST_HTTP_RETRIES = 3;

	private final Gson gson;
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
	public ManifestStore(Gson gson, ClientThread clientThread, ColorLockGroupSync groupSync, ColorLockConfig config,
		ScheduledExecutorService executor)
	{
		this.gson = gson;
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
		final String fallbackV1 = groupSync.getDefaultItemsManifestUrl(config);
		final String fallbackLegacy = groupSync.getLegacyItemsManifestUrl(config);
		executor.execute(() -> {
			log.debug("color-lock manifest fetching {}", primary);
			boolean urlChanged = primary != null && !primary.equals(lastLoadedManifestUrl);
			if (urlChanged)
			{
				lastManifestPayloadCrc = Long.MIN_VALUE;
			}
			String loadedFrom = null;
			if (tryLoadOrLogWithRetries(primary))
			{
				loadedFrom = primary;
			}
			else if (fallbackV1 != null && !fallbackV1.isEmpty() && !fallbackV1.equals(primary))
			{
				log.warn("color-lock manifest fetch failed at {} - retrying default {}", primary, fallbackV1);
				groupSync.clearItemsUrlOverride();
				if (tryLoadOrLogWithRetries(fallbackV1))
				{
					loadedFrom = fallbackV1;
				}
			}
			if (loadedFrom == null && fallbackLegacy != null && !fallbackLegacy.isEmpty()
				&& !fallbackLegacy.equals(primary) && !fallbackLegacy.equals(fallbackV1))
			{
				log.warn("color-lock manifest fetch retrying deprecated {}", fallbackLegacy);
				if (tryLoadOrLogWithRetries(fallbackLegacy))
				{
					loadedFrom = fallbackLegacy;
				}
			}
			String reconcileBase = loadedFrom != null ? loadedFrom
				: (fallbackV1 != null && !fallbackV1.isEmpty() ? fallbackV1 : primary);
			reconcilePotionRows(reconcileBase);
			clientThread.invokeLater(onClientThreadFinish);
		});
	}

	private boolean tryLoadOrLogWithRetries(String url)
	{
		for (int attempt = 1; attempt <= MANIFEST_HTTP_RETRIES; attempt++)
		{
			if (tryLoadOrLog(url))
			{
				return true;
			}
			if (!isRetryableManifestError(loadError) || attempt >= MANIFEST_HTTP_RETRIES)
			{
				if (isRetryableManifestError(loadError))
				{
					log.warn("color-lock manifest fetch failed after {} retries: {}", MANIFEST_HTTP_RETRIES, loadError);
				}
				return false;
			}
			log.debug("color-lock manifest retry {}/{} for {}", attempt + 1, MANIFEST_HTTP_RETRIES, url);
			try
			{
				Thread.sleep(2000L * attempt);
			}
			catch (InterruptedException e)
			{
				return false;
			}
		}
		return false;
	}

	private static boolean isRetryableManifestError(String err)
	{
		if (err == null)
		{
			return false;
		}
		String e = err.toLowerCase(Locale.ENGLISH);
		return e.contains("502") || e.contains("503") || e.contains("504")
			|| e.contains("timed out") || e.contains("timeout");
	}

	/** Main manifest omits potions (manual sync-off); add or strip potion rows in a second small GET. */
	private void reconcilePotionRows(String hubItemsUrl)
	{
		if (hubItemsUrl == null || hubItemsUrl.isEmpty() || byId.isEmpty())
		{
			return;
		}
		if (ColorLockItemsApi.potionsWantedInManifest(config, groupSync))
		{
			refreshPotionRowsFromSupplement(hubItemsUrl);
		}
		else
		{
			stripPotionRowsFromCache();
		}
	}

	private void stripPotionRowsFromCache()
	{
		int removed = 0;
		HashMap<Integer, ManifestItem> next = new HashMap<>(Math.max(byId.size(), 64));
		for (Map.Entry<Integer, ManifestItem> e : byId.entrySet())
		{
			if ("potion".equalsIgnoreCase(e.getValue().getCategory()))
			{
				removed++;
				continue;
			}
			next.put(e.getKey(), e.getValue());
		}
		if (removed == 0)
		{
			return;
		}
		byId = Collections.unmodifiableMap(next);
		lastManifestPayloadCrc = Long.MIN_VALUE;
		log.debug("color-lock removed {} potion rows (include potions off)", removed);
	}

	private void refreshPotionRowsFromSupplement(String hubItemsUrl)
	{
		String url = ColorLockItemsApi.buildPotionSupplementItemsUrl(hubItemsUrl, config);
		try
		{
			List<ManifestItem> potions = fetchItemsListBlocking(url);
			HashMap<Integer, ManifestItem> next = new HashMap<>(Math.max(byId.size(), 64));
			for (ManifestItem row : byId.values())
			{
				if (!"potion".equalsIgnoreCase(row.getCategory()))
				{
					next.put(row.getId(), row);
				}
			}
			for (ManifestItem row : potions)
			{
				next.put(row.getId(), row);
			}
			byId = Collections.unmodifiableMap(next);
			lastManifestPayloadCrc = Long.MIN_VALUE;
			loadError = null;
			log.debug("color-lock potion supplement: {} rows ({})", potions.size(), url);
		}
		catch (IOException e)
		{
			loadError = e.getMessage();
			if (isRetryableManifestError(loadError))
			{
				log.debug("color-lock potion supplement fetch failed (retryable): {}", e.getMessage());
			}
			else
			{
				log.warn("color-lock potion supplement fetch failed at {}", url, e);
			}
		}
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
			if (isRetryableManifestError(loadError))
			{
				log.debug("color-lock manifest fetch failed (retryable): {}", e.getMessage());
			}
			else
			{
				log.warn("color-lock manifest fetch failed at {}", url, e);
			}
			return false;
		}
	}

	/** Items list for lookup sidebar ({@code usableBy} + group filters); does not replace the cached manifest. */
	public void fetchPaletteLookupAsync(ColorLockColor assignment, Consumer<List<ManifestItem>> onClientThread)
	{
		final String base = groupSync.getEffectiveItemsManifestUrl(config);
		final String url = ColorLockItemsApi.buildPaletteLookupItemsUrl(base, config, assignment);
		executor.execute(() -> {
			List<ManifestItem> rows;
			try
			{
				rows = fetchItemsListBlocking(url);
				log.debug("palette lookup loaded {} rows from {}", rows.size(), url);
			}
			catch (IOException e)
			{
				log.warn("palette lookup fetch failed at {}", url, e);
				rows = List.of();
			}
			final List<ManifestItem> result = rows;
			clientThread.invokeLater(() -> onClientThread.accept(result));
		});
	}

	List<ManifestItem> fetchItemsListBlocking(String url) throws IOException
	{
		byte[] raw = downloadItemsPayload(url);
		return ManifestJson.readItemsUtf8(gson, raw);
	}

	void loadBlocking(String url) throws IOException
	{
		loadError = null;
		HttpURLConnection conn = openItemsGet(url);
		try
		{
			int headerContract = parsePositiveHeader(conn, ColorLockApiContracts.HEADER_API_CONTRACT);
			int headerSchema = parsePositiveHeader(conn, ColorLockApiContracts.HEADER_ITEMS_SCHEMA);
			if (headerContract > 0
				&& headerContract != ColorLockApiContracts.EXPECTED_ITEMS_API_CONTRACT_VERSION)
			{
				log.warn("Items API contract header {}={} plugin expects {}",
					ColorLockApiContracts.HEADER_API_CONTRACT, headerContract,
					ColorLockApiContracts.EXPECTED_ITEMS_API_CONTRACT_VERSION);
			}
			byte[] raw;
			try (InputStream in = conn.getInputStream())
			{
				raw = in.readAllBytes();
			}
			applyLoadedManifest(url, raw, headerSchema);
		}
		finally
		{
			conn.disconnect();
		}
	}

	private HttpURLConnection openItemsGet(String url) throws IOException
	{
		HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
		conn.setRequestMethod("GET");
		conn.setUseCaches(false);
		conn.setConnectTimeout(20_000);
		conn.setReadTimeout(120_000);
		conn.setRequestProperty("User-Agent", "osrs-color-lock-runelite/1.0 (https://github.com/unidarkshin/osrs-color-lock)");
		conn.setRequestProperty("Cache-Control", "no-cache");
		conn.setRequestProperty("Pragma", "no-cache");
		if (ColorLockItemsApi.isHubItemsApiPath(url) && !ColorLockItemsApi.isStaticItemsJsonPath(url)
			&& config.hubGroupSyncEnabled())
		{
			String token = groupSync.pluginAccessToken();
			if (token != null && !token.isEmpty())
			{
				conn.setRequestProperty("Authorization", "Bearer " + token);
			}
		}
		int httpCode = conn.getResponseCode();
		if (httpCode != HttpURLConnection.HTTP_OK)
		{
			conn.disconnect();
			throw new IOException("items manifest HTTP " + httpCode);
		}
		return conn;
	}

	private byte[] downloadItemsPayload(String url) throws IOException
	{
		HttpURLConnection conn = openItemsGet(url);
		try (InputStream in = conn.getInputStream())
		{
			return in.readAllBytes();
		}
		finally
		{
			conn.disconnect();
		}
	}

	private void applyLoadedManifest(String url, byte[] raw, int headerSchema) throws IOException
	{
		long crcVal = crc32(raw);
		List<ManifestItem> list = ManifestJson.readItemsUtf8(gson, raw);

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
		int rowSchema = schemaSeen == null ? -1 : schemaSeen;
		manifestSchemaVersion = headerSchema > 0 ? headerSchema : rowSchema;
		if (headerSchema > 0 && rowSchema > 0 && headerSchema != rowSchema)
		{
			log.warn("Items schema header {}={} differs from row schemaVersion {}",
				ColorLockApiContracts.HEADER_ITEMS_SCHEMA, headerSchema, rowSchema);
		}
		if (manifestSchemaVersion > 0
			&& manifestSchemaVersion != ColorLockApiContracts.EXPECTED_ITEMS_JSON_SCHEMA_VERSION)
		{
			log.warn("Items schemaVersion={} plugin expects {} — see DATA_CONTRACT.md",
				manifestSchemaVersion, ColorLockApiContracts.EXPECTED_ITEMS_JSON_SCHEMA_VERSION);
		}
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

	private static int parsePositiveHeader(HttpURLConnection conn, String name)
	{
		if (conn == null || name == null)
		{
			return -1;
		}
		String v = conn.getHeaderField(name);
		if (v == null || v.isEmpty())
		{
			return -1;
		}
		try
		{
			int n = Integer.parseInt(v.trim());
			return n > 0 ? n : -1;
		}
		catch (NumberFormatException e)
		{
			return -1;
		}
	}

	private static long crc32(byte[] raw)
	{
		CRC32 c = new CRC32();
		c.update(raw);
		return c.getValue();
	}
}
