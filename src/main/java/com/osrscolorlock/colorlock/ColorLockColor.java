package com.osrscolorlock.colorlock;

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
		return loweredUsableEntry != null && key.equals(loweredUsableEntry.trim().toLowerCase());
	}
}
