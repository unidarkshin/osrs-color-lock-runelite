package com.osrscolorlock.colorlock;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Pure color-lock rules on one manifest row (no RuneLite). */
public final class ManifestRules
{
	private ManifestRules()
	{
	}

	/**
	 * Uses hub fields only: {@code colorLockExcluded} or {@code colorLockApplies}.
	 * When both are absent (older JSON), rows with {@code usableColors} are treated as gated — the hub must
	 * emit {@code colorLockApplies: false} / {@code colorLockExcluded: true} on every opt-out row.
	 */
	public static boolean isLockEnforced(ManifestItem row)
	{
		if (row == null || row.getUsableColors().isEmpty())
		{
			return false;
		}
		if (Boolean.TRUE.equals(row.getColorLockExcluded()))
		{
			return false;
		}
		Boolean ap = row.getColorLockApplies();
		if (ap != null)
		{
			return ap.booleanValue();
		}
		return true;
	}

	public static boolean isUsableByAssignment(ManifestItem row, ColorLockColor assignment)
	{
		if (!isLockEnforced(row))
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
		return isRestrictedForAssignment(row, assignment, null);
	}

	/**
	 * When {@code crewPaletteLowercase} is non-null and non-empty (crew hub enabled-colors), rules use the
	 * intersection of manifest {@code usableColors} and that set. Empty intersection ⇒ restricted for everyone.
	 */
	public static boolean isRestrictedForAssignment(ManifestItem row, ColorLockColor assignment,
		Set<String> crewPaletteLowercase)
	{
		if (!isLockEnforced(row))
		{
			return false;
		}
		List<String> tokens = row.getNormalizedUsableColors();
		List<String> effective = effectiveUsable(tokens, crewPaletteLowercase);
		if (effective.isEmpty())
		{
			return true;
		}
		for (String c : effective)
		{
			if (assignment.matchesPalette(c))
			{
				return false;
			}
		}
		return true;
	}

	/** Deduped manifest tokens preserving first-seen order. */
	public static List<String> usableColorsManifestOrdered(ManifestItem row)
	{
		if (row == null || row.getUsableColors().isEmpty())
		{
			return List.of();
		}
		return row.getNormalizedUsableColors();
	}

	/** Manifest colors that also appear in the crew-enabled palette (for UI). Empty if no crew filter applies. */
	public static List<String> usableColorsEffectiveForCrew(ManifestItem row, Set<String> crewPaletteLowercase)
	{
		if (crewPaletteLowercase == null || crewPaletteLowercase.isEmpty())
		{
			return List.of();
		}
		if (row == null || row.getUsableColors().isEmpty())
		{
			return List.of();
		}
		return List.copyOf(effectiveUsable(row.getNormalizedUsableColors(), crewPaletteLowercase));
	}

	private static List<String> effectiveUsable(List<String> normalizedManifestTokens,
		Set<String> crewPaletteLowercase)
	{
		if (crewPaletteLowercase == null || crewPaletteLowercase.isEmpty())
		{
			return normalizedManifestTokens;
		}
		List<String> clipped = new ArrayList<>();
		for (String manifestToken : normalizedManifestTokens)
		{
			String key = manifestToken.trim().toLowerCase(Locale.ENGLISH);
			if (crewAllowsManifestColor(crewPaletteLowercase, key))
			{
				clipped.add(manifestToken);
			}
		}
		return clipped;
	}

	/** Violet / purple are treated as the same slot vs crew payloads. */
	private static boolean crewAllowsManifestColor(Set<String> crewPaletteLowercase, String manifestKeyLc)
	{
		if (crewPaletteLowercase.contains(manifestKeyLc))
		{
			return true;
		}
		if ("purple".equals(manifestKeyLc))
		{
			return crewPaletteLowercase.contains("violet");
		}
		if ("violet".equals(manifestKeyLc))
		{
			return crewPaletteLowercase.contains("purple");
		}
		return false;
	}
}
