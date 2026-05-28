package com.osrscolorlock.colorlock;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;

import javax.inject.Inject;
import javax.inject.Singleton;

import java.util.concurrent.ScheduledExecutorService;

import net.runelite.client.callback.ClientThread;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import java.net.URI;
import java.net.URLEncoder;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Group session: {@code POST /api/plugin/v1/auth} JWT, {@code GET /api/plugin/v1/state}, {@code PATCH /api/plugin/v1/me}.
 * If {@code /auth} returns HTTP 404, tries stateless {@code POST /api/plugin/v1/resolve/{slug}} (JWT-less, per hub spec).
 */
@Singleton
public class ColorLockGroupSync
{
	private static final Logger log = LoggerFactory.getLogger(ColorLockGroupSync.class);

	private static final int CONNECT_MS = 20_000;
	private static final int READ_MS = 120_000;

	private final ClientThread clientThread;
	private final ScheduledExecutorService executor;
	private final Object sessionLock = new Object();

	/** Bearer from {@code /api/plugin/v1/auth}. */
	private String accessJwt;
	private long accessJwtExpiryWallMs;

	private volatile Integer lastPluginProfileRev;

	private volatile String hubItemsManifestUrlOverride;
	private volatile Integer hubItemsSchemaVersionHint;

	private volatile boolean resolvedOk;
	private volatile ColorLockColor resolvedLock;
	private volatile Set<String> resolvedGroupPaletteLowercase;
	/** Last `/state` roster snapshot (empty list when unsynced). */
	private volatile List<RosterMemberSnapshot> rosterSnapshot = Collections.emptyList();
	/** Last group meta (slug, name, enabledColors) snapshot from /auth or /state. */
	private volatile GroupSnapshot groupSnapshot;
	/** Wall-clock millis of the last successful /state. 0 if never. */
	private volatile long lastStateAtMs;
	/** Last `/auth` http response (HTTP_OK on success, 401/403/404/-1 on failure). */
	private volatile int lastAuthHttpStatus = 0;
	/** Hub-provided `{error}` string from the most recent failed `/auth`, or {@code null}. */
	private volatile String lastAuthErrorMessage;
	/** Last `/state` http response (0 if never called this session). */
	private volatile int lastStateHttpStatus = 0;
	/** True when member.status came back as "active" on the most recent sync. */
	private volatile boolean lastMemberActive = false;
	/** Fingerprint of hub potion/food/ammo toggles; -1 until first /state or /auth group payload. */
	private volatile int lastGroupItemPolicyFingerprint = -1;
	private volatile boolean groupItemPolicyDirty;
	/** Client-detected in-progress quest keys (normalized). */
	private volatile Set<String> localInProgressQuestKeys = Collections.emptySet();
	/** {@code member.colorLock.inProgressQuests} from the last successful {@code GET /state}. */
	private volatile Set<String> hubInProgressQuestKeys = Collections.emptySet();

	private final Gson gson;
	private final OkHttpClient httpClient;

	@Inject
	public ColorLockGroupSync(Gson gson, OkHttpClient httpClient, ClientThread clientThread, ScheduledExecutorService executor)
	{
		this.gson = gson;
		this.httpClient = httpClient.newBuilder()
			.connectTimeout(CONNECT_MS, TimeUnit.MILLISECONDS)
			.readTimeout(READ_MS, TimeUnit.MILLISECONDS)
			.build();
		this.clientThread = clientThread;
		this.executor = executor;
	}

	/**
	 * Items payload URL: hub-supplied {@code state.items.url} when present, otherwise the canonical
	 * {@code {base}/api/v1/items}.
	 */
	public String getEffectiveItemsManifestUrl(ColorLockConfig config)
	{
		if (config != null && !config.hubGroupSyncEnabled())
		{
			return getDefaultItemsManifestUrl(config);
		}
		String o = hubItemsManifestUrlOverride;
		if (o != null && !o.isBlank())
		{
			return ColorLockItemsApi.applyPluginItemsQuery(o.trim(), config);
		}
		return getDefaultItemsManifestUrl(config);
	}

	/** Stable canonical items API URL ignoring hub override. */
	public String getDefaultItemsManifestUrl(ColorLockConfig config)
	{
		String base = ColorLockSites.deriveBaseSiteUrl(ColorLockWeb.DEFAULT_ITEMS_JSON);
		String api = ColorLockSites.concatBasePath(base, ColorLockWeb.API_V1_ITEMS);
		if (!api.isEmpty())
		{
			return ColorLockItemsApi.applyPluginItemsQuery(api, config);
		}
		return ColorLockWeb.DEFAULT_ITEMS_JSON;
	}

	/** Deprecated alias URL — final manifest retry when versioned path fails. */
	public String getLegacyItemsManifestUrl(ColorLockConfig config)
	{
		String base = ColorLockSites.deriveBaseSiteUrl(ColorLockWeb.DEFAULT_ITEMS_JSON);
		String api = ColorLockSites.concatBasePath(base, ColorLockWeb.API_ITEMS_LEGACY);
		if (!api.isEmpty())
		{
			return ColorLockItemsApi.applyPluginItemsQuery(api, config);
		}
		return ColorLockWeb.DEFAULT_ITEMS_JSON;
	}

