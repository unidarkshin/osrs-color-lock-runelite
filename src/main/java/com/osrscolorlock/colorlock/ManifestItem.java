package com.osrscolorlock.colorlock;

import com.google.gson.annotations.SerializedName;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

/** Minimum fields described in DATA_CONTRACT.md; Gson ignores extras. */
public class ManifestItem
{
	private int id;
	private String name;
	private String category;
	@SerializedName(value = "schemaVersion", alternate = {"schema_version"})
	private Number schemaVersion;
	@SerializedName(value = "usableColors", alternate = {"usable_colors"})
	private List<String> usableColors;

	/**
	 * When {@code false}, hub does not gate this row (consumables, throwables, …) even if
	 * usableColors exists for visuals. {@code null} ⇒ plugin treats the row as gated unless
	 * {@link #colorLockExcluded} opts it out — the hub should set {@code false} on every non-gated row.
	 */
	@SerializedName(value = "colorLockApplies", alternate = {
		"color_lock_applies",
		"trackedByColorLock",
		"tracked_by_color_lock",
		"colorLockTracked",
		"color_lock_tracked"
	})
	private Boolean colorLockApplies;

	/** When {@code true}, hub opts this row out of lock enforcement (same effect as {@code colorLockApplies: false}). */
	@SerializedName(value = "colorLockExcluded", alternate = {"color_lock_excluded"})
	private Boolean colorLockExcluded;

	/** Quest catalog keys — allow any team color while any key is in the player's in-progress quest set. */
	@SerializedName(value = "questColorLockKeys", alternate = {"quest_color_lock_keys"})
	private List<String> questColorLockKeys;

	private transient volatile List<String> normalizedUsableColors;
	private transient volatile List<String> normalizedQuestColorLockKeys;

	public int getId()
	{
		return id;
	}

	public String getCategory()
	{
		return category == null ? "" : category;
	}

	public String getName()
	{
		return name == null ? "" : name;
	}

	public List<String> getUsableColors()
	{
		return usableColors == null ? Collections.emptyList() : usableColors;
	}

	public Boolean getColorLockApplies()
	{
		return colorLockApplies;
	}

	public Boolean getColorLockExcluded()
	{
		return colorLockExcluded;
	}

	public List<String> getQuestColorLockKeys()
	{
		return questColorLockKeys == null ? Collections.emptyList() : questColorLockKeys;
	}

	List<String> getNormalizedQuestColorLockKeys()
	{
		List<String> cached = normalizedQuestColorLockKeys;
		if (cached != null)
		{
			return cached;
		}
		List<String> raw = getQuestColorLockKeys();
		if (raw.isEmpty())
		{
			normalizedQuestColorLockKeys = Collections.emptyList();
			return normalizedQuestColorLockKeys;
		}
		LinkedHashSet<String> deduped = new LinkedHashSet<>();
		for (String k : raw)
		{
			if (k == null)
			{
				continue;
			}
			String t = k.trim().toLowerCase(java.util.Locale.ENGLISH);
			if (!t.isEmpty())
			{
				deduped.add(t);
			}
		}
		cached = Collections.unmodifiableList(new ArrayList<>(deduped));
		normalizedQuestColorLockKeys = cached;
		return cached;
	}

	public int getSchemaVersionNumber()
	{
		return schemaVersion == null ? -1 : schemaVersion.intValue();
	}

	List<String> getNormalizedUsableColors()
	{
		List<String> cached = normalizedUsableColors;
		if (cached != null)
		{
			return cached;
		}
		List<String> raw = getUsableColors();
		if (raw.isEmpty())
		{
			normalizedUsableColors = Collections.emptyList();
			return normalizedUsableColors;
		}
		LinkedHashSet<String> deduped = new LinkedHashSet<>();
		for (String c : raw)
		{
			if (c == null)
			{
				continue;
			}
			String t = c.trim();
			if (!t.isEmpty())
			{
				deduped.add(t);
			}
		}
		cached = Collections.unmodifiableList(new ArrayList<>(deduped));
		normalizedUsableColors = cached;
		return cached;
	}
}
