package com.osrscolorlock.colorlock;

/** Pure color-lock rules on one manifest row (no RuneLite). */
public final class ManifestRules
{
	private ManifestRules()
	{
	}

	public static boolean isUsableByAssignment(ManifestItem row, ColorLockColor assignment)
	{
		if (row == null || row.getUsableColors().isEmpty())
		{
			return false;
		}
		for (String c : row.getUsableColors())
		{
			if (assignment.matchesPalette(c))
			{
				return true;
			}
		}
		return false;
	}

	public static boolean isRestrictedForAssignment(ManifestItem row, ColorLockColor assignment)
	{
		if (row == null || row.getUsableColors().isEmpty())
		{
			return false;
		}
		return !isUsableByAssignment(row, assignment);
	}
}
