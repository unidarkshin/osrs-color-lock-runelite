package com.osrscolorlock.colorlock;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("colorlockhelper")
public interface ColorLockConfig extends Config
{
	/** Config row label title — used to find this row in RL settings UI. Must match {@link #assignedColor()} name exactly. */
	String ASSIGNED_COLOR_CONFIG_NAME = "Your color lock";

	@ConfigItem(
		position = 10,
		keyName = "groupSlug",
		name = "Group code",
		description = "Group code from your Color Lock hub URL (/g/…). You can also paste the full invite URL."
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
		name = "Member code",
		description = "Your member/public code from the hub; together with Group code authenticates against the hub."
	)
	default String memberPublicCode()
	{
		return "";
	}

	@ConfigItem(
		position = 13,
		keyName = "hubGroupSyncEnabled",
		name = "Sync with group",
		description = "Authenticate to the hub with Group code and Member code (plus Group password if the group has one), "
			+ "pull your assigned color, and reload item rules. While enabled, the plugin sends a presence heartbeat so the "
			+ "hub shows you online. Requires Group code and Member code filled. Changing any credential field disables sync "
			+ "until you re-check the box."
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
}