	/** Nullable Bearer for versioned items API when synced; hub applies group potion/food/ammo policy. */
	public String pluginAccessToken()
	{
		if (jwtMissingOrExpired())
		{
			return null;
		}
		return accessJwt;
	}

	/** Drops a broken/unreachable {@code state.items.url}; next manifest fetch will use the default. */
	public void clearItemsUrlOverride()
	{
		if (hubItemsManifestUrlOverride != null)
		{
			log.warn("Dropping hub items.url override: {}", hubItemsManifestUrlOverride);
			hubItemsManifestUrlOverride = null;
		}
	}

	boolean hubOverridesManualAssignedColor(ColorLockConfig config)
	{
		return config != null && config.hubGroupSyncEnabled() && resolvedOk && resolvedLock != null;
	}

	/** Nullable; hub roster color slot when synced. */
	public ColorLockColor getHubAssignedColor()
	{
		return resolvedLock;
	}

	/** Nullable; {@code items.schemaVersion} from the last successful {@code GET /state}. */
	public Integer getHubItemsSchemaVersionHint()
	{
		return hubItemsSchemaVersionHint;
	}

	/** Last roster snapshot seen on /state. Empty list when unsynced or not yet polled. */
	public List<RosterMemberSnapshot> getRosterSnapshot()
	{
		return rosterSnapshot;
	}

	/** Last group meta (slug, name, enabledColors). Null when never authed. */
	public GroupSnapshot getGroupSnapshot()
	{
		return groupSnapshot;
	}

	/** Wall-clock ms of the most recent /state response, or 0. */
	public long getLastStateAtMs()
	{
		return lastStateAtMs;
	}

	public int getLastAuthHttpStatus()
	{
		return lastAuthHttpStatus;
	}

	/** Hub-provided error string from the most recent failed `/auth`, or {@code null}. */
	public String getLastAuthErrorMessage()
	{
		return lastAuthErrorMessage;
	}

	public int getLastStateHttpStatus()
	{
		return lastStateHttpStatus;
	}

	public boolean isLastMemberActive()
	{
		return lastMemberActive;
	}

	public boolean isResolvedOk()
	{
		return resolvedOk;
	}

	/** Updates client-side quest progress (normalized catalog keys). */
	public void setLocalInProgressQuestKeys(Set<String> keys)
	{
		if (keys == null || keys.isEmpty())
		{
			localInProgressQuestKeys = Collections.emptySet();
			return;
		}
		localInProgressQuestKeys = Collections.unmodifiableSet(new LinkedHashSet<>(keys));
	}

	/** Union of local detection and hub echo — used for {@code questColorLockKeys} enforcement. */
	public Set<String> getEffectiveInProgressQuestKeys()
	{
		Set<String> local = localInProgressQuestKeys;
		Set<String> hub = hubInProgressQuestKeys;
		if (local.isEmpty() && hub.isEmpty())
		{
			return Collections.emptySet();
		}
		LinkedHashSet<String> merged = new LinkedHashSet<>();
		merged.addAll(local);
		merged.addAll(hub);
		return Collections.unmodifiableSet(merged);
	}

	/**
	 * Clears JWT + hub-derived flags. Use when disabling hub sync so UI and rules fall back to config.
	 */
	public void clearHubSessionBlocking()
	{
		synchronized (sessionLock)
		{
			clearSession();
		}
	}

	ColorLockColor effectiveAssignment(ColorLockConfig config)
	{
		if (config == null || !config.hubGroupSyncEnabled())
		{
			return config == null ? ColorLockColor.RED : config.assignedColor();
		}
		if (!groupCredentialsPresent(config))
		{
			return config.assignedColor();
		}
		if (!resolvedOk)
		{
			return config.assignedColor();
		}
		if (resolvedLock == null)
		{
			return config.assignedColor();
		}
		return resolvedLock;
	}

	Set<String> manifestRuleCrewFilter(ColorLockConfig config)
	{
		if (config == null || !config.hubGroupSyncEnabled())
		{
			return null;
		}
		if (!groupCredentialsPresent(config) || !resolvedOk)
		{
			return null;
		}
		return resolvedGroupPaletteLowercase;
	}

	private static boolean groupCredentialsPresent(ColorLockConfig config)
	{
		return ColorLockCredentials.from(config).isFilled();
	}

	private boolean jwtMissingOrExpired()
	{
		if (accessJwt == null || accessJwt.isEmpty())
		{
			return true;
		}
		return System.currentTimeMillis() >= accessJwtExpiryWallMs;
	}

	private void clearJwt()
	{
		accessJwt = null;
		accessJwtExpiryWallMs = 0L;
	}

