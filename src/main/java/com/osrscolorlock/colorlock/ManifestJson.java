package com.osrscolorlock.colorlock;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
	private static final Type LIST_TYPE = TypeToken.getParameterized(List.class, ManifestItem.class).getType();

	private ManifestJson()
	{
	}

	static List<ManifestItem> readItemsUtf8(Gson gson, byte[] utf8Body) throws IOException
	{
		String json = new String(utf8Body, java.nio.charset.StandardCharsets.UTF_8);
		@SuppressWarnings("deprecation")
		JsonElement root = new JsonParser().parse(json);
		return readItemsJson(gson, root);
	}

	static List<ManifestItem> readItems(Gson gson, Reader reader) throws IOException
	{
		List<ManifestItem> list = gson.fromJson(reader, LIST_TYPE);
		if (list == null || list.isEmpty())
		{
			throw new IOException("manifest empty");
		}
		return list;
	}

	private static List<ManifestItem> readItemsJson(Gson gson, JsonElement root) throws IOException
	{
		JsonElement arrayEl = unwrapToArray(root);
		if (!arrayEl.isJsonArray())
		{
			throw new IOException("manifest payload must be a JSON array or an object wrapping an array");
		}
		JsonArray arr = arrayEl.getAsJsonArray();
		List<ManifestItem> list = gson.fromJson(arr, LIST_TYPE);
		if (list == null || list.isEmpty())
		{
			throw new IOException("manifest empty");
		}
		return list;
	}

	private static JsonElement unwrapToArray(JsonElement root)
	{
		if (root == null || root.isJsonNull())
		{
			return root;
		}
		if (root.isJsonArray())
		{
			return root;
		}
		if (!root.isJsonObject())
		{
			return root;
		}
		JsonObject o = root.getAsJsonObject();
		for (String k : new String[] {"items", "data"})
		{
			if (o.has(k))
			{
				JsonElement inner = o.get(k);
				if (inner.isJsonArray())
				{
					return inner;
				}
			}
		}
		return root;
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
