package com.osrscolorlock.colorlock;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("colorlockhelper")
public interface ColorLockConfig extends Config
{
	@ConfigItem(
		keyName = "assignedColor",
		name = "Your color lock",
		description = "Item actions are validated against manifests using usableColors from osrs-color-lock"
	)
	default ColorLockColor assignedColor()
	{
		return ColorLockColor.RED;
	}

	@ConfigItem(
		keyName = "itemsUrl",
		name = "Items JSON URL",
		description = "/data/items.json or /api/items on deployed app — see DATA_CONTRACT.md"
	)
	default String itemsUrl()
	{
		return "https://osrs-color-lock.vercel.app/data/items.json";
	}

	@ConfigItem(
		keyName = "enableDownload",
		name = "Download manifest on startup",
		description = "Fetches JSON in background; disables if false (menu hooks still TODO)"
	)
	default boolean enableDownloadOnStartup()
	{
		return true;
	}
}
