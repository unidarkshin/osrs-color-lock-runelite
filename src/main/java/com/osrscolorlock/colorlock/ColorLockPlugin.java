package com.osrscolorlock.colorlock;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.awt.Window;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import javax.inject.Inject;
import javax.inject.Provider;

import com.google.inject.Provides;

import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Menu;
import net.runelite.api.MenuEntry;
import net.runelite.api.events.BeforeMenuRender;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.api.events.MenuOpened;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.ChatMessageType;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.Text;

@PluginDescriptor(
	name = "Color Locked",
	description = "Group Iron color-lock: hub rules, menu blocking, red marks, and item lookup.",
	tags = {"ironman", "gim", "color-lock", "groupiron"}
)
public class ColorLockPlugin extends Plugin
{
	private static final Logger log = Logger.getLogger(ColorLockPlugin.class.getName());

	static final int EXPECTED_SCHEMA_VERSION = 2;

	/** Fixed background interval for manifest refresh (no config UI). */
	private static final int MANIFEST_REFRESH_INTERVAL_MINUTES = 60;

	private void migrateLegacyItemsUrlIfNeeded()
	{
		String configured = configManager.getConfiguration("colorlockhelper", "itemsUrl");
		if (!ColorLockWeb.shouldMigrateLegacyVercelItemsUrl(configured))
		{
			return;
		}
		configManager.setConfiguration("colorlockhelper", "itemsUrl", ColorLockWeb.DEFAULT_ITEMS_JSON);
		log.info("Updated stored items URL from legacy osrs-color-lock.vercel.app to " + ColorLockWeb.DEFAULT_ITEMS_JSON);
	}

	private void unsetLegacySyncDropdownKeyIfPresent()
	{
		if (configManager.getConfiguration("colorlockhelper", "syncToGroupAction") != null)
		{
			configManager.unsetConfiguration("colorlockhelper", "syncToGroupAction");
		}
	}

	@Inject
	private ColorLockConfig config;

	@Inject
	private ConfigManager configManager;

	@Inject
	private ManifestStore manifestStore;

	@Inject
	private ColorLockGroupSync groupSync;

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
	private ChatMessageManager chatMessageManager;

	@Inject
	private Provider<ColorLockLookupPanel> lookupPanelProvider;

	private NavigationButton lookupNavButton;

	private static final long LOGIN_REFRESH_DEBOUNCE_MS = 90_000L;

	private ScheduledExecutorService refreshScheduler;
	private ScheduledFuture<?> scheduledManifestRefresh;
	private volatile long lastDebouncedRefreshMs;
	private long lastSeenHubPresentationEpochInSettingsUi = Long.MIN_VALUE;
	private long lastMuteSwingMs;