	private void clearSession()
	{
		clearJwt();
		hubItemsManifestUrlOverride = null;
		hubItemsSchemaVersionHint = null;
		lastPluginProfileRev = null;
		resolvedOk = false;
		resolvedLock = null;
		resolvedGroupPaletteLowercase = null;
		rosterSnapshot = Collections.emptyList();
		groupSnapshot = null;
		lastStateAtMs = 0L;
		lastAuthHttpStatus = 0;
		lastAuthErrorMessage = null;
		lastStateHttpStatus = 0;
		lastMemberActive = false;
		lastGroupItemPolicyFingerprint = -1;
		groupItemPolicyDirty = false;
	}

	/** After {@code GET /state}: hub group potion/food/ammo toggles changed since last poll. */
	public boolean consumeGroupItemPolicyDirty()
	{
		boolean d = groupItemPolicyDirty;
		groupItemPolicyDirty = false;
		return d;
	}

	void refreshAsync(ColorLockConfig config, Runnable onFinishClientThread)
	{
		executor.execute(() -> {
			try
			{
				synchronized (sessionLock)
				{
					syncFullSessionBlocking(config);
				}
			}
			finally
			{
				clientThread.invokeLater(onFinishClientThread);
			}
		});
	}

	/**
	 * Lightweight poll {@code GET /api/plugin/v1/state} while JWT fresh; refreshes JWT if needed.
	 */
	void pollStateAsync(ColorLockConfig config, Runnable onFinishClientThread)
	{
		executor.execute(() -> {
			try
			{
				synchronized (sessionLock)
				{
					pollOrResyncBlocking(config);
				}
			}
			finally
			{
				clientThread.invokeLater(onFinishClientThread);
			}
		});
	}

	private void pollOrResyncBlocking(ColorLockConfig config)
	{
		if (!groupCredentialsPresent(config))
		{
			clearSession();
			return;
		}
		String base = ColorLockSites.deriveBaseSiteUrl(ColorLockWeb.DEFAULT_ITEMS_JSON);
		if (base.isEmpty())
		{
			return;
		}
		if (jwtMissingOrExpired())
		{
			syncFullSessionBlocking(config);
			return;
		}
		int sc = fetchPluginStateAndApplyBlocking(base);
		if (sc == 401)
		{
			clearJwt();
			syncFullSessionBlocking(config);
		}
	}

	private void syncFullSessionBlocking(ColorLockConfig config)
	{
		if (config == null || !config.hubGroupSyncEnabled())
		{
			clearSession();
			return;
		}
		if (!groupCredentialsPresent(config))
		{
			clearSession();
			return;
		}
		String base = ColorLockSites.deriveBaseSiteUrl(ColorLockWeb.DEFAULT_ITEMS_JSON);
		if (base.isEmpty())
		{
			log.warn("Group sync skipped: hub base URL could not be derived");
			clearSession();
			return;
		}

		ColorLockCredentials cred = ColorLockCredentials.from(config);
		String jp = config.groupJoinPasscode() == null ? "" : config.groupJoinPasscode().trim();

		int authRc = postPluginAuthBlocking(base, cred.groupField, cred.memberField, jp);
		lastAuthHttpStatus = authRc;
		if (authRc == 404)
		{
			if (postPluginResolveV1Blocking(base, cred.pathSlug, cred.memberField, jp))
			{
				return;
			}
			clearSession();
			log.warn("plugin/v1/auth 404 and plugin/v1/resolve failed for slug {}", cred.pathSlug);
			return;
		}
		if (authRc != 200 || accessJwt == null || accessJwt.isEmpty())
		{
			clearSession();
			if (authRc > 0)
			{
				log.warn("plugin/v1/auth HTTP {} for group slug {}", authRc, cred.pathSlug);
			}
			return;
		}

		boolean retriedJwt = false;
		while (true)
		{
			int stateRc = fetchPluginStateAndApplyBlocking(base);
			lastStateHttpStatus = stateRc;
			if (stateRc == 401 && !retriedJwt)
			{
				clearJwt();
				authRc = postPluginAuthBlocking(base, cred.groupField, cred.memberField, jp);
				lastAuthHttpStatus = authRc;
				if (authRc != 200 || jwtMissingOrExpired())
				{
					clearSession();
					log.warn("plugin/v1/state 401 -> re-auth failed for slug {}", cred.pathSlug);
					return;
				}
				retriedJwt = true;
				continue;
			}
			if (stateRc != 200)
			{
				log.warn("plugin/v1/state HTTP {} - using auth snapshot only", stateRc);
			}
			break;
		}
	}

