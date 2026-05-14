package com.osrscolorlock.colorlock;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Type;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class ManifestJson
{
	private static final Gson GSON = new Gson();
	private static final Type LIST_TYPE = TypeToken.getParameterized(List.class, ManifestItem.class).getType();

	private ManifestJson()
	{
	}

	static List<ManifestItem> readItems(Reader reader) throws IOException
	{
		List<ManifestItem> list = GSON.fromJson(reader, LIST_TYPE);
		if (list == null || list.isEmpty())
		{
			throw new IOException("manifest empty");
		}
		return list;
	}

	static Map<Integer, ManifestItem> toUnmodifiableMap(List<ManifestItem> list)
	{
		Map<Integer, ManifestItem> next = new HashMap<>(list.size() * 2);
		for (ManifestItem row : list)
		{
			next.put(row.getId(), row);
		}
		return Collections.unmodifiableMap(next);
	}
}
