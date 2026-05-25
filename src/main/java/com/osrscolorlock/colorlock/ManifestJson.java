package com.osrscolorlock.colorlock;

import com.google.gson.Gson;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class ManifestJson
{
	private ManifestJson()
	{
	}

	static List<ManifestItem> readItemsStreaming(Gson gson, InputStream in) throws IOException
	{
		try (JsonReader reader = new JsonReader(new InputStreamReader(in, StandardCharsets.UTF_8)))
		{
			reader.setLenient(true);
			return readItemsFromReader(gson, reader);
		}
	}

	private static List<ManifestItem> readItemsFromReader(Gson gson, JsonReader reader) throws IOException
	{
		JsonToken first = reader.peek();
		if (first == JsonToken.BEGIN_ARRAY)
		{
			return readArray(gson, reader);
		}
		if (first == JsonToken.BEGIN_OBJECT)
		{
			return readWrappedArray(gson, reader);
		}
		throw new IOException("manifest payload must be a JSON array or an object wrapping an array");
	}

	private static List<ManifestItem> readWrappedArray(Gson gson, JsonReader reader) throws IOException
	{
		reader.beginObject();
		while (reader.hasNext())
		{
			String key = reader.nextName();
			if ("items".equals(key) || "data".equals(key))
			{
				if (reader.peek() == JsonToken.BEGIN_ARRAY)
				{
					List<ManifestItem> list = readArray(gson, reader);
					skipRemainingObject(reader);
					return list;
				}
			}
			reader.skipValue();
		}
		reader.endObject();
		throw new IOException("manifest object has no 'items' or 'data' array");
	}

	private static void skipRemainingObject(JsonReader reader) throws IOException
	{
		while (reader.hasNext())
		{
			reader.nextName();
			reader.skipValue();
		}
		reader.endObject();
	}

	private static List<ManifestItem> readArray(Gson gson, JsonReader reader) throws IOException
	{
		List<ManifestItem> list = new ArrayList<>();
		reader.beginArray();
		while (reader.hasNext())
		{
			ManifestItem item = gson.fromJson(reader, ManifestItem.class);
			if (item != null)
			{
				list.add(item);
			}
		}
		reader.endArray();
		if (list.isEmpty())
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