	private int postPluginAuthBlocking(String base, String slug, String publicCode, String joinPasscode)
	{
		String uri = ColorLockSites.concatBasePath(base, ColorLockWeb.API_PLUGIN_AUTH);
		String bodyJson = ColorLockAuthBodies.buildPluginAuthJson(slug, publicCode, joinPasscode);
		Request request = new Request.Builder()
			.url(uri)
			.post(RequestBody.create(MediaType.get("application/json; charset=utf-8"), bodyJson))
			.header("Accept", "application/json")
			.header("Cache-Control", "no-cache")
			.header("User-Agent", "osrs-color-lock-runelite/1.0 (https://github.com/unidarkshin/osrs-color-lock)")
			.build();
		try (Response response = httpClient.newCall(request).execute())
		{
			int rc = response.code();
			if (rc != 200)
			{
				lastAuthErrorMessage = readErrorMessageQuietly(response);
				return rc;
			}
			lastAuthErrorMessage = null;

			String respBody = response.body().string();
			PluginAuthResp auth = gson.fromJson(respBody, PluginAuthResp.class);
			if (auth == null || auth.accessToken == null || auth.accessToken.isEmpty() || auth.member == null)
			{
				clearJwt();
				return -1;
			}
			accessJwt = auth.accessToken;
			int expWall = auth.expiresInSec == null || auth.expiresInSec <= 0 ? 7200 : auth.expiresInSec;
			long slackSec = expWall > 120 ? expWall - 60L : Math.max(expWall / 2L, 60L);
			accessJwtExpiryWallMs = System.currentTimeMillis() + slackSec * 1000L;
			applyGroupAndMember(auth.group, auth.member, null);
			return 200;
		}
		catch (IOException e)
		{
			clearJwt();
			log.warn("plugin/v1/auth failed", e);
			return -1;
		}
	}

	void patchMeAsync(ColorLockConfig config, String runescapeName, boolean presenceOnline,
		String currentColorKey, Runnable onFinishClientThread)
	{
		patchMeAsync(config, runescapeName, presenceOnline, currentColorKey, null, null, null, null, null,
			onFinishClientThread);
	}

	void patchMeAsync(ColorLockConfig config, String runescapeName, boolean presenceOnline,
		String currentColorKey, Boolean syncToggleEnabled, Map<String, Integer> stats,
		List<String> completedQuests, List<String> inProgressQuests, List<String> completedDiaries,
		Runnable onFinishClientThread)
	{
		executor.execute(() -> {
			try
			{
				synchronized (sessionLock)
				{
					patchMeBlocking(config, runescapeName, presenceOnline, currentColorKey, syncToggleEnabled, stats,
						completedQuests, inProgressQuests, completedDiaries);
				}
			}
			finally
			{
				clientThread.invokeLater(() -> {
					if (onFinishClientThread != null)
					{
						onFinishClientThread.run();
					}
				});
			}
		});
	}

	/** Hub-validated runescapeUsername: 1-12 chars, [A-Za-z0-9 _-]. */
	private static final java.util.regex.Pattern RSN_PATTERN =
		java.util.regex.Pattern.compile("^[A-Za-z0-9 _-]+$");

	/**
	 * RuneLite returns the local player's display name with the non-breaking space
	 * character (U+00A0) in place of a regular space, plus stray whitespace at edges.
	 * Normalise to plain ASCII so the hub's {@code ^[A-Za-z0-9 _-]{1,12}$} regex matches.
	 */
	private static String normalizeRunescapeName(String raw)
	{
		if (raw == null)
		{
			return "";
		}
		return raw.replace('\u00A0', ' ').trim();
	}

