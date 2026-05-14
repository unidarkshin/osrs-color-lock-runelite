package com.osrscolorlock.colorlock;

import java.util.Locale;

public enum ColorLockColor
{
	RED("red"),
	YELLOW("yellow"),
	GREEN("green"),
	BLUE("blue"),
	PURPLE("purple"),
	ORANGE("orange"),
	BROWN("brown"),
	BLACK("black"),
	WHITE("white"),
	PINK("pink");

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
		if (loweredUsableEntry == null)
		{
			return false;
		}
		String t = loweredUsableEntry.trim().toLowerCase(Locale.ENGLISH);
		if (key.equals(t))
		{
			return true;
		}
		return this == PURPLE && "violet".equals(t);
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
			if (c == PURPLE && "violet".equals(t))
			{
				return c;
			}
		}
		return null;
	}
}
