package com.osrscolorlock.colorlock;

import java.net.URI;
import java.util.Locale;

/** Canonical group app URLs — keep in sync with deployed Color Lock Hub. */
public final class ColorLockWeb
{
	public static final String HUB = "https://group.thegrandchart.com/";
	public static final String ITEMS_PAGE = "https://group.thegrandchart.com/items";
	/** Typical static manifest path for plugins (usableColors payloads). */
	public static final String DEFAULT_ITEMS_JSON = "https://group.thegrandchart.com/data/items.json";

	/** Stored in older profiles; rewritten to {@link #DEFAULT_ITEMS_JSON} on plugin start when still present. */
	public static final String LEGACY_VERCEL_ITEMS_JSON = "https://osrs-color-lock.vercel.app/data/items.json";
	public static final String LEGACY_VERCEL_API_ITEMS = "https://osrs-color-lock.vercel.app/api/items";

	private ColorLockWeb()
	{
	}

	/**
	 * True when the user's saved URL pointed at the old Vercel deployment (exact known paths or same host).
	 */
	public static boolean shouldMigrateLegacyVercelItemsUrl(String itemsUrlConfigured)
	{
		if (itemsUrlConfigured == null)
		{
			return false;
		}
		String t = itemsUrlConfigured.trim();
		if (t.isEmpty())
		{
			return false;
		}
		String lower = t.toLowerCase(Locale.ENGLISH);
		if (LEGACY_VERCEL_ITEMS_JSON.equalsIgnoreCase(t))
		{
			return true;
		}
		if (LEGACY_VERCEL_API_ITEMS.equalsIgnoreCase(t))
		{
			return true;
		}
		if (!lower.startsWith("http://") && !lower.startsWith("https://"))
		{
			return false;
		}
		try
		{
			String withScheme = t.startsWith("//") ? "https:" + t : t;
			URI uri = URI.create(withScheme.replace(" ", ""));
			String host = uri.getHost();
			if (host == null)
			{
				return false;
			}
			if (!"osrs-color-lock.vercel.app".equalsIgnoreCase(host))
			{
				return false;
			}
			String path = uri.getRawPath();
			String p = path == null ? "" : path.toLowerCase(Locale.ENGLISH);
			return p.endsWith("/data/items.json") || p.endsWith("/api/items");
		}
		catch (IllegalArgumentException e)
		{
			return false;
		}
	}
}