	private int patchMeBlocking(ColorLockConfig config, String runescapeName, boolean presenceOnline,
		String currentColorKey, Boolean syncToggleEnabled, Map<String, Integer> stats, List<String> completedQuests,
		List<String> inProgressQuests, List<String> completedDiaries)
	{
		boolean isSyncToggleEvent = syncToggleEnabled != null;
		if (config == null)
		{
			log.debug("Heartbeat skipped: config not initialised.");
			return -1;
		}
		if (!config.hubGroupSyncEnabled() && !isSyncToggleEvent)
		{
			log.debug("Heartbeat skipped: hub sync disabled.");
			return -1;
		}
		if (jwtMissingOrExpired())
		{
			log.debug("Heartbeat skipped: JWT missing or expired (sync first).");
			return 401;
		}
		String clean = normalizeRunescapeName(runescapeName);
		if (clean.isEmpty())
		{
			log.debug("Heartbeat skipped: no in-game player name yet.");
			return -1;
		}
		if (clean.length() > 12 || !RSN_PATTERN.matcher(clean).matches())
		{
			log.debug("Heartbeat skipped: in-game player name '{}' (raw '{}') fails hub validation (1-12 chars, [A-Za-z0-9 _-]).",
				clean, runescapeName);
			return -1;
		}
		String base = ColorLockSites.deriveBaseSiteUrl(ColorLockWeb.DEFAULT_ITEMS_JSON);
		if (base.isEmpty())
		{
			log.warn("Heartbeat skipped: hub base URL could not be derived.");
			return -1;
		}
		String uri = ColorLockSites.concatBasePath(base, ColorLockWeb.API_PLUGIN_ME);
		StringBuilder bodySb = new StringBuilder(256)
			.append("{\"runescapeUsername\":\"").append(ColorLockAuthBodies.gsonEscape(clean))
			.append("\",\"presence\":{\"online\":").append(presenceOnline).append("}");
		if (currentColorKey != null && !currentColorKey.isBlank())
		{
			String cur = currentColorKey.trim().toLowerCase(Locale.ENGLISH);
			bodySb.append(",\"currentColor\":\"").append(ColorLockAuthBodies.gsonEscape(cur)).append("\"");
		}
		if (syncToggleEnabled != null)
		{
			bodySb.append(",\"sync\":{\"enabled\":").append(syncToggleEnabled.booleanValue()).append("}");
		}
		boolean hasStats = stats != null && !stats.isEmpty();
		boolean hasQuests = completedQuests != null && !completedQuests.isEmpty();
		boolean hasInProgress = inProgressQuests != null && !inProgressQuests.isEmpty();
		boolean hasDiaries = completedDiaries != null && !completedDiaries.isEmpty();
		if (hasStats || hasQuests || hasInProgress || hasDiaries)
		{
			bodySb.append(",\"stats\":{");
			boolean statsFieldWritten = false;
			if (hasStats)
			{
				bodySb.append("\"skills\":{");
				boolean first = true;
				for (Map.Entry<String, Integer> e : stats.entrySet())
				{
					String key = e.getKey();
					if ("hitpoints_current".equals(key) || "prayer_current".equals(key))
					{
						continue;
					}
					if (!first)
					{
						bodySb.append(',');
					}
					bodySb.append('"').append(key).append("\":").append(e.getValue());
					first = false;
				}
				bodySb.append('}');
				statsFieldWritten = true;
				Integer hp = stats.get("hitpoints_current");
				Integer pray = stats.get("prayer_current");
				if (hp != null)
				{
					bodySb.append(",\"hitpoints\":").append(hp);
				}
				if (pray != null)
				{
					bodySb.append(",\"prayer\":").append(pray);
				}
			}
			if (hasQuests)
			{
				if (statsFieldWritten)
				{
					bodySb.append(',');
				}
				bodySb.append("\"completedQuests\":[");
				for (int qi = 0; qi < completedQuests.size(); qi++)
				{
					if (qi > 0)
					{
						bodySb.append(',');
					}
					bodySb.append('"').append(ColorLockAuthBodies.gsonEscape(completedQuests.get(qi))).append('"');
				}
				bodySb.append(']');
				statsFieldWritten = true;
			}
			if (hasInProgress)
			{
				if (statsFieldWritten)
				{
					bodySb.append(',');
				}
				bodySb.append("\"inProgressQuests\":[");
				for (int pi = 0; pi < inProgressQuests.size(); pi++)
				{
					if (pi > 0)
					{
						bodySb.append(',');
					}
					bodySb.append('"').append(ColorLockAuthBodies.gsonEscape(inProgressQuests.get(pi))).append('"');
				}
				bodySb.append(']');
				statsFieldWritten = true;
			}
			if (hasDiaries)
			{
				if (statsFieldWritten)
				{
					bodySb.append(',');
				}
				bodySb.append("\"completedDiaries\":[");
				for (int di = 0; di < completedDiaries.size(); di++)
				{
					if (di > 0)
					{
						bodySb.append(',');
					}
					bodySb.append('"').append(ColorLockAuthBodies.gsonEscape(completedDiaries.get(di))).append('"');
				}
				bodySb.append(']');
			}
			bodySb.append('}');
		}
		bodySb.append("}");
		String body = bodySb.toString();

		Request req = new Request.Builder()
			.url(uri)
			.patch(RequestBody.create(MediaType.get("application/json; charset=utf-8"), body))
			.header("Accept", "application/json")
			.header("Authorization", "Bearer " + accessJwt)
			.header("User-Agent", "osrs-color-lock-runelite/1.0")
			.build();
		try (Response resp = httpClient.newCall(req).execute())
		{
			int rc = resp.code();
			if (rc == 401)
			{
				clearJwt();
			}
			if (rc != 200 && rc != 204)
			{
				String respBody = resp.body() != null ? resp.body().string() : "";
				log.warn("plugin/v1/me PATCH HTTP {} for {} body={}", rc, clean, truncateForLog(respBody));
			}
			else
			{
				log.debug("plugin/v1/me PATCH HTTP {} for {} ({})", rc, clean,
					presenceOnline ? "online" : "offline");
			}
			return rc;
		}
		catch (IOException e)
		{
			log.warn("plugin/v1/me PATCH failed", e);
			return -1;
		}
	}

	private static String truncateForLog(String s)
	{
		if (s == null)
		{
			return "";
		}
		return s.length() > 240 ? s.substring(0, 240) + "…" : s;
	}

