package com.osrscolorlock.colorlock;

import java.awt.Color;
import java.util.Locale;

/** Maps manifest palette strings to UI colors for dots / swatches. */
final class ColorLockPalette
{
	private ColorLockPalette()
	{
	}

	static Color toUiColor(String paletteKey)
	{
		if (paletteKey == null)
		{
			return new Color(140, 140, 150);
		}
		switch (paletteKey.trim().toLowerCase(Locale.ENGLISH))
		{
			case "red":
				return new Color(220, 72, 72);
			case "yellow":
				return new Color(230, 200, 70);
			case "green":
				return new Color(72, 170, 92);
			case "blue":
				return new Color(72, 128, 230);
			case "purple":
			case "violet":
				return new Color(160, 96, 210);
			case "brown":
				return new Color(150, 105, 72);
			case "black":
				return new Color(76, 80, 90);
			case "white":
				return new Color(235, 235, 240);
			case "orange":
				return new Color(230, 140, 64);
			case "pink":
				return new Color(230, 130, 168);
			default:
				return new Color(140, 140, 150);
		}
	}

	/** Short tag shown inside palette nodes (R, BL=black, Br=brown, …). */
	static String abbreviation(String paletteKeyRaw)
	{
		if (paletteKeyRaw == null)
		{
			return "?";
		}
		switch (paletteKeyRaw.trim().toLowerCase(Locale.ENGLISH))
		{
			case "red":
				return "R";
			case "yellow":
				return "Y";
			case "green":
				return "G";
			case "blue":
				return "B";
			case "purple":
			case "violet":
				return "P";
			case "brown":
				return "Br";
			case "black":
				return "BL";
			case "white":
				return "W";
			case "orange":
				return "O";
			case "pink":
				return "Pi";
			default:
				return "?";
		}
	}
}
