package com.osrscolorlock.colorlock;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;

import javax.inject.Inject;
import javax.inject.Singleton;

import net.runelite.client.callback.ClientThread;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;

/**
 * Group session: {@code POST /api/plugin/v1/auth} JWT, {@code GET /api/plugin/v1/state} roster/colors/items URL.
 * Fallback: {@code POST .../api/groups/{slug}/plugin-resolve} when auth path is unavailable (legacy hub).
 */
@Singleton
public class ColorLockGroupSync
{
	private static final Gson GSON = new Gson();
	private static final Logger log = Logger.getLogger(ColorLockGroupSync.class.getName());

	private static final int CONNECT_MS = 20_000;
	private static final int READ_MS = 120_000;

	private final ClientThread clientThread;
	private final Object sessionLock = new Object();

	/** Bearer from {@code /api/plugin/v1/auth}. */
	private String accessJwt;
	private long accessJwtExpiryWallMs;

	private volatile Integer lastPluginProfileRev;

	private volatile String hubItemsManifestUrlOverride;

	private volatile long hubPresentationEpoch;

	private volatile boolean resolvedOk;
	private volatile ColorLockColor resolvedLock;
	private volatile Set<String> resolvedGroupPaletteLowercase;
	/** Last `/auth` http response (HTTP_OK on success, 401/403/404/-1 on failure). */
	private volatile int lastAuthHttpStatus = 0;
	/** Last `/state` http response (0 if never called this session). */
	private volatile int lastStateHttpStatus = 0;
	/** True when member.status came back as "active" on the most recent sync. */
	private volatile boolean lastMemberActive = false;

	@Inject
	public ColorLockGroupSync(ClientThread clientThread)
	{
		this.clientThread = clientThread;
	}

	/**
	 * Items payload URL: hub {@code GET state.items.url} when authenticated, otherwise {@code {base}/api/items},
	 * or static JSON fallback when base cannot be derived.
	 */
	public String getEffectiveItemsManifestUrl(ColorLockConfig config)
	{
		String o = hubItemsManifestUrlOverride;
		if (o != null && !o.isBlank())
		{
			return o.trim();
		}
		String base = ColorLockSites.deriveBaseSiteUrl(ColorLockWeb.DEFAULT_ITEMS_JSON);
		String api = ColorLockSites.concatBasePath(base, "/api/items");
		if (!api.isEmpty())
		{
			return api;
		}
		return ColorLockWeb.DEFAULT_ITEMS_JSON;
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

	long hubPresentationEpoch()
	{
		return hubPresentationEpoch;
	}

	public int getLastAuthHttpStatus()
	{
		return lastAuthHttpStatus;
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
		String slug = config.groupSlug() == null ? "" : config.groupSlug().trim();
		String memberCode = config.memberPublicCode() == null ? "" : config.memberPublicCode().trim();
		return !slug.isEmpty() && !memberCode.isEmpty();
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
		lastPluginProfileRev = null;
		resolvedOk = false;
		resolvedLock = null;
		resolvedGroupPaletteLowercase = null;
		lastAuthHttpStatus = 0;
		lastStateHttpStatus = 0;
		lastMemberActive = false;
		touchHubPresentationEpoch();
	}

	void refreshAsync(ColorLockConfig config, Runnable onFinishClientThread)
	{
		new Thread(() -> {
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
		}, "osrs-color-lock-groupsync").start();
	}

	/**
	 * Lightweight poll {@code GET /api/plugin/v1/state} while JWT fresh; refreshes JWT if needed (no legacy resolve).
	 */
	void pollStateAsync(ColorLockConfig config, Runnable onFinishClientThread)
	{
		new Thread(() -> {
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
		}, "osrs-color-lock-statepoll").start();
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
		if (sc == HttpURLConnection.HTTP_UNAUTHORIZED)
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
			log.warning("Group sync skipped: hub base URL could not be derived");
			clearSession();
			return;
		}

		String slug = config.groupSlug().trim();
		String memberCode = config.memberPublicCode().trim();
		String jp = config.groupJoinPasscode() == null ? "" : config.groupJoinPasscode().trim();

		int authRc = postPluginAuthBlocking(base, slug, memberCode, jp);
		lastAuthHttpStatus = authRc;
		if (authRc == HttpURLConnection.HTTP_NOT_FOUND)
		{
			doLegacyPluginResolveBlocking(base, slug, memberCode, jp);
			return;
		}
		if (authRc != HttpURLConnection.HTTP_OK || accessJwt == null || accessJwt.isEmpty())
		{
			clearSession();
			if (authRc > 0)
			{
				log.warning("plugin/v1/auth HTTP " + authRc + " for group slug " + slug);
			}
			return;
		}

		boolean retriedJwt = false;
		while (true)
		{
			int stateRc = fetchPluginStateAndApplyBlocking(base);
			lastStateHttpStatus = stateRc;
			if (stateRc == HttpURLConnection.HTTP_UNAUTHORIZED && !retriedJwt)
			{
				clearJwt();
				authRc = postPluginAuthBlocking(base, slug, memberCode, jp);
				lastAuthHttpStatus = authRc;
				if (authRc != HttpURLConnection.HTTP_OK || jwtMissingOrExpired())
				{
					clearSession();
					log.warning("plugin/v1/state 401 → re-auth failed for slug " + slug);
					return;
				}
				retriedJwt = true;
				continue;
			}
			if (stateRc != HttpURLConnection.HTTP_OK)
			{
				log.warning("plugin/v1/state HTTP " + stateRc + " — using auth snapshot only");
			}
			break;
		}
	}