	/**
	 * Hub state.items.url comes through unverified. Resolve relative paths against {@code base},
	 * reject loopback / private / file URLs, and drop anything we can't reach.
	 */
	static String normalizeItemsUrl(String raw, String base)
	{
		if (raw == null)
		{
			return null;
		}
		String t = raw.trim();
		if (t.isEmpty())
		{
			return null;
		}
		String resolved = t;
		if (t.startsWith("/"))
		{
			resolved = ColorLockSites.concatBasePath(base, t);
		}
		try
		{
			URI u = URI.create(resolved);
			String scheme = u.getScheme();
			if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https")))
			{
				log.warn("Ignoring hub items.url scheme: {}", resolved);
				return null;
			}
			String host = u.getHost();
			if (host == null || host.isEmpty())
			{
				log.warn("Ignoring hub items.url missing host: {}", resolved);
				return null;
			}
			String h = host.toLowerCase(Locale.ENGLISH);
			boolean unreachableHost = h.equals("localhost") || h.equals("127.0.0.1") || h.equals("0.0.0.0")
				|| h.equals("::1")
				|| h.startsWith("10.") || h.startsWith("192.168.")
				|| (h.startsWith("172.") && in172_16_thru_31(h));
			if (unreachableHost)
			{
				log.warn("Ignoring hub items.url pointing at non-public host: {}", resolved);
				return null;
			}
			return resolved;
		}
		catch (IllegalArgumentException e)
		{
			log.warn("Ignoring malformed hub items.url: {}", resolved);
			return null;
		}
	}

	private static boolean in172_16_thru_31(String host)
	{
		int dot1 = host.indexOf('.', 4);
		if (dot1 < 0)
		{
			return false;
		}
		try
		{
			int second = Integer.parseInt(host.substring(4, dot1));
			return second >= 16 && second <= 31;
		}
		catch (NumberFormatException e)
		{
			return false;
		}
	}

	private int fetchPluginStateAndApplyBlocking(String base)
	{
		if (jwtMissingOrExpired())
		{
			return 401;
		}
		String uri = ColorLockSites.concatBasePath(base, ColorLockWeb.API_PLUGIN_STATE);
		Request request = new Request.Builder()
			.url(uri)
			.header("Authorization", "Bearer " + accessJwt)
			.header("Accept", "application/json")
			.header("User-Agent", "osrs-color-lock-runelite/1.0 (https://github.com/unidarkshin/osrs-color-lock)")
			.header("Cache-Control", "no-cache")
			.build();
		try (Response response = httpClient.newCall(request).execute())
		{
			int rc = response.code();
			if (rc != 200)
			{
				return rc;
			}

			String respBody = response.body().string();
			PluginStateResp state = gson.fromJson(respBody, PluginStateResp.class);
			if (state == null || state.member == null)
			{
				return rc;
			}
			String itemsUrl = state.items != null && state.items.url != null ? state.items.url.trim() : null;
			hubItemsSchemaVersionHint = state.items != null ? state.items.schemaVersion : null;
			applyGroupAndMember(state.group, state.member, normalizeItemsUrl(itemsUrl, base));
			captureRosterAndGroupSnapshot(state);
			lastStateAtMs = System.currentTimeMillis();
			return 200;
		}
		catch (IOException e)
		{
			log.debug("plugin/v1/state failed", e);
			return -1;
		}
	}

	private void noteRev(Number revNum)
	{
		if (revNum == null)
		{
			return;
		}
		int rev = revNum.intValue();
		Integer prev = lastPluginProfileRev;
		if (prev != null && rev > prev)
		{
			log.debug("Group hub pluginProfileRev {} -> {} - assignedColor may have changed.", prev, rev);
		}
		lastPluginProfileRev = rev;
	}

	private void applyGroupAndMember(GroupDto group, MemberDto member, String itemsUrlAbsolute)
	{
		if (group != null)
		{
			noteGroupItemPolicyChange(group);
			groupSnapshot = new GroupSnapshot(
				group.slug,
				group.name,
				group.enabledColors,
				group.colorLockIncludePotions == Boolean.TRUE,
				group.colorLockExcludeFood == Boolean.TRUE,
				group.colorLockIncludeAmmunition == Boolean.TRUE);
		}
		if (member == null)
		{
			resolvedOk = false;
			resolvedLock = null;
			resolvedGroupPaletteLowercase = null;
			log.warn("Hub response missing member payload");
			return;
		}

		boolean activeMember = member.status == null
			|| "active".equalsIgnoreCase(member.status.trim());
		lastMemberActive = activeMember;
		if (!activeMember)
		{
			hubItemsManifestUrlOverride = null;
			resolvedGroupPaletteLowercase = null;
			resolvedOk = false;
			resolvedLock = null;
			log.info("Hub member status is not active - color lock fallback to plugin assignment.");
			return;
		}

		resolvedGroupPaletteLowercase = deriveGroupPaletteIntersect(group);

		if (itemsUrlAbsolute != null && !itemsUrlAbsolute.isBlank())
		{
			hubItemsManifestUrlOverride = itemsUrlAbsolute.trim();
		}

		resolvedOk = true;
		resolvedLock = ColorLockColor.fromPaletteKey(member.assignedColor);

		if (member.pluginProfileRev != null)
		{
			noteRev(member.pluginProfileRev);
		}
		if (member.colorLock != null && member.colorLock.inProgressQuests != null)
		{
			hubInProgressQuestKeys = PluginQuestKeys.normalizeHubKeys(member.colorLock.inProgressQuests);
		}
		else
		{
			hubInProgressQuestKeys = Collections.emptySet();
		}

		if (resolvedLock != null)
		{
			log.info("Hub assigned your color-lock to {} (slug + member code matched the hub).", resolvedLock.getKey());
		}
		else
		{
			log.info("Synced with hub: no assigned color yet - Your color lock in settings still applies until the hub assigns one.");
		}
	}

