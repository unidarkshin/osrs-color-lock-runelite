package com.osrscolorlock.colorlock;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.runelite.client.callback.ClientThread;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

@Singleton
public class ManifestStore
{
	private static final Logger log = Logger.getLogger(ManifestStore.class.getName());

	private final ClientThread clientThread;
	private final Gson gson = new Gson();

	private volatile Map<Integer, ManifestItem> byId = Collections.emptyMap();
	private volatile int manifestSchemaVersion = -1;
	private volatile String loadError;

	@Inject
	public ManifestStore(ClientThread clientThread)
	{
		this.clientThread = clientThread;
	}

	public String lastLoadError()
	{
		return loadError;
	}

	public int manifestSchemaVersion()
	{
		return manifestSchemaVersion;
	}

	public int itemCount()
	{
		return byId.size();
	}

	public boolean isUsableByAssignment(int itemId, ColorLockColor assignment)
	{
		ManifestItem row = byId.get(itemId);
		if (row == null || row.getUsableColors().isEmpty())
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

	public void downloadAsync(ColorLockConfig config, Runnable onClientThreadFinish)
	{
		new Thread(() -> {
			try
			{
				loadBlocking(config.itemsUrl());
			}
			catch (IOException e)
			{
				loadError = e.getMessage();
				log.log(Level.WARNING, "color-lock manifest fetch failed", e);
			}
			clientThread.invokeLater(onClientThreadFinish);
		}, "osrs-color-lock-manifest").start();
	}

	void loadBlocking(String url) throws IOException
	{
		loadError = null;
		HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
		conn.setConnectTimeout(20_000);
		conn.setReadTimeout(120_000);
		conn.setRequestProperty("User-Agent", "osrs-color-lock-runelite/1.0 (https://github.com/unidarkshin/osrs-color-lock)");

		try (InputStreamReader reader = new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))
		{
			java.lang.reflect.Type listType = TypeToken.getParameterized(List.class, ManifestItem.class).getType();
			List<ManifestItem> list = gson.fromJson(reader, listType);
			if (list == null || list.isEmpty())
			{
				throw new IOException("manifest empty");
			}
			Map<Integer, ManifestItem> next = new HashMap<>(list.size() * 2);
			Integer schemaSeen = null;
			for (ManifestItem row : list)
			{
				next.put(row.getId(), row);
				if (schemaSeen == null && row.getSchemaVersionNumber() >= 0)
				{
					schemaSeen = row.getSchemaVersionNumber();
				}
			}
			byId = Collections.unmodifiableMap(next);
			manifestSchemaVersion = schemaSeen == null ? -1 : schemaSeen;
			log.info(() -> String.format(
				"color-lock manifest loaded %d items schemaVersion=%s",
				byId.size(),
				Integer.toString(manifestSchemaVersion)
			));
		}
		finally
		{
			conn.disconnect();
		}
	}
}
