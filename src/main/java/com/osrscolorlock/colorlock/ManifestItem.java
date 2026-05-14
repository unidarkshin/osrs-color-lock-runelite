package com.osrscolorlock.colorlock;

import com.google.gson.annotations.SerializedName;

import java.util.Collections;
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

	public int getSchemaVersionNumber()
	{
		return schemaVersion == null ? -1 : schemaVersion.intValue();
	}
}