	private void noteGroupItemPolicyChange(GroupDto group)
	{
		int fp = groupItemPolicyFingerprint(group);
		if (lastGroupItemPolicyFingerprint >= 0 && fp != lastGroupItemPolicyFingerprint)
		{
			groupItemPolicyDirty = true;
			log.info("Hub group item policy changed (fingerprint {} -> {}) — items manifest should reload.",
				lastGroupItemPolicyFingerprint, fp);
		}
		lastGroupItemPolicyFingerprint = fp;
	}

	static int groupItemPolicyFingerprint(GroupDto group)
	{
		if (group == null)
		{
			return 0;
		}
		int fp = 0;
		if (group.colorLockIncludePotions == Boolean.TRUE)
		{
			fp |= 1;
		}
		if (group.colorLockExcludeFood == Boolean.TRUE)
		{
			fp |= 2;
		}
		if (group.colorLockIncludeAmmunition == Boolean.TRUE)
		{
			fp |= 4;
		}
		return fp;
	}

	private static Set<String> deriveGroupPaletteIntersect(GroupDto group)
	{
		if (group == null || group.enabledColors == null || group.enabledColors.isEmpty())
		{
			return null;
		}
		LinkedHashSet<String> canon = new LinkedHashSet<>();
		for (String c : group.enabledColors)
		{
			if (c == null)
			{
				continue;
			}
			String t = c.trim().toLowerCase(Locale.ENGLISH);
			if (t.isEmpty())
			{
				continue;
			}
			canon.add(t);
		}
		if (canon.isEmpty())
		{
			return null;
		}
		HashSet<String> expanded = new HashSet<>(canon.size() + 4);
		for (String t : canon)
		{
			expanded.add(t);
			if ("purple".equals(t))
			{
				expanded.add("violet");
			}
			else if ("violet".equals(t))
			{
				expanded.add("purple");
			}
		}
		return Collections.unmodifiableSet(expanded);
	}

	/** Build a read-only roster snapshot for UI consumption (skips kicked rows). */
	private void captureRosterAndGroupSnapshot(PluginStateResp state)
	{
		if (state == null)
		{
			return;
		}
		List<RosterMemberSnapshot> out;
		if (state.roster == null || state.roster.isEmpty())
		{
			out = Collections.emptyList();
		}
		else
		{
			List<RosterMemberSnapshot> tmp = new ArrayList<>(state.roster.size());
			for (RosterRowDto r : state.roster)
			{
				if (r == null)
				{
					continue;
				}
				tmp.add(new RosterMemberSnapshot(r));
			}
			out = Collections.unmodifiableList(tmp);
		}
		rosterSnapshot = out;
	}

	/** Stateless JWT-less resolve ({@link ColorLockWeb#API_PLUGIN_RESOLVE_V1}) when {@code /auth} returns 404. */
	private boolean postPluginResolveV1Blocking(String base, String slug, String memberCode, String jpRaw)
	{
		String encodedSlug = URLEncoder.encode(slug, StandardCharsets.UTF_8).replace("+", "%20");
		String uri = ColorLockSites.concatBasePath(base, ColorLockWeb.API_PLUGIN_RESOLVE_V1 + encodedSlug);
		String bodyJson = ColorLockAuthBodies.buildPluginResolveJson(slug, memberCode, jpRaw);
		Request request = new Request.Builder()
			.url(uri)
			.post(RequestBody.create(MediaType.get("application/json; charset=utf-8"), bodyJson))
			.header("Accept", "application/json")
			.header("Cache-Control", "no-cache")
			.header("User-Agent", "osrs-color-lock-runelite/1.0 (https://github.com/unidarkshin/osrs-color-lock)")
			.build();
		try (Response response = httpClient.newCall(request).execute())
		{
			int rc = response.code();
			if (rc != 200)
			{
				log.warn("plugin/v1/resolve HTTP {} for slug {}", rc, slug);
				return false;
			}

			String respBody = response.body().string();
			StatelessResolveResponse resp = gson.fromJson(respBody, StatelessResolveResponse.class);
			applyGroupAndMember(resp != null ? resp.group : null, resp != null ? resp.member : null, null);
			return true;
		}
		catch (IOException e)
		{
			log.warn("plugin/v1/resolve failed for slug {}", slug, e);
			return false;
		}
	}

	/** Reads response body and pulls out the hub-supplied {@code {error}} string; returns {@code null} on miss. */
	private static String readErrorMessageQuietly(Response response)
	{
		if (response.body() == null)
		{
			return null;
		}
		try
		{
			String body = response.body().string();
			if (body == null || body.trim().isEmpty())
			{
				return null;
			}
			String trimmed = body.trim();
			try
			{
				@SuppressWarnings("deprecation")
				com.google.gson.JsonElement parsed = new com.google.gson.JsonParser().parse(trimmed);
				com.google.gson.JsonObject obj = parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
				if (obj != null && obj.has("error") && obj.get("error").isJsonPrimitive())
				{
					String e = obj.get("error").getAsString();
					if (e != null && !e.isBlank())
					{
						return e.trim();
					}
				}
			}
			catch (Exception ignored)
			{
			}
			return trimmed.length() > 240 ? trimmed.substring(0, 240) : trimmed;
		}
		catch (IOException ignored)
		{
			return null;
		}
	}

