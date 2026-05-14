package com.osrscolorlock.colorlock;

import java.net.URI;

/** Derive site origin from configured items manifest URL ({@code /data/items.json} or {@code /api/items}). */
final class ColorLockSites
{
	private ColorLockSites()
	{
	}

	static String deriveBaseSiteUrl(String itemsUrl)
	{
		if (itemsUrl == null)
		{
			return "";
		}
		String trimmed = itemsUrl.trim();
		if (trimmed.isEmpty())
		{
			return "";
		}
		final String lower = trimmed.toLowerCase();
		final int api = lower.indexOf("/api/items");
		if (api >= 0)
		{
			return trimTrailingSlash(trimmed.substring(0, api));
		}
		final int json = lower.indexOf("/data/items.json");
		if (json >= 0)
		{
			return trimTrailingSlash(trimmed.substring(0, json));
		}

		try
		{
			String withScheme = trimmed.startsWith("//") ? "https:" + trimmed : trimmed;
			if (!withScheme.matches("(?i)^(https?)://.+"))
			{
				withScheme = "https://" + withScheme;
			}
			URI u = URI.create(withScheme);
			String scheme = u.getScheme();
			String host = u.getHost();
			if (host == null || scheme == null)
			{
				return "";
			}
			int port = u.getPort();
			return scheme + "://" + host + (port > 0 ? ":" + port : "");
		}
		catch (IllegalArgumentException e)
		{
			return "";
		}
	}

	private static String trimTrailingSlash(String s)
	{
		int len = s.length();
		while (len > 0 && s.charAt(len - 1) == '/')
		{
			len--;
		}
		return s.substring(0, len);
	}
}
