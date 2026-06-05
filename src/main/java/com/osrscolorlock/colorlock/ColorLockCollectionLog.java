package com.osrscolorlock.colorlock;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.VarPlayer;
import net.runelite.api.widgets.ComponentID;
import net.runelite.api.widgets.InterfaceID;
import net.runelite.api.widgets.Widget;

/** Unique collection-log entry count from client varps / open UI. */
final class ColorLockCollectionLog
{
	private static final Pattern TITLE_UNIQUE_TOTAL =
		Pattern.compile("\\((\\d+)\\s*/\\s*(\\d+)\\)");

	private ColorLockCollectionLog()
	{
	}

	/**
	 * Unique collection-log slots filled (not total-with-dupes). Returns null when logged out or count unknown.
	 */
	static Integer readUniqueObtainedCount(Client client)
	{
		if (client == null || client.getGameState() != GameState.LOGGED_IN)
		{
			return null;
		}
		Integer fromTitle = parseUniqueFromOpenInterface(client);
		if (fromTitle != null)
		{
			return fromTitle;
		}
		int totalVarp = client.getVarpValue(VarPlayer.CLOG_TOTAL);
		if (isPlausibleUniqueCount(totalVarp))
		{
			return totalVarp;
		}
		int loggedVarp = client.getVarpValue(VarPlayer.CLOG_LOGGED);
		if (isPlausibleUniqueCount(loggedVarp))
		{
			return loggedVarp;
		}
		return null;
	}

	private static boolean isPlausibleUniqueCount(int n)
	{
		return n >= 0 && n <= 9_999;
	}

	private static Integer parseUniqueFromOpenInterface(Client client)
	{
		if (client.getWidget(InterfaceID.COLLECTION_LOG) == null)
		{
			return null;
		}
		Widget container = client.getWidget(ComponentID.COLLECTION_LOG_CONTAINER);
		if (container == null)
		{
			return null;
		}
		Widget[] staticChildren = container.getStaticChildren();
		if (staticChildren == null || staticChildren.length == 0)
		{
			return null;
		}
		Widget inner = staticChildren[0];
		if (inner == null)
		{
			return null;
		}
		Widget[] dynamicChildren = inner.getDynamicChildren();
		if (dynamicChildren == null || dynamicChildren.length < 2)
		{
			return null;
		}
		Widget title = dynamicChildren[1];
		if (title == null)
		{
			return null;
		}
		String text = title.getText();
		if (text == null || text.isEmpty())
		{
			return null;
		}
		Matcher m = TITLE_UNIQUE_TOTAL.matcher(text);
		if (!m.find())
		{
			return null;
		}
		try
		{
			int unique = Integer.parseInt(m.group(1));
			return isPlausibleUniqueCount(unique) ? unique : null;
		}
		catch (NumberFormatException ignored)
		{
			return null;
		}
	}
}