	static final class GroupDto
	{
		String slug;
		String name;

		@SerializedName(value = "enabledColors", alternate = {"enabled_colors"})
		List<String> enabledColors;

		@SerializedName(value = "colorLockIncludePotions", alternate = {"color_lock_include_potions"})
		Boolean colorLockIncludePotions;

		@SerializedName(value = "colorLockExcludeFood", alternate = {"color_lock_exclude_food"})
		Boolean colorLockExcludeFood;

		@SerializedName(value = "colorLockIncludeAmmunition", alternate = {"color_lock_include_ammunition"})
		Boolean colorLockIncludeAmmunition;
	}

	static final class ColorLockDto
	{
		List<String> inProgressQuests;
		List<String> completedQuests;
	}

	static final class MemberDto
	{
		String assignedColor;
		String status;
		Number pluginProfileRev;

		@SerializedName("colorLock")
		ColorLockDto colorLock;
	}

	static final class RosterRowDto
	{
		String id;
		String displayName;
		String assignedColor;
		String role;
		String status;
		Boolean presenceOnline;
		String presenceSummary;
		Boolean pluginSyncEnabled;
		String pluginSyncDisplay;
		String pluginCurrentColor;
		Boolean pluginSyncCaution;
	}

	static final class PluginAuthResp
	{
		String accessToken;
		String tokenType;
		Integer expiresInSec;

		GroupDto group;
		MemberDto member;
	}

	static final class ItemsPointerDto
	{
		String url;
		Integer schemaVersion;
	}

	static final class PluginStateResp
	{
		GroupDto group;
		MemberDto member;

		ItemsPointerDto items;
		List<RosterRowDto> roster;
	}

	/** Read-only snapshot of one roster member as last seen on /state. Safe to share to Swing. */
	public static final class RosterMemberSnapshot
	{
		public final String id;
		public final String displayName;
		public final String assignedColorKey;
		public final String role;
		public final String status;
		public final boolean presenceOnline;
		public final String presenceSummary;
		public final boolean pluginSyncEnabled;
		public final String pluginSyncDisplay;
		public final String pluginCurrentColorKey;
		public final boolean pluginSyncCaution;

		RosterMemberSnapshot(RosterRowDto r)
		{
			this.id = r.id == null ? "" : r.id;
			this.displayName = r.displayName == null ? "" : r.displayName;
			this.assignedColorKey = r.assignedColor == null ? null : r.assignedColor.toLowerCase(Locale.ENGLISH);
			this.role = r.role == null ? "member" : r.role;
			this.status = r.status == null ? "active" : r.status;
			this.presenceOnline = Boolean.TRUE.equals(r.presenceOnline);
			this.presenceSummary = r.presenceSummary == null ? "" : r.presenceSummary;
			this.pluginSyncEnabled = Boolean.TRUE.equals(r.pluginSyncEnabled);
			this.pluginSyncDisplay = r.pluginSyncDisplay == null ? "—" : r.pluginSyncDisplay;
			this.pluginCurrentColorKey = r.pluginCurrentColor == null ? null
				: r.pluginCurrentColor.toLowerCase(Locale.ENGLISH);
			this.pluginSyncCaution = Boolean.TRUE.equals(r.pluginSyncCaution);
		}
	}

	/** Response body from {@link ColorLockWeb#API_PLUGIN_RESOLVE_V1} — same shapes as embedded {@code group}/{@code member} from {@code /auth}. */
	static final class StatelessResolveResponse
	{
		MemberDto member;
		GroupDto group;
	}

	/** Read-only snapshot of group meta last seen on /state or /auth. */
	public static final class GroupSnapshot
	{
		public final String slug;
		public final String name;
		public final List<String> enabledColorsLowercase;
		public final boolean colorLockIncludePotions;
		public final boolean colorLockExcludeFood;
		public final boolean colorLockIncludeAmmunition;

		GroupSnapshot(String slug, String name, List<String> enabled,
			boolean colorLockIncludePotions, boolean colorLockExcludeFood, boolean colorLockIncludeAmmunition)
		{
			this.slug = slug == null ? "" : slug;
			this.name = name == null ? "" : name;
			List<String> out = new ArrayList<>();
			if (enabled != null)
			{
				for (String c : enabled)
				{
					if (c != null && !c.isBlank())
					{
						out.add(c.trim().toLowerCase(Locale.ENGLISH));
					}
				}
			}
			this.enabledColorsLowercase = Collections.unmodifiableList(out);
			this.colorLockIncludePotions = colorLockIncludePotions;
			this.colorLockExcludeFood = colorLockExcludeFood;
			this.colorLockIncludeAmmunition = colorLockIncludeAmmunition;
		}
	}

}
