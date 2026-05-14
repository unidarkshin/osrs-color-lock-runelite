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
		description = "Fetches JSON in background; turn off only if you load data another way"
	)
	default boolean enableDownloadOnStartup()
	{
		return true;
	}

	@ConfigItem(
		keyName = "enforceRestrictions",
		name = "Block Eat / Equip / …",
		description = "Remove use verbs from menus when your color lock cannot use the item (manifest must be loaded)"
	)
	default boolean enforceRestrictions()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showColorOverlay",
		name = "Mark restricted items",
		description = "Draws a red corner X on inventory / bank / worn slots your color lock cannot use"
	)
	default boolean showColorOverlay()
	{
		return true;
	}

	@ConfigItem(
		keyName = "plainListedItemMenus",
		name = "Plain menus for listed items",
		description = "Remove Jagex color tags from menu option text for manifest-listed items (default click + right-click rows)"
	)
	default boolean plainListedItemMenus()
	{
		return true;
	}

	@ConfigItem(
		keyName = "enableLookupPanel",
		name = "Show lookup side panel",
		description = "Toolbar panel: search items by name, see ids, canonical id, and manifest status"
	)
	default boolean enableLookupPanel()
	{
		return true;
	}
}
