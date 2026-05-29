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
		description = "Paste your Group#0000 code from the Color Lock hub.",
		secret = true
	)
	default String groupSlug()
	{
		return "";
	}

	@ConfigItem(
		position = 11,
		keyName = "groupJoinPasscode",
		name = "Group password",
		description = "Only needed if the group has a join password.",
		secret = true
	)
	default String groupJoinPasscode()
	{
		return "";
	}

	@ConfigItem(
		position = 12,
		keyName = "memberPublicCode",
		name = "Member code (legacy)",
		description = "Legacy word code (e.g. Frog12). Leave blank for new groups."
	)
	default String memberPublicCode()
	{
		return "";
	}

	@ConfigItem(
		position = 13,
		keyName = "hubGroupSyncEnabled",
		name = "Sync with group",
		description = "Pull your color and item rules from the hub. Sends your display name and stats while enabled."
	)
	default boolean hubGroupSyncEnabled()
	{
		return false;
	}

	@ConfigItem(
		position = 20,
		keyName = "assignedColor",
		name = ASSIGNED_COLOR_CONFIG_NAME,
		description = "Your color for item restrictions. Overwritten by hub when synced."
	)
	default ColorLockColor assignedColor()
	{
		return ColorLockColor.RED;
	}

	@ConfigItem(
		position = 30,
		keyName = "showColorOverlay",
		name = "Mark restricted items",
		description = "Draws a red corner mark on items in inventory, bank, shops, and worn equipment that your color cannot use."
	)
	default boolean showColorOverlay()
	{
		return true;
	}

	@ConfigItem(
		position = 32,
		keyName = "showColorSwatches",
		name = "Color swatches on hover",
		description = "Show which colors can use an item when you hover over it (inventory, bank, shops, equipment)."
	)
	default boolean showColorSwatches()
	{
		return true;
	}

	@ConfigItem(
		position = 35,
		keyName = "showLookupPanel",
		name = "Show lookup sidebar",
		description = "Show the Color Locked lookup panel in the sidebar for searching items by color."
	)
	default boolean showLookupPanel()
	{
		return true;
	}

	@ConfigItem(
		position = 40,
		keyName = "manualLockPotionsToColors",
		name = MANUAL_LOCK_POTIONS_CONFIG_NAME,
		description = "Include potions in color-lock rules. Ignored when synced."
	)
	default boolean manualLockPotionsToColors()
	{
		return false;
	}

	@ConfigItem(
		position = 41,
		keyName = "manualIncludeFood",
		name = MANUAL_INCLUDE_FOOD_CONFIG_NAME,
		description = "Include food in color-lock rules. Ignored when synced."
	)
	default boolean manualIncludeFood()
	{
		return true;
	}

	@ConfigItem(
		position = 42,
		keyName = "manualLockAmmunitionToColors",
		name = MANUAL_LOCK_AMMUNITION_CONFIG_NAME,
		description = "Include ammunition in color-lock rules. Ignored when synced."
	)
	default boolean manualLockAmmunitionToColors()
	{
		return false;
	}
}
