package com.osrscolorlock.colorlock;

import java.util.Collections;
import java.util.List;

/** Minimum fields described in DATA_CONTRACT.md; Gson ignores extras. */
public class ManifestItem
{
	private int id;
	private Number schemaVersion;
	private List<String> usableColors;

	public int getId()
	{
		return id;
	}

	public List<String> getUsableColors()
	{
		return usableColors == null ? Collections.emptyList() : usableColors;
	}

	public int getSchemaVersionNumber()
	{
		return schemaVersion == null ? -1 : schemaVersion.intValue();
	}
}
