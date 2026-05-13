package com.osrscolorlock.colorlock;

import javax.inject.Inject;
import java.awt.GraphicsEnvironment;
import java.util.logging.Logger;

import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

@PluginDescriptor(
	name = "Color Lock Helper",
	description = "Loads usableColors from deployable JSON (same as osrs-color-lock web)",
	tags = {"ironman", "gim", "color-lock"}
)
public class ColorLockPlugin extends Plugin
{
	private static final Logger log = Logger.getLogger(ColorLockPlugin.class.getName());

	static final int EXPECTED_SCHEMA_VERSION = 1;

	@Inject
	private ColorLockConfig config;

	@Inject
	private ManifestStore manifestStore;

	@Override
	protected void startUp()
	{
		kickoffFetch();
	}

	@Override
	protected void shutDown()
	{
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged evt)
	{
		if ("colorlockhelper".equals(evt.getGroup()) && config.enableDownloadOnStartup())
		{
			kickoffFetch();
		}
	}

	private void kickoffFetch()
	{
		if (!config.enableDownloadOnStartup())
		{
			return;
		}
		if (GraphicsEnvironment.isHeadless())
		{
			return;
		}
		manifestStore.downloadAsync(config, () -> validateSchemaQuietly());
	}

	private void validateSchemaQuietly()
	{
		String err = manifestStore.lastLoadError();
		if (err != null)
		{
			return;
		}
		int v = manifestStore.manifestSchemaVersion();
		if (v != EXPECTED_SCHEMA_VERSION)
		{
			log.warning("Items schemaVersion=" + v + " plugin expects " + EXPECTED_SCHEMA_VERSION + " — see DATA_CONTRACT.md");
		}
	}
}
