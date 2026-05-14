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
		keyName = "syncGroupAssignmentFromWeb",
		name = "Sync color from crew (web app)",
		description = "When on, overrides \"Your color lock\" with assignment from osrs-color-lock after group rotation (requires slug + member code). Same server as Items JSON URL."
	)
	default boolean syncGroupAssignmentFromWeb()
	{
		return false;
	}

	@ConfigItem(
		keyName = "groupSlug",
		name = "Crew slug",
		description = "Group slug from hub URL (/g/...)"
	)
	default String groupSlug()
	{
		return "";
	}

	@ConfigItem(
		keyName = "memberPublicCode",
		name = "Member code",
		description = "Your FoxMango224-style restore code shown in Color Lock Hub"
	)
	default String memberPublicCode()
	{
		return "";
	}

	@ConfigItem(
		keyName = "groupJoinPasscode",
		name = "Crew join password (optional)",
		description = "Only if admins set a password on Join — required so plugin-resolve can authenticate"
	)
	default String groupJoinPasscode()
	{
		return "";
	}

	@ConfigItem(
		keyName = "itemsUrl",
		name = "Items JSON URL",
		description = "/data/items.json or /api/items on the deployed app (" + ColorLockWeb.ITEMS_PAGE + ")."
	)
	default String itemsUrl()
	{
		return ColorLockWeb.DEFAULT_ITEMS_JSON;
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
		keyName = "manifestRefreshIntervalMinutes",
		name = "Re-fetch items every (minutes)",
		description = "0 = off. Pulls latest items.json / api/items so usableColors stays aligned with the website. Recommended: 60."
	)
	default int manifestRefreshIntervalMinutes()
	{
		return 60;
	}

	@ConfigItem(
		keyName = "refreshManifestOnGameLogin",
		name = "Re-fetch items when you log in",
		description = "Runs once when the client reaches the logged-in state (after world login / hop). Catches updates without waiting for the interval."
	)
	default boolean refreshManifestOnGameLogin()
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

	@ConfigItem(
		keyName = "lookupMyPaletteOnly",
		name = "Lookup: palette filter",
		description = "When toggled from the Lookup panel checkbox, searches only items your color lock can use (crew rules apply)."
	)
	default boolean lookupMyPaletteOnly()
	{
		return false;
	}

	@ConfigItem(
		keyName = "lookupAllColorsRowsOnly",
		name = "Lookup: hub opt-out rows only",
		description = "When toggled from the Lookup panel, searches only hub opt-out listings (shown as \"All colors\")."
	)
	default boolean lookupAllColorsRowsOnly()
	{
		return false;
	}
}
