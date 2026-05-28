package com.osrscolorlock.colorlock;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemManager;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
	private final OkHttpClient manifestHttpClient;
	private final OkHttpClient detailHttpClient;
	private final ManifestDiskCache diskCache;

	private volatile Map<Integer, ManifestItem> byId = Collections.emptyMap();
	private volatile int manifestSchemaVersion = -1;
	private volatile String loadError;
	/** Absolute URL passed to {@link #loadBlocking}; used to detect hub items pointer changes without refetching. */
	private volatile String lastLoadedManifestUrl;

	/** After first successful load; item-count + schema fingerprint detects manifest drift. */
	private volatile int lastSnapshotItemCount = -1;
	private volatile int lastSnapshotSchemaVersion = -1;
	/** When true, the next successful hub manifest fetch this login may write the disk cache once. */
	private volatile boolean saveDiskCacheAfterLoginNetworkFetch;

	@Inject
	public ManifestStore(Gson gson, OkHttpClient httpClient, ClientThread clientThread, ColorLockGroupSync groupSync,
		ColorLockConfig config, ScheduledExecutorService executor)
	{
		this.gson = gson;
		this.manifestHttpClient = httpClient.newBuilder()
			.connectTimeout(20, TimeUnit.SECONDS)
			.readTimeout(120, TimeUnit.SECONDS)
			.build();
		this.detailHttpClient = httpClient.newBuilder()
			.connectTimeout(10, TimeUnit.SECONDS)
			.readTimeout(10, TimeUnit.SECONDS)
			.build();
		this.clientThread = clientThread;
		this.groupSync = groupSync;
		this.config = config;
		this.executor = executor;
		this.diskCache = new ManifestDiskCache(gson);
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
		Set<String> inProgress = groupSync.getEffectiveInProgressQuestKeys();
		return ManifestRules.isRestrictedForAssignment(row, assignment, crew, inProgress);
	}

	private static boolean hasListedColors(ManifestItem row)
	{
		return row != null && !row.getUsableColors().isEmpty();
	}

	public String lastLoadedManifestUrl()
	{
		return lastLoadedManifestUrl;
	}

	/** Arm a single disk write after the next successful network manifest fetch this login. */
	public void notifyPlayerLoggedIn()
	{
		saveDiskCacheAfterLoginNetworkFetch = true;
	}

	public void downloadAsync(Runnable onClientThreadFinish)
	{
		final String primary = groupSync.getEffectiveItemsManifestUrl(config);
		final String fallbackV1 = groupSync.getDefaultItemsManifestUrl(config);
		final String fallbackLegacy = groupSync.getLegacyItemsManifestUrl(config);
		executor.execute(() -> {
			restoreFromDiskIfEmpty();
			log.debug("color-lock manifest fetching {}", primary);
			boolean urlChanged = primary != null && !primary.equals(lastLoadedManifestUrl);
			if (urlChanged)
			{
				lastSnapshotSchemaVersion = -1;
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
			if (loadedFrom == null)
			{
				restoreFromDiskIfEmpty();
			}
			reconcilePotionRows(reconcileBase);
			if (loadedFrom != null)
			{
				persistToDiskOnceAfterLoginNetworkFetch();
			}
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
		lastSnapshotSchemaVersion = -1;

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
			lastSnapshotSchemaVersion = -1;
	
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

	/** Fetches drop sources for a single item from the hub detail endpoint. */
	public void fetchItemDropSourcesAsync(int itemId, Consumer<List<DropSourceInfo>> onResult)
	{
		final String base = groupSync.getEffectiveItemsManifestUrl(config);
		final String siteBase = ColorLockSites.deriveBaseSiteUrl(base);
		if (siteBase.isEmpty())
		{
			onResult.accept(List.of());
			return;
		}
		final String url = siteBase + ColorLockWeb.API_V1_ITEM_DETAIL + itemId;
		executor.execute(() -> {
			List<DropSourceInfo> sources;
			try
			{
				sources = fetchDropSourcesBlocking(url);
				log.debug("drop sources loaded {} entries for item {} from {}", sources.size(), itemId, url);
			}
			catch (IOException e)
			{
				log.debug("drop sources fetch failed for item {}: {}", itemId, e.getMessage());
				sources = List.of();
			}
			final List<DropSourceInfo> result = sources;
			onResult.accept(result);
		});
	}

	private List<DropSourceInfo> fetchDropSourcesBlocking(String url) throws IOException
	{
		Request.Builder builder = new Request.Builder()
			.url(url)
			.header("User-Agent", "osrs-color-lock-runelite/1.0 (https://github.com/unidarkshin/osrs-color-lock)")
			.header("Accept", "application/json");
		if (config.hubGroupSyncEnabled())
		{
			String token = groupSync.pluginAccessToken();
			if (token != null && !token.isEmpty())
			{
				builder.header("Authorization", "Bearer " + token);
			}
		}
		try (Response response = detailHttpClient.newCall(builder.build()).execute())
		{
			if (!response.isSuccessful())
			{
				throw new IOException("item detail HTTP " + response.code());
			}
			@SuppressWarnings("deprecation")
			JsonObject obj = new JsonParser().parse(
				new InputStreamReader(response.body().byteStream(), StandardCharsets.UTF_8))
				.getAsJsonObject();
			JsonElement arr = obj.get("monsterDropSources");
			if (arr == null || !arr.isJsonArray())
			{
				return List.of();
			}
			Type listType = TypeToken.getParameterized(List.class, DropSourceInfo.class).getType();
			List<DropSourceInfo> list = gson.fromJson(arr, listType);
			return list == null ? List.of() : list;
		}
	}

	List<ManifestItem> fetchItemsListBlocking(String url) throws IOException
	{
		Request request = buildItemsGetRequest(url);
		try (Response response = manifestHttpClient.newCall(request).execute())
		{
			if (!response.isSuccessful())
			{
				throw new IOException("items manifest HTTP " + response.code());
			}
			return ManifestJson.readItemsStreaming(gson, response.body().byteStream());
		}
	}

	void loadBlocking(String url) throws IOException
	{
		loadError = null;

		if (lastSnapshotItemCount > 0 && isManifestUnchangedViaHead(url))
		{
			log.debug("manifest HEAD unchanged (schema={}); skipping full GET", lastSnapshotSchemaVersion);
			return;
		}

		Request request = buildItemsGetRequest(url);
		try (Response response = manifestHttpClient.newCall(request).execute())
		{
			if (!response.isSuccessful())
			{
				throw new IOException("items manifest HTTP " + response.code());
			}
			int headerContract = parsePositiveHeader(response, ColorLockApiContracts.HEADER_API_CONTRACT);
			int headerSchema = parsePositiveHeader(response, ColorLockApiContracts.HEADER_ITEMS_SCHEMA);
			if (headerContract > 0
				&& headerContract != ColorLockApiContracts.EXPECTED_ITEMS_API_CONTRACT_VERSION)
			{
				log.warn("Items API contract header {}={} plugin expects {}",
					ColorLockApiContracts.HEADER_API_CONTRACT, headerContract,
					ColorLockApiContracts.EXPECTED_ITEMS_API_CONTRACT_VERSION);
			}
			List<ManifestItem> list = ManifestJson.readItemsStreaming(gson, response.body().byteStream());
			applyLoadedManifest(url, list, headerSchema);
		}
	}

	private boolean isManifestUnchangedViaHead(String url)
	{
		try
		{
			Request head = buildItemsGetRequest(url).newBuilder().head().build();
			try (Response response = manifestHttpClient.newCall(head).execute())
			{
				if (!response.isSuccessful())
				{
					return false;
				}
				int headSchema = parsePositiveHeader(response, ColorLockApiContracts.HEADER_ITEMS_SCHEMA);
				return headSchema > 0 && headSchema == lastSnapshotSchemaVersion;
			}
		}
		catch (IOException e)
		{
			log.debug("manifest HEAD check failed, falling through to GET: {}", e.getMessage());
			return false;
		}
	}

	private Request buildItemsGetRequest(String url)
	{
		Request.Builder builder = new Request.Builder()
			.url(url)
			.header("User-Agent", "osrs-color-lock-runelite/1.0 (https://github.com/unidarkshin/osrs-color-lock)")
			.header("Cache-Control", "no-cache")
			.header("Pragma", "no-cache");
		if (ColorLockItemsApi.isHubItemsApiPath(url) && !ColorLockItemsApi.isStaticItemsJsonPath(url)
			&& config.hubGroupSyncEnabled())
		{
			String token = groupSync.pluginAccessToken();
			if (token != null && !token.isEmpty())
			{
				builder.header("Authorization", "Bearer " + token);
			}
		}
		return builder.build();
	}

	private void applyLoadedManifest(String url, List<ManifestItem> list, int headerSchema)
	{
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

		int n = byId.size();
		int s = manifestSchemaVersion;
		boolean firstLoad = lastSnapshotItemCount < 0;
		boolean drift = !firstLoad && (n != lastSnapshotItemCount || s != lastSnapshotSchemaVersion);

		lastSnapshotItemCount = n;
		lastSnapshotSchemaVersion = s;

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
			log.debug("manifest refresh unchanged ({} entries, schema={})", n, s);
		}
	}

	private void restoreFromDiskIfEmpty()
	{
		if (!byId.isEmpty())
		{
			return;
		}
		ManifestDiskCache.Snapshot snap = diskCache.load();
		if (snap == null)
		{
			return;
		}
		String url = snap.sourceUrl == null ? "" : snap.sourceUrl;
		int schema = snap.schemaVersion > 0 ? snap.schemaVersion : -1;
		applyLoadedManifest(url, snap.items, schema);
		log.info("color-lock manifest restored {} items from disk cache (schema {}, saved {})",
			byId.size(), manifestSchemaVersion, snap.savedAt == null ? "?" : snap.savedAt);
	}

	private void persistToDiskOnceAfterLoginNetworkFetch()
	{
		if (!saveDiskCacheAfterLoginNetworkFetch || byId.isEmpty())
		{
			return;
		}
		saveDiskCacheAfterLoginNetworkFetch = false;
		diskCache.save(lastLoadedManifestUrl, manifestSchemaVersion, new ArrayList<>(byId.values()));
		log.info("color-lock manifest saved to disk cache ({} items, schema {})", byId.size(), manifestSchemaVersion);
	}

	private static int parsePositiveHeader(Response response, String name)
	{
		if (response == null || name == null)
		{
			return -1;
		}
		String v = response.header(name);
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

}