	private int postPluginAuthBlocking(String base, String slug, String publicCode, String joinPasscode)
	{
		String uri = ColorLockSites.concatBasePath(base, ColorLockWeb.API_PLUGIN_AUTH);
		HttpURLConnection conn = null;
		try
		{
			conn = openJsonPost(uri);
			String bodyJson = buildAuthJsonBody(slug, publicCode, joinPasscode);
			try (OutputStreamWriter w = new OutputStreamWriter(conn.getOutputStream(), StandardCharsets.UTF_8))
			{
				w.write(bodyJson);
			}

			int rc = conn.getResponseCode();
			if (rc != HttpURLConnection.HTTP_OK)
			{
				drainQuietly(conn.getErrorStream());
				drainQuietly(conn.getInputStream());
				return rc;
			}

			try (BufferedReader reader = new BufferedReader(
				new java.io.InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8)))
			{
				PluginAuthResp auth = GSON.fromJson(reader, PluginAuthResp.class);
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
			}
			return HttpURLConnection.HTTP_OK;
		}
		catch (IOException e)
		{
			clearJwt();
			log.log(Level.WARNING, "plugin/v1/auth failed", e);
			return -1;
		}
		finally
		{
			if (conn != null)
			{
				conn.disconnect();
			}
		}
	}

	private static String buildAuthJsonBody(String slug, String publicCode, String joinPasscode)
	{
		String escSlug = gsonEscape(slug == null ? "" : slug.trim());
		String escCode = gsonEscape(publicCode == null ? "" : publicCode.trim());
		StringBuilder sb = new StringBuilder(96);
		sb.append("{\"publicCode\":\"").append(escCode).append("\"");
		if (slug != null && !slug.trim().isEmpty())
		{
			sb.append(",\"slug\":\"").append(escSlug).append("\"");
		}
		if (joinPasscode != null && !joinPasscode.trim().isEmpty())
		{
			sb.append(",\"joinPasscode\":\"").append(gsonEscape(joinPasscode.trim())).append("\"");
		}
		sb.append("}");
		return sb.toString();
	}

	private static String gsonEscape(String s)
	{
		return s.replace("\\", "\\\\").replace("\"", "\\\"");
	}

	private int fetchPluginStateAndApplyBlocking(String base)
	{
		if (jwtMissingOrExpired())
		{
			return HttpURLConnection.HTTP_UNAUTHORIZED;
		}
		String uri = ColorLockSites.concatBasePath(base, ColorLockWeb.API_PLUGIN_STATE);
		HttpURLConnection conn = null;
		try
		{
			conn = (HttpURLConnection) URI.create(uri).toURL().openConnection();
			conn.setRequestMethod("GET");
			conn.setUseCaches(false);
			conn.setConnectTimeout(CONNECT_MS);
			conn.setReadTimeout(READ_MS);
			conn.setRequestProperty("Authorization", "Bearer " + accessJwt);
			conn.setRequestProperty("Accept", "application/json");
			conn.setRequestProperty("User-Agent",
				"osrs-color-lock-runelite/1.0 (https://github.com/unidarkshin/osrs-color-lock)");
			conn.setRequestProperty("Cache-Control", "no-cache");

			int rc = conn.getResponseCode();
			if (rc != HttpURLConnection.HTTP_OK)
			{
				drainQuietly(rc >= 400 ? conn.getErrorStream() : conn.getInputStream());
				return rc;
			}

			try (BufferedReader reader = new BufferedReader(
				new java.io.InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8)))
			{
				PluginStateResp state = GSON.fromJson(reader, PluginStateResp.class);
				if (state == null || state.member == null)
				{
					return rc;
				}
				String itemsUrl = state.items != null && state.items.url != null ? state.items.url.trim() : null;
				applyGroupAndMember(state.group, state.member, itemsUrl);
			}
			return HttpURLConnection.HTTP_OK;
		}
		catch (IOException e)
		{
			log.log(Level.FINE, "plugin/v1/state failed", e);
			return -1;
		}
		finally
		{
			if (conn != null)
			{
				conn.disconnect();
			}
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
			log.info(() -> "Group hub pluginProfileRev " + prev + " → " + rev + " — assignedColor may have changed.");
		}
		lastPluginProfileRev = rev;
	}

	private void applyGroupAndMember(GroupDto group, MemberDto member, String itemsUrlAbsolute)
	{
		try
		{
			if (member == null)
			{
				resolvedOk = false;
				resolvedLock = null;
				resolvedGroupPaletteLowercase = null;
				log.warning("Hub response missing member payload");
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
				log.info("Hub member status is not active — color lock fallback to plugin assignment.");
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
			if (resolvedLock != null)
			{
				log.info(() -> "Hub assigned your color-lock to " + resolvedLock.getKey() + " (slug + member code matched the hub).");
			}
			else
			{
				log.info("Synced with hub: no assigned color yet — Your color lock in settings still applies until the hub assigns one.");
			}
		}
		finally
		{
			touchHubPresentationEpoch();
		}
	}

	private void touchHubPresentationEpoch()
	{
		hubPresentationEpoch++;
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

	private void doLegacyPluginResolveBlocking(String base, String slug, String memberCode, String jpRaw)
	{
		String encodedSlug = URLEncoder.encode(slug, StandardCharsets.UTF_8).replace("+", "%20");
		try
		{
			String uri = ColorLockSites.concatBasePath(base, "/api/groups/" + encodedSlug + "/plugin-resolve");
			HttpURLConnection conn = openJsonPost(uri);
			OutputStreamWriter w = new OutputStreamWriter(conn.getOutputStream(), StandardCharsets.UTF_8);
			try
			{
				boolean sendPasscode = jpRaw != null && !jpRaw.isEmpty();
				if (sendPasscode)
				{
					w.write(GSON.toJson(new ResolveBody(memberCode, jpRaw)));
				}
				else
				{
					w.write(GSON.toJson(new ResolveBodyOnlyCode(memberCode)));
				}
			}
			finally
			{
				w.flush();
				w.close();
			}

			int rc = conn.getResponseCode();
			if (rc != HttpURLConnection.HTTP_OK)
			{
				clearSession();
				drainQuietly(conn.getErrorStream());
				conn.disconnect();
				log.warning("plugin-resolve HTTP " + rc + " (legacy fallback) slug " + slug);
				return;
			}

			try (BufferedReader reader = new BufferedReader(
				new java.io.InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8)))
			{
				LegacyResolveResponse resp = GSON.fromJson(reader, LegacyResolveResponse.class);
				applyGroupAndMember(resp != null ? resp.group : null, resp != null ? resp.member : null, null);
			}
			finally
			{
				conn.disconnect();
			}
		}
		catch (IOException e)
		{
			clearSession();
			log.log(Level.WARNING, "legacy plugin-resolve failed", e);
		}
	}

	private static HttpURLConnection openJsonPost(String uri) throws IOException
	{
		HttpURLConnection conn = (HttpURLConnection) URI.create(uri).toURL().openConnection();
		conn.setRequestMethod("POST");
		conn.setUseCaches(false);
		conn.setConnectTimeout(CONNECT_MS);
		conn.setReadTimeout(READ_MS);
		conn.setDoOutput(true);
		conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
		conn.setRequestProperty("Accept", "application/json");
		conn.setRequestProperty("Cache-Control", "no-cache");
		conn.setRequestProperty("User-Agent",
			"osrs-color-lock-runelite/1.0 (https://github.com/unidarkshin/osrs-color-lock)");
		return conn;
	}

	private static void drainQuietly(InputStream in)
	{
		if (in == null)
		{
			return;
		}
		try
		{
			in.readAllBytes();
		}
		catch (IOException ignored)
		{
		}
		try
		{
			in.close();
		}
		catch (IOException ignored)
		{
		}
	}

	static final class GroupDto
	{
		String slug;
		String name;

		@SerializedName(value = "enabledColors", alternate = {"enabled_colors"})
		List<String> enabledColors;
	}

	static final class MemberDto
	{
		String assignedColor;
		String status;
		Number pluginProfileRev;
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
	}

	static final class PluginStateResp
	{
		GroupDto group;
		MemberDto member;

		ItemsPointerDto items;
	}

	static final class ResolveBodyOnlyCode
	{
		final String publicCode;

		ResolveBodyOnlyCode(String publicCode)
		{
			this.publicCode = publicCode;
		}
	}

	static final class ResolveBody
	{
		final String publicCode;
		final String joinPasscode;

		ResolveBody(String publicCode, String joinPasscode)
		{
			this.publicCode = publicCode;
			this.joinPasscode = joinPasscode;
		}
	}

	static final class LegacyResolveResponse
	{
		MemberDto member;
		GroupDto group;
	}
}
