package com.osrscolorlock.colorlock;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Query string and URL helpers for hub {@code GET /api/v1/items} (see app {@code openapi.yml}). */
public final class ColorLockItemsApi
{
	private static final Set<String> PLUGIN_CONTROLLED_KEYS = Set.of(
		"mode", "colored", "groupfilters", "includepotions", "excludefood", "includeammunition", "usableby"
	);

	private ColorLockItemsApi()
	{
	}

	/**
	 * Items list query. Synced: just mode/colored/groupFilters (Bearer applies group policy).
	 * Manual: explicit potion/food/ammo toggles. Potions always excluded here; loaded via supplement.
	 */
	public static String buildItemsQueryString(ColorLockConfig config)
	{
		StringBuilder sb = new StringBuilder("mode=color-lock&colored=1&groupFilters=1");
		if (config == null || config.hubGroupSyncEnabled())
		{
			return sb.toString();
		}
		sb.append("&includePotions=0");
		sb.append("&excludeFood=").append(config.manualIncludeFood() ? "0" : "1");
		sb.append("&includeAmmunition=").append(config.manualLockAmmunitionToColors() ? "1" : "0");
		return sb.toString();
	}

	/** Small {@code category=potion} fetch (~1.4 MiB) instead of full manifest with includePotions=1 (~60 MiB). */
	public static String buildPotionSupplementItemsUrl(String hubItemsUrl, ColorLockConfig config)
	{
		String url = applyPluginItemsQuery(hubItemsUrl, config);
		int q = url.indexOf('?');
		String base = q >= 0 ? url.substring(0, q) : url;
		Map<String, String> params = new LinkedHashMap<>();
		if (q >= 0) parseQueryInto(url.substring(q + 1), params);
		params.put("includePotions", "1");
		params.put("category", "potion");
		return base + "?" + serializeParams(params);
	}

	static boolean potionsWantedInManifest(ColorLockConfig config, ColorLockGroupSync groupSync)
	{
		if (config == null) return false;
		if (config.hubGroupSyncEnabled())
		{
			ColorLockGroupSync.GroupSnapshot snap = groupSync == null ? null : groupSync.getGroupSnapshot();
			return snap != null && snap.colorLockIncludePotions;
		}
		return config.manualLockPotionsToColors();
	}

	/** Lookup sidebar: group-filtered items + hub {@code usableBy} for the player's assigned color. */
	public static String buildPaletteLookupItemsUrl(String hubItemsUrl, ColorLockConfig config,
		ColorLockColor assignment)
	{
		String url = applyPluginItemsQuery(hubItemsUrl, config);
		if (assignment == null || !isHubUsableByColor(assignment.getKey())) return url;
		return appendQueryParam(url, "usableBy", assignment.getKey());
	}

	/** Strip hub-pinned query params and replace with plugin-controlled ones. */
	public static String applyPluginItemsQuery(String url, ColorLockConfig config)
	{
		if (url == null) return null;
		String t = url.trim();
		if (t.isEmpty() || !isHubItemsApiPath(t)) return t;

		int q = t.indexOf('?');
		String base = q >= 0 ? t.substring(0, q) : t;
		Map<String, String> params = new LinkedHashMap<>();
		if (q >= 0) parseQueryInto(t.substring(q + 1), params);
		params.keySet().removeIf(k -> PLUGIN_CONTROLLED_KEYS.contains(k.toLowerCase(Locale.ENGLISH)));
		parseQueryInto(buildItemsQueryString(config), params);
		return params.isEmpty() ? base : base + "?" + serializeParams(params);
	}

	static boolean isHubUsableByColor(String paletteKey)
	{
		if (paletteKey == null) return false;
		switch (paletteKey.trim().toLowerCase(Locale.ENGLISH))
		{
			case "red": case "yellow": case "green": case "blue":
			case "purple": case "brown": case "black": case "white":
				return true;
			default:
				return false;
		}
	}

	public static boolean isHubItemsApiPath(String url)
	{
		if (url == null || url.isEmpty()) return false;
		String lower = url.toLowerCase();
		return lower.contains("/api/v1/items") || lower.contains("/api/items");
	}

	public static boolean isStaticItemsJsonPath(String url)
	{
		return url != null && url.toLowerCase().contains("/data/items.json");
	}

	private static String appendQueryParam(String url, String name, String value)
	{
		if (url == null || name == null || value == null) return url;
		return url + (url.indexOf('?') >= 0 ? "&" : "?") + name + "=" + value;
	}

	private static String serializeParams(Map<String, String> params)
	{
		StringBuilder sb = new StringBuilder();
		for (Map.Entry<String, String> e : params.entrySet())
		{
			if (sb.length() > 0) sb.append('&');
			sb.append(e.getKey());
			if (e.getValue() != null && !e.getValue().isEmpty()) sb.append('=').append(e.getValue());
		}
		return sb.toString();
	}

	private static void parseQueryInto(String query, Map<String, String> params)
	{
		if (query == null || query.isEmpty()) return;
		for (String part : query.split("&"))
		{
			if (part.isEmpty()) continue;
			int eq = part.indexOf('=');
			params.put(eq >= 0 ? part.substring(0, eq) : part, eq >= 0 ? part.substring(eq + 1) : "");
		}
	}
}
