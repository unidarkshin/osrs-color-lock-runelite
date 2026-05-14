package com.osrscolorlock.colorlock;

import java.util.Locale;

public enum ColorLockColor
{
	RED("red"),
	YELLOW("yellow"),
	GREEN("green"),
	BLUE("blue"),
	PURPLE("purple"),
	BROWN("brown"),
	BLACK("black"),
	WHITE("white");

	private final String key;

	ColorLockColor(String key)
	{
		this.key = key;
	}

	public String getKey()
	{
		return key;
	}

	public boolean matchesPalette(String loweredUsableEntry)
	{
		return loweredUsableEntry != null && key.equals(loweredUsableEntry.trim().toLowerCase(Locale.ENGLISH));
	}

	public static ColorLockColor fromPaletteKey(String paletteKey)
	{
		if (paletteKey == null)
		{
			return null;
		}
		String t = paletteKey.trim().toLowerCase(Locale.ENGLISH);
		for (ColorLockColor c : values())
		{
			if (c.key.equals(t))
			{
				return c;
			}
		}
		return null;
	}
}
