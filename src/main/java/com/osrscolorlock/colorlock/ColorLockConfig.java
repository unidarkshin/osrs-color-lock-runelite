package com.osrscolorlock.colorlock;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("colorlockhelper")
public interface ColorLockConfig extends Config
{
	/** Config row label title — used to find this row in RL settings UI. Must match {@link #assignedColor()} name exactly. */
	String ASSIGNED_COLOR_CONFIG_NAME = "Your color lock";

	String MANUAL_LOCK_POTIONS_CONFIG_NAME = "Include potions in item list";
	String MANUAL_INCLUDE_FOOD_CONFIG_NAME = "Include food in item list";
	String MANUAL_LOCK_AMMUNITION_CONFIG_NAME = "Include ammunition in item list";

	/** Config row label — used to find this row in RL settings UI. */
	String GROUP_ACCESS_CODE_CONFIG_NAME = "Group auth code";

	@ConfigItem(
		position = 10,
		keyName = "groupSlug",
		name = GROUP_ACCESS_CODE_CONFIG_NAME,
		description = "Hub auth code (e.g. GeckoGlacier38#0723) or group slug / invite URL. "
			+ "With Sync on, only the auth code is required."
	)
	default String groupSlug()
	{
		return "";
	}

	@ConfigItem(
		position = 11,
		keyName = "groupJoinPasscode",
		name = "Group password",
		description = "Optional. Only required when the hub group is protected with a join password; leave blank otherwise."
	)
	default String groupJoinPasscode()
	{
		return "";
	}

	@ConfigItem(
		position = 12,
		keyName = "memberPublicCode",
		name = "Member code (legacy)",
		description = "Optional. Only for old groups that still use a word member code (e.g. Frog12) with a separate group slug. "
			+ "Leave blank when using Group auth code (Slug#0000)."
	)
	default String memberPublicCode()
	{
		return "";
	}

	@ConfigItem(
		position = 13,
		keyName = "hubGroupSyncEnabled",
		name = "Sync with group",
		description = "Authenticate with your Group auth code (Slug#0000; Group password only if the group has one), "
			+ "pull your assigned color, and reload item rules from the hub. While enabled, potion/food/ammo filters come from "
			+ "the hub — not the manual toggles below. Changing credentials disables sync until you re-check this box."
	)
	default boolean hubGroupSyncEnabled()
	{
		return false;
	}

	@ConfigItem(
		position = 20,
		keyName = "assignedColor",
		name = ASSIGNED_COLOR_CONFIG_NAME,
		description = "Manual fallback when Sync with group is off, or hub has no assigned color yet. "
			+ "After a hub sync with an assigned color, we save it here, show it in the list, and gray this row until you turn sync off."
	)
	default ColorLockColor assignedColor()
	{
		return ColorLockColor.RED;
	}

	@ConfigItem(
		position = 30,
		keyName = "showColorOverlay",
		name = "Mark restricted items",
		description = "Red corner mark on inventory / bank / worn gear your color lock cannot use."
	)
	default boolean showColorOverlay()
	{
		return true;
	}

	@ConfigItem(
		position = 40,
		keyName = "manualLockPotionsToColors",
		name = MANUAL_LOCK_POTIONS_CONFIG_NAME,
		description = "Download potion rows for color-lock rules. Only used when Sync with group is off."
	)
	default boolean manualLockPotionsToColors()
	{
		return false;
	}

	@ConfigItem(
		position = 41,
		keyName = "manualIncludeFood",
		name = MANUAL_INCLUDE_FOOD_CONFIG_NAME,
		description = "Download food / heal rows for color-lock rules. Only used when Sync with group is off (default on)."
	)
	default boolean manualIncludeFood()
	{
		return true;
	}

	@ConfigItem(
		position = 42,
		keyName = "manualLockAmmunitionToColors",
		name = MANUAL_LOCK_AMMUNITION_CONFIG_NAME,
		description = "Download stack ammunition (arrows, bolts, …) for color-lock rules. Only used when Sync with group is off."
	)
	default boolean manualLockAmmunitionToColors()
	{
		return false;
	}
}
