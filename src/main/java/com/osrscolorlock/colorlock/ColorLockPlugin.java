package com.osrscolorlock.colorlock;

import java.awt.GraphicsEnvironment;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import javax.inject.Inject;
import javax.inject.Provider;

import com.google.inject.Provides;

import net.runelite.api.Client;
import net.runelite.api.Menu;
import net.runelite.api.MenuEntry;
import net.runelite.api.events.BeforeMenuRender;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.api.events.MenuOpened;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.Text;

@PluginDescriptor(
	name = "Color Lock Helper",
	description = "Color-lock manifest, lookup tooltips, and Eat/Equip restrictions from osrs-color-lock data",
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

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private ColorLockItemOverlay itemOverlay;

	@Inject
	private ItemManager itemManager;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private Provider<ColorLockLookupPanel> lookupPanelProvider;

	private NavigationButton lookupNavButton;

	@Provides
	ColorLockConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(ColorLockConfig.class);
	}

	@Override
	protected void startUp()
	{
		overlayManager.add(itemOverlay);
		if (config.enableLookupPanel())
		{
			lookupNavButton = NavigationButton.builder()
				.tooltip("Color lock lookup")
				.icon(ColorLockLookupPanel.createNavIcon())
				.priority(7)
				.panel(lookupPanelProvider.get())
				.build();
			clientToolbar.addNavigation(lookupNavButton);
		}
		kickoffFetch();
	}

	@Override
	protected void shutDown()
	{
		overlayManager.remove(itemOverlay);
		if (lookupNavButton != null)
		{
			clientToolbar.removeNavigation(lookupNavButton);
			lookupNavButton = null;
		}
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged evt)
	{
		if ("colorlockhelper".equals(evt.getGroup()) && config.enableDownloadOnStartup())
		{
			kickoffFetch();
		}
	}

	@Subscribe
	public void onMenuEntryAdded(MenuEntryAdded event)
	{
		MenuEntry e = event.getMenuEntry();
		plainListedEntry(e);
		if (!config.enforceRestrictions() || manifestStore.itemCount() == 0)
		{
			return;
		}
		if (!restrictUseMenuEntry(e))
		{
			return;
		}
		// Left-click default skips deprioritized ops; right-click list still stripped each frame.
		e.setDeprioritized(true);
	}

	@Subscribe
	public void onMenuOpened(MenuOpened event)
	{
		if (manifestStore.itemCount() == 0)
		{
			return;
		}
		clientThread.invokeLater(() -> clientThread.invokeLater(this::stripOpenMenu));
	}

	@Subscribe
	public void onBeforeMenuRender(BeforeMenuRender event)
	{
		if (manifestStore.itemCount() == 0)
		{
			return;
		}
		if (!client.isMenuOpen())
		{
			return;
		}
		stripOpenMenu();
	}

	@Subscribe
	public void onClientTick(ClientTick tick)
	{
		if (manifestStore.itemCount() == 0)
		{
			return;
		}
		if (!client.isMenuOpen())
		{
			return;
		}
		stripOpenMenu();
	}

	/** Plain labels + optionally strip restricted verbs from the live menu (submenus included). */
	private void stripOpenMenu()
	{
		if (manifestStore.itemCount() == 0 || !client.isMenuOpen())
		{
			return;
		}
		Menu root = client.getMenu();
		if (config.plainListedItemMenus())
		{
			plainMenuLevel(root);
		}
		if (!config.enforceRestrictions())
		{
			return;
		}
		stripMenuLevel(root);
	}

	private void plainMenuLevel(Menu menu)
	{
		if (menu == null)
		{
			return;
		}
		MenuEntry[] entries = menu.getMenuEntries();
		if (entries == null || entries.length == 0)
		{
			return;
		}
		for (MenuEntry e : entries)
		{
			Menu sub = e.getSubMenu();
			if (sub != null)
			{
				plainMenuLevel(sub);
			}
		}
		entries = menu.getMenuEntries();
		if (entries == null || entries.length == 0)
		{
			return;
		}
		for (MenuEntry e : entries)
		{
			plainListedEntry(e);
		}
	}

	private void plainListedEntry(MenuEntry e)
	{
		if (!config.plainListedItemMenus() || manifestStore.itemCount() == 0)
		{
			return;
		}
		int itemId = ColorLockItemIdResolver.resolve(e, client);
		if (itemId <= 0)
		{
			return;
		}
		ManifestItem row = manifestStore.getListedManifestItem(itemId, itemManager);
		if (row == null || row.getUsableColors().isEmpty())
		{
			return;
		}
		String target = e.getTarget();
		if (target != null && target.indexOf('<') >= 0)
		{
			e.setTarget(Text.removeTags(target));
		}
		String opt = e.getOption();
		if (opt != null && opt.indexOf('<') >= 0)
		{
			e.setOption(Text.removeTags(opt));
		}
	}

	private void stripMenuLevel(Menu menu)
	{
		if (menu == null)
		{
			return;
		}
		MenuEntry[] entries = menu.getMenuEntries();
		if (entries == null || entries.length == 0)
		{
			return;
		}
		for (MenuEntry e : entries)
		{
			Menu sub = e.getSubMenu();
			if (sub != null)
			{
				stripMenuLevel(sub);
			}
		}
		entries = menu.getMenuEntries();
		if (entries == null || entries.length == 0)
		{
			return;
		}
		List<MenuEntry> remove = new ArrayList<>();
		for (MenuEntry e : entries)
		{
			if (shouldStripUseMenuEntry(e))
			{
				remove.add(e);
			}
		}
		for (MenuEntry e : remove)
		{
			// If remove fails or leaves a ghost row, blank text so nothing usable shows.
			e.setOption("");
			e.setTarget("");
			menu.removeMenuEntry(e);
		}
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		if (!config.enforceRestrictions() || manifestStore.itemCount() == 0)
		{
			return;
		}
		if (event.isConsumed())
		{
			return;
		}
		if (!ColorLockMenuRules.isRestrictedUseVerb(event.getMenuOption()))
		{
			return;
		}
		int itemId = event.getItemId();
		if (itemId <= 0)
		{
			MenuEntry me = event.getMenuEntry();
			itemId = ColorLockItemIdResolver.resolve(me, client);
		}
		if (itemId <= 0)
		{
			itemId = event.getId();
		}
		if (itemId <= 0)
		{
			return;
		}
		if (manifestStore.isRestrictedForAssignment(itemId, config.assignedColor(), itemManager))
		{
			event.consume();
		}
	}

	private boolean restrictUseMenuEntry(MenuEntry e)
	{
		if (!config.enforceRestrictions() || manifestStore.itemCount() == 0)
		{
			return false;
		}
		if (!ColorLockMenuRules.isRestrictedUseVerb(e.getOption()))
		{
			return false;
		}
		int itemId = ColorLockItemIdResolver.resolve(e, client);
		if (itemId <= 0)
		{
			return false;
		}
		return manifestStore.isRestrictedForAssignment(itemId, config.assignedColor(), itemManager);
	}

	private boolean shouldStripUseMenuEntry(MenuEntry e)
	{
		return restrictUseMenuEntry(e);
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
