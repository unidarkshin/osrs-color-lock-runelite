package com.osrscolorlock.colorlock;

import net.runelite.client.util.Text;

final class ColorLockMenuRules
{
	private ColorLockMenuRules()
	{
	}

	static boolean isRestrictedUseVerb(String rawOption)
	{
		if (rawOption == null)
		{
			return false;
		}
		String opt = Text.removeTags(rawOption).trim().toLowerCase();
		switch (opt)
		{
			case "eat":
			case "drink":
			case "equip":
			case "wield":
			case "wear":
				return true;
			default:
				return false;
		}
	}
}
