package com.osrscolorlock.colorlock;

import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemManager;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

@Singleton
public class ManifestStore
{
	private static final Logger log = Logger.getLogger(ManifestStore.class.getName());

	private final ClientThread clientThread;

	private volatile Map<Integer, ManifestItem> byId = Collections.emptyMap();
	private volatile int manifestSchemaVersion = -1;
	private volatile String loadError;

	/** After first successful load, used to detect drift on refresh. */
	private volatile int lastSnapshotItemCount = -1;
	private volatile int lastSnapshotSchemaVersion = Integer.MIN_VALUE;

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

	public ManifestItem getManifestItem(int itemId)
	{
		return byId.get(itemId);
	}

	/**
	 * Row with non-empty usableColors for this id, or for {@link ItemManager#canonicalize(int)} when the
	 * variant id is missing from the manifest (e.g. charged gear).
	 */
	public ManifestItem getListedManifestItem(int itemId, ItemManager itemManager)
	{
		ManifestItem row = byId.get(itemId);
		if (hasListedColors(row))
		{
			return row;
		}
		if (itemManager != null)
		{
			int canon = itemManager.canonicalize(itemId);
			if (canon != itemId)
			{
				ManifestItem alt = byId.get(canon);
				if (hasListedColors(alt))
				{
					return alt;
				}
			}
		}
		return row;
	}

	public boolean isUsableByAssignment(int itemId, ColorLockColor assignment)
	{
		return ManifestRules.isUsableByAssignment(byId.get(itemId), assignment);
	}

	/** True when manifest lists usableColors and your lock is not among them. Unknown items are not restricted. */
	public boolean isRestrictedForAssignment(int itemId, ColorLockColor assignment)
	{
		return ManifestRules.isRestrictedForAssignment(byId.get(itemId), assignment);
	}

	/**
	 * Like {@link #isRestrictedForAssignment(int, ColorLockColor)} but resolves manifest rows via
	 * {@link #getListedManifestItem(int, ItemManager)} so variant ids match canonical entries.
	 */
	public boolean isRestrictedForAssignment(int itemId, ColorLockColor assignment, ItemManager itemManager)
	{
		if (itemManager == null)
		{
			return isRestrictedForAssignment(itemId, assignment);
		}
		return ManifestRules.isRestrictedForAssignment(getListedManifestItem(itemId, itemManager), assignment);
	}

	private static boolean hasListedColors(ManifestItem row)
	{
		return row != null && !row.getUsableColors().isEmpty();
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
			List<ManifestItem> list = ManifestJson.readItems(reader);
			Integer schemaSeen = null;
			for (ManifestItem row : list)
			{
				if (schemaSeen == null && row.getSchemaVersionNumber() >= 0)
				{
					schemaSeen = row.getSchemaVersionNumber();
				}
			}
			byId = ManifestJson.toUnmodifiableMap(list);
			manifestSchemaVersion = schemaSeen == null ? -1 : schemaSeen;
			final int prevC = lastSnapshotItemCount;
			final int prevS = lastSnapshotSchemaVersion;
			int n = byId.size();
			int s = manifestSchemaVersion;
			boolean firstLoad = prevC < 0;
			boolean changed = !firstLoad && (n != prevC || s != prevS);
			if (changed)
			{
				log.info(() -> String.format(
					"Items manifest resynced with host (was %d items schema %d — now %d items schema %d)",
					prevC,
					prevS,
					n,
					s
				));
			}
			lastSnapshotItemCount = n;
			lastSnapshotSchemaVersion = s;
			if (firstLoad || changed)
			{
				log.info(() -> String.format(
					"color-lock manifest loaded %d items schemaVersion=%s",
					byId.size(),
					Integer.toString(manifestSchemaVersion)
				));
			}
			else
			{
				log.fine(() -> String.format(
					"color-lock manifest refresh OK (still %d items, schema %d)",
					n,
					s
				));
			}
		}
		finally
		{
			conn.disconnect();
		}
	}
}