	@Provides
	ColorLockConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(ColorLockConfig.class);
	}

	@Override
	protected void startUp()
	{
		migrateLegacyItemsUrlIfNeeded();
		unsetLegacySyncDropdownKeyIfPresent();
		overlayManager.add(itemOverlay);
		lookupNavButton = NavigationButton.builder()
			.tooltip("Color Locked lookup")
			.icon(ColorLockLookupPanel.createNavIcon())
			.priority(7)
			.panel(lookupPanelProvider.get())
			.build();
		clientToolbar.addNavigation(lookupNavButton);
		kickoffFetch();
		lastDebouncedRefreshMs = System.currentTimeMillis();
		schedulePeriodicManifestRefresh();
	}

	private ColorLockColor assignment()
	{
		return groupSync.effectiveAssignment(config);
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
		cancelPeriodicManifestRefresh();
		if (refreshScheduler != null)
		{
			refreshScheduler.shutdown();
			refreshScheduler = null;
		}
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged evt)
	{
		if (!"colorlockhelper".equals(evt.getGroup()))
		{
			return;
		}
		String key = evt.getKey();
		if ("hubGroupSyncEnabled".equals(key))
		{
			boolean on = parseConfigBoolean(evt.getNewValue());
			if (on)
			{
				if (!groupSlugAndMemberCodeFilled(config))
				{
					log.warning("Sync with group needs non-empty Group code and Member code. Checkbox turned off.");
					SwingUtilities.invokeLater(() ->
						configManager.setConfiguration(evt.getGroup(), key, Boolean.FALSE.toString()));
					groupSync.clearHubSessionBlocking();
					return;
				}
				runOneShotHubSyncThenManifestAndMirror();
			}
			else
			{
				groupSync.clearHubSessionBlocking();
			}
			lastSeenHubPresentationEpochInSettingsUi = Long.MIN_VALUE;
			return;
		}
		kickoffFetch();
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		if (GraphicsEnvironment.isHeadless())
		{
			return;
		}
		long now = System.currentTimeMillis();
		synchronized (this)
		{
			if (lastDebouncedRefreshMs != 0L && now - lastDebouncedRefreshMs < LOGIN_REFRESH_DEBOUNCE_MS)
			{
				return;
			}
			lastDebouncedRefreshMs = now;
		}
		manifestStore.downloadAsync(this::validateSchemaQuietly);
		if (config.hubGroupSyncEnabled() && groupSlugAndMemberCodeFilled(config))
		{
			runLoginVerification();
		}
	}

	@Subscribe
	public void onMenuEntryAdded(MenuEntryAdded event)
	{
		MenuEntry e = event.getMenuEntry();
		plainListedEntry(e);
		if (manifestStore.itemCount() == 0)
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
		maybeRefreshAssignedColorRowGreyState();
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

	/** Plain labels + strip restricted verbs from the live menu (submenus included). */
	private void stripOpenMenu()
	{
		if (manifestStore.itemCount() == 0 || !client.isMenuOpen())
		{
			return;
		}
		Menu root = client.getMenu();
		plainMenuLevel(root);
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
		if (manifestStore.itemCount() == 0)
		{
			return;
		}
		int itemId = ColorLockItemIdResolver.resolve(e, client);
		if (itemId <= 0)
		{
			return;
		}
		ManifestItem row = manifestStore.getListedManifestItem(itemId, itemManager);
		if (row == null || row.getUsableColors().isEmpty() || !ManifestRules.isLockEnforced(row))
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
		if (manifestStore.itemCount() == 0)
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
		if (manifestStore.isRestrictedForAssignment(itemId, assignment(), itemManager))
		{
			event.consume();
		}
	}

	private boolean restrictUseMenuEntry(MenuEntry e)
	{
		if (manifestStore.itemCount() == 0)
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
		return manifestStore.isRestrictedForAssignment(itemId, assignment(), itemManager);
	}

	private boolean shouldStripUseMenuEntry(MenuEntry e)
	{
		return restrictUseMenuEntry(e);
	}

	private void kickoffRemoteRefreshCycle()
	{
		if (GraphicsEnvironment.isHeadless())
		{
			return;
		}
		manifestStore.downloadAsync(() -> validateSchemaQuietly());
	}

	private void schedulePeriodicManifestRefresh()
	{
		cancelPeriodicManifestRefresh();
		int minutes = MANIFEST_REFRESH_INTERVAL_MINUTES;
		if (GraphicsEnvironment.isHeadless())
		{
			return;
		}
		if (refreshScheduler == null)
		{
			refreshScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
				Thread t = new Thread(r, "osrs-color-lock-periodic");
				t.setDaemon(true);
				return t;
			});
		}
		scheduledManifestRefresh = refreshScheduler.scheduleAtFixedRate(
			() -> {
				if (GraphicsEnvironment.isHeadless())
				{
					return;
				}
				kickoffRemoteRefreshCycle();
			},
			minutes,
			minutes,
			TimeUnit.MINUTES
		);
	}

	private void cancelPeriodicManifestRefresh()
	{
		if (scheduledManifestRefresh != null)
		{
			scheduledManifestRefresh.cancel(false);
			scheduledManifestRefresh = null;
		}
	}

	private void kickoffFetch()
	{
		if (GraphicsEnvironment.isHeadless())
		{
			return;
		}
		manifestStore.downloadAsync(() -> validateSchemaQuietly());
	}

	/** One shot: auth + state, then manifest, then mirror hub-assigned color into config. Runs only on checkbox-on. */
	private void runOneShotHubSyncThenManifestAndMirror()
	{
		if (GraphicsEnvironment.isHeadless())
		{
			return;
		}
		groupSync.refreshAsync(config, () -> {
			mirrorHubAssignedColorIntoConfig();
			manifestStore.downloadAsync(this::validateSchemaQuietly);
		});
	}

	/** Login-only verification: re-auth + state, then banner in chat if anything changed/blocked. */
	private void runLoginVerification()
	{
		if (GraphicsEnvironment.isHeadless())
		{
			return;
		}
		ColorLockColor savedBefore = config.assignedColor();
		groupSync.refreshAsync(config, () -> {
			mirrorHubAssignedColorIntoConfig();
			postLoginVerificationBanner(savedBefore);
		});
	}

	private void mirrorHubAssignedColorIntoConfig()
	{
		ColorLockColor hub = groupSync.getHubAssignedColor();
		if (hub == null)
		{
			return;
		}
		if (hub == config.assignedColor())
		{
			return;
		}
		configManager.setConfiguration("colorlockhelper", "assignedColor", hub.name());
		lastSeenHubPresentationEpochInSettingsUi = Long.MIN_VALUE;
	}

	private void postLoginVerificationBanner(ColorLockColor savedBefore)
	{
		int auth = groupSync.getLastAuthHttpStatus();
		int state = groupSync.getLastStateHttpStatus();
		boolean ok = groupSync.isResolvedOk();
		boolean active = groupSync.isLastMemberActive();
		ColorLockColor hubNow = groupSync.getHubAssignedColor();

		String msg = null;
		if (auth == 401 || auth == 403)
		{
			msg = "Color Locked: hub auth rejected (HTTP " + auth + "). Check Group code, Member code, and password.";
		}
		else if (auth == 404)
		{
			msg = "Color Locked: hub does not recognise this Group code.";
		}
		else if (auth != java.net.HttpURLConnection.HTTP_OK)
		{
			msg = "Color Locked: could not reach hub (auth status " + auth + "). Sync will retry next login.";
		}
		else if (!active)
		{
			msg = "Color Locked: your hub membership is not active. Manual color-lock is in effect.";
		}
		else if (!ok)
		{
			msg = "Color Locked: hub state read failed (HTTP " + state + "). Color may be stale.";
		}
		else if (hubNow != null && savedBefore != null && hubNow != savedBefore)
		{
			msg = "Color Locked: hub changed your assignment to " + hubNow.getKey()
				+ " (was " + savedBefore.getKey() + "). Settings updated.";
		}
		if (msg == null)
		{
			return;
		}
		final String chatLine = msg;
		clientThread.invokeLater(() -> chatMessageManager.queue(QueuedMessage.builder()
			.type(ChatMessageType.GAMEMESSAGE)
			.runeLiteFormattedMessage("<col=ff5555>" + chatLine + "</col>")
			.build()));
		log.warning(chatLine);
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

	private static boolean parseConfigBoolean(String raw)
	{
		return raw != null && Boolean.parseBoolean(raw.trim());
	}

	private static boolean groupSlugAndMemberCodeFilled(ColorLockConfig cfg)
	{
		if (cfg == null)
		{
			return false;
		}
		String slug = cfg.groupSlug() == null ? "" : cfg.groupSlug().trim();
		String code = cfg.memberPublicCode() == null ? "" : cfg.memberPublicCode().trim();
		return !slug.isEmpty() && !code.isEmpty();
	}

	private void maybeRefreshAssignedColorRowGreyState()
	{
		if (GraphicsEnvironment.isHeadless())
		{
			return;
		}
		long now = System.currentTimeMillis();
		long epoch = groupSync.hubPresentationEpoch();
		boolean epochChanged = epoch != lastSeenHubPresentationEpochInSettingsUi;
		if (!epochChanged && now - lastMuteSwingMs < 500L)
		{
			return;
		}
		lastSeenHubPresentationEpochInSettingsUi = epoch;
		lastMuteSwingMs = now;
		final boolean mute = groupSync.hubOverridesManualAssignedColor(config);
		SwingUtilities.invokeLater(() -> applyAssignedColorRowMuteInConfigPanels(mute));
	}

	private static void applyAssignedColorRowMuteInConfigPanels(boolean disableManualPicker)
	{
		String wantTitle = ColorLockConfig.ASSIGNED_COLOR_CONFIG_NAME;
		for (Window w : Window.getWindows())
		{
			if (w == null || !w.isDisplayable())
			{
				continue;
			}
			JLabel rowLabel = findJLabelMatchingText(w, wantTitle);
			if (rowLabel == null)
			{
				continue;
			}
			Container rowPanel = rowLabel.getParent();
			if (!(rowPanel instanceof JPanel))
			{
				continue;
			}
			JPanel jp = (JPanel) rowPanel;
			if (!(jp.getLayout() instanceof BorderLayout))
			{
				continue;
			}
			BorderLayout bl = (BorderLayout) jp.getLayout();
			Component east = bl.getLayoutComponent(BorderLayout.EAST);
			if (!(east instanceof JComboBox<?>))
			{
				continue;
			}
			rowLabel.setEnabled(!disableManualPicker);
			east.setEnabled(!disableManualPicker);
			rowLabel.setForeground(disableManualPicker
				? ColorScheme.MEDIUM_GRAY_COLOR : ColorScheme.TEXT_COLOR);
			return;
		}
	}

	private static JLabel findJLabelMatchingText(Component root, String exact)
	{
		if (root instanceof JLabel)
		{
			String t = normalizeLabelText(((JLabel) root).getText());
			if (exact.equals(t))
			{
				return (JLabel) root;
			}
		}
		if (root instanceof Container)
		{
			for (Component ch : ((Container) root).getComponents())
			{
				JLabel hit = findJLabelMatchingText(ch, exact);
				if (hit != null)
				{
					return hit;
				}
			}
		}
		return null;
	}

	private static String normalizeLabelText(String raw)
	{
		if (raw == null)
		{
			return "";
		}
		String noTags = raw.replaceAll("<[^>]*>", "").trim();
		String oneLine = noTags.replace('\n', ' ').trim();
		return oneLine.replaceAll("\\s+", " ");
	}
}
