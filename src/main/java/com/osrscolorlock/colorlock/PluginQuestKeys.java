package com.osrscolorlock.colorlock;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Maps RuneLite {@code Quest.getName()} labels to hub catalog keys ({@code questColorLockKeys}).
 * Mirrors {@code normalizePluginQuestProgressKey} in the web app (slugify + aliases; full catalog on server).
 */
public final class PluginQuestKeys
{
	private static final Map<String, String> DISPLAY_NAME_ALIASES = buildDisplayNameAliases();

	private PluginQuestKeys()
	{
	}

	private static Map<String, String> buildDisplayNameAliases()
	{
		Map<String, String> m = new HashMap<>();
		putAlias(m, "Cook's Assistant", "cooks_assistant");
		putAlias(m, "Dragon Slayer I", "dragon_slayer_i");
		putAlias(m, "Dragon Slayer II", "dragon_slayer_ii");
		putAlias(m, "Monkey Madness I", "monkey_madness_i");
		putAlias(m, "Monkey Madness II", "monkey_madness_ii");
		putAlias(m, "Recipe for Disaster", "recipe_for_disaster");
		putAlias(m, "The Knight's Sword", "knights_sword");
		putAlias(m, "Fairytale I - Growing Pains", "fairytale_i_growing_pains");
		putAlias(m, "Fairytale II - Cure a Queen", "fairy_tale_ii");
		putAlias(m, "Mourning's End Part I", "mourning_end_part_i");
		putAlias(m, "Mourning's End Part II", "mourning_end_part_ii");
		putAlias(m, "Another Slice of H.A.M.", "another_slice_of_h_a_m");
		putAlias(m, "Icthlarin's Little Helper", "icthlarin_s_little_helper");
		putAlias(m, "Merlin's Crystal", "merlin_s_crystal");
		putAlias(m, "Eadgar's Ruse", "eadgar_s_ruse");
		putAlias(m, "My Arm's Big Adventure", "my_arm_s_big_adventure");
		putAlias(m, "Pirate's Treasure", "pirate_s_treasure");
		putAlias(m, "Witch's House", "witch_s_house");
		putAlias(m, "The Eyes of Glouphrie", "the_eyes_of_glouphrie");
		putAlias(m, "Elemental Workshop I", "elemental_workshop_i");
		return Collections.unmodifiableMap(m);
	}

	private static void putAlias(Map<String, String> m, String displayName, String key)
	{
		m.put(normalizeQuestMatchKey(displayName), key);
	}

	/** Stable slug; matches hub {@code slugifyQuestLabel}. */
	public static String slugifyQuestLabel(String label)
	{
		if (label == null)
		{
			return "";
		}
		return label.trim()
			.toLowerCase(Locale.ENGLISH)
			.replace("'", "")
			.replace("\u2019", "")
			.replaceAll("[^a-z0-9]+", "_")
			.replaceAll("^_|_$", "");
	}

	static String normalizeQuestMatchKey(String label)
	{
		return label.trim().toLowerCase(Locale.ENGLISH).replace('\'', ' ').replace('\u2019', ' ')
			.replaceAll("\\s+", " ");
	}

	/** {@code null} when blank or invalid. */
	public static String normalize(String raw)
	{
		if (raw == null)
		{
			return null;
		}
		String trimmed = raw.trim();
		if (trimmed.isEmpty() || trimmed.length() > 64)
		{
			return null;
		}
		String lc = trimmed.toLowerCase(Locale.ENGLISH);
		if (lc.matches("[a-z][a-z0-9_]*"))
		{
			return lc;
		}
		String fromAlias = DISPLAY_NAME_ALIASES.get(normalizeQuestMatchKey(trimmed));
		if (fromAlias != null)
		{
			return fromAlias;
		}
		String slug = slugifyQuestLabel(trimmed);
		return slug.isEmpty() ? null : slug;
	}

	public static Set<String> normalizeAll(List<String> rawLabels)
	{
		if (rawLabels == null || rawLabels.isEmpty())
		{
			return Collections.emptySet();
		}
		LinkedHashSet<String> out = new LinkedHashSet<>();
		for (String raw : rawLabels)
		{
			String k = normalize(raw);
			if (k != null)
			{
				out.add(k);
			}
		}
		return out.isEmpty() ? Collections.emptySet() : Collections.unmodifiableSet(out);
	}

	public static Set<String> normalizeHubKeys(List<String> hubKeys)
	{
		if (hubKeys == null || hubKeys.isEmpty())
		{
			return Collections.emptySet();
		}
		Set<String> out = new HashSet<>();
		for (String raw : hubKeys)
		{
			if (raw == null)
			{
				continue;
			}
			String t = raw.trim().toLowerCase(Locale.ENGLISH);
			if (!t.isEmpty())
			{
				out.add(t);
			}
		}
		return out.isEmpty() ? Collections.emptySet() : Collections.unmodifiableSet(out);
	}
}
