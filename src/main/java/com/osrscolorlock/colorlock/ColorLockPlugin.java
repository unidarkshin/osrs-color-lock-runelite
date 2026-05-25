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
import javax.swing.Timer;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
import net.runelite.api.Skill;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatColorType;
import net.runelite.client.chat.ChatMessageBuilder;
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
@PluginDescriptor(
	name = "Color Locked",
	description = "Group Iron color-lock enforcement. Blocks Eat/Drink/Equip/Wield/Wear/Release on items restricted by your "
		+ "assigned color. Strips Mine/Chop when carrying a restricted pickaxe or axe. Marks restricted items with a red "
		+ "corner mark in inventory, bank, and worn equipment. Includes a sidebar for looking up which items your color can "
		+ "use and viewing drop sources. Syncs your assigned color and item rules from the Color Lock hub "
		+ "(group.thegrandchart.com) — when sync is on, your RuneScape display name and skill stats are sent to the hub. "
		+ "Also works standalone with manual settings.",
	tags = {"ironman", "gim", "color-lock", "groupiron", "color", "lock", "restriction"}
)
public class ColorLockPlugin extends Plugin
{
	private static final Logger log = LoggerFactory.getLogger(ColorLockPlugin.class);

	static final int EXPECTED_SCHEMA_VERSION = ColorLockApiContracts.EXPECTED_ITEMS_JSON_SCHEMA_VERSION;

	private static final java.util.Set<String> HUB_CRED_KEYS = java.util.Set.of(
		"groupSlug", "groupJoinPasscode", "memberPublicCode");

	private static final java.util.Set<String> MANUAL_ITEM_FILTER_KEYS = java.util.Set.of(
		"manualLockPotionsToColors", "manualIncludeFood", "manualLockAmmunitionToColors");

	/**
	 * RuneLite replays the active profile by re-firing {@code ConfigChanged} for every key on
	 * plugin startup / profile activation. Ignore credential-key change events for this many ms
	 * after {@link #startUp()} so we don't auto-disable sync from a profile-replay event the
	 * user never triggered.
	 */
	private static final long CRED_CHANGE_GRACE_MS = 2_500L;

	/** Fixed background interval for manifest refresh (no config UI). */
	private static final int MANIFEST_REFRESH_INTERVAL_MINUTES = 60;

	/** Presence heartbeat to PATCH /api/plugin/v1/me; hub considers a member stale after ~180s. */
	private static final int ME_HEARTBEAT_INTERVAL_SECONDS = 60;

	/** Strip config keys we no longer expose so old profiles don't carry stale state. */
	private void purgeDeprecatedConfigKeys()
	{
		for (String key : new String[]{"itemsUrl", "syncToGroupAction", "manualLockFoodToColors"})
		{
			if (configManager.getConfiguration("colorlockhelper", key) != null)
			{
				configManager.unsetConfiguration("colorlockhelper", key);
			}
		}
	}

	/** Default manual item list includes food ({@code excludeFood=0}). Migrates {@code manualLockFoodToColors}. */
	private void migrateManualIncludeFoodConfig()
	{
		if (configManager.getConfiguration("colorlockhelper", "manualIncludeFood") != null)
		{
			return;
		}
		String legacy = configManager.getConfiguration("colorlockhelper", "manualLockFoodToColors");
		if (legacy != null)
		{
			configManager.setConfiguration("colorlockhelper", "manualIncludeFood", legacy);
			return;
		}
		configManager.setConfiguration("colorlockhelper", "manualIncludeFood", Boolean.TRUE.toString());
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
	private ScheduledExecutorService scheduledExecutor;

	@Inject
	private Provider<ColorLockLookupPanel> lookupPanelProvider;

	private NavigationButton lookupNavButton;

	private static final long LOGIN_REFRESH_DEBOUNCE_MS = 10_000L;
	private static final long SETTINGS_OPEN_RESYNC_DEBOUNCE_MS = 30_000L;

	private ScheduledFuture<?> scheduledManifestRefresh;
	private ScheduledFuture<?> scheduledMeHeartbeat;
	private volatile long lastDebouncedRefreshMs;
	private volatile long lastSettingsOpenResyncMs;
	private volatile boolean settingsPanelOpenLastTick;
	/** Non-null when the user just toggled hub sync; consumed by the next heartbeat to inform the hub. */
	private volatile Boolean pendingSyncToggle;
	/**
	 * Wall-clock millis after which {@link #onConfigChanged} will react to credential-key edits.
	 * Set in {@link #startUp()} to {@code now + CRED_CHANGE_GRACE_MS} to swallow RuneLite's
	 * profile-replay events.
	 */
	private volatile long credChangeArmedAtMs;
	private Timer settingsMuteTimer;

	@Provides
	ColorLockConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(ColorLockConfig.class);
	}

	@Override
	protected void startUp()
	{
		credChangeArmedAtMs = System.currentTimeMillis() + CRED_CHANGE_GRACE_MS;
		migrateManualIncludeFoodConfig();
		purgeDeprecatedConfigKeys();
		overlayManager.add(itemOverlay);
		lookupNavButton = NavigationButton.builder()
			.tooltip("Color Locked lookup")
			.icon(ColorLockLookupPanel.createNavIcon())
			.priority(7)
			.panel(lookupPanelProvider.get())
			.build();
		clientToolbar.addNavigation(lookupNavButton);
		kickoffFetch();
		lastDebouncedRefreshMs = 0L;
		schedulePeriodicManifestRefresh();
		schedulePeriodicMeHeartbeat();
		startSettingsMuteTimer();
		if (config.hubGroupSyncEnabled() && hubCredentialsFilled(config)
			&& client.getGameState() == GameState.LOGGED_IN)
		{
			runLoginVerification();
		}
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
			try
			{
				clientToolbar.removeNavigation(lookupNavButton);
			}
			catch (RuntimeException ex)
			{
				log.debug("removeNavigation failed", ex);
			}
			lookupNavButton = null;
		}
		cancelPeriodicManifestRefresh();
		cancelPeriodicMeHeartbeat();
		stopSettingsMuteTimer();
		sendPresenceOfflineBestEffort();
	}

	private void startSettingsMuteTimer()
	{
		if (GraphicsEnvironment.isHeadless())
		{
			return;
		}
		stopSettingsMuteTimer();
		settingsMuteTimer = new Timer(500, e -> {
			boolean mute = groupSync.hubOverridesManualAssignedColor(config);
			boolean panelOpen = applyAssignedColorRowMuteInConfigPanels(mute);
			applyManualItemFilterRowsVisibility(!config.hubGroupSyncEnabled());
			applyLegacyMemberCodeRowVisibility();
			boolean wasOpen = settingsPanelOpenLastTick;
			settingsPanelOpenLastTick = panelOpen;
			if (panelOpen && !wasOpen)
			{
				maybeResyncOnSettingsOpened();
			}
		});
		settingsMuteTimer.setRepeats(true);
		settingsMuteTimer.start();
	}

	private void stopSettingsMuteTimer()
	{
		if (settingsMuteTimer != null)
		{
			settingsMuteTimer.stop();
			settingsMuteTimer = null;
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
		if (HUB_CRED_KEYS.contains(key))
		{
			if (System.currentTimeMillis() < credChangeArmedAtMs)
			{
				log.debug("Ignoring credential-key replay during startup grace: {}", key);
				return;
			}
			if (config.hubGroupSyncEnabled())
			{
				SwingUtilities.invokeLater(() ->
					configManager.setConfiguration(evt.getGroup(), "hubGroupSyncEnabled", Boolean.FALSE.toString()));
				groupSync.clearHubSessionBlocking();
				postChatBanner("Color Locked: credentials changed - sync disabled. Re-check Sync with group to re-auth.");
			}
			return;
		}
		if ("hubGroupSyncEnabled".equals(key))
		{
			boolean on = parseConfigBoolean(evt.getNewValue());
			if (on)
			{
				if (!hubCredentialsFilled(config))
				{
					log.warn("Sync needs Group auth code. Checkbox turned off.");
					SwingUtilities.invokeLater(() ->
						configManager.setConfiguration(evt.getGroup(), key, Boolean.FALSE.toString()));
					groupSync.clearHubSessionBlocking();
					postChatBanner("Color Locked: enter your Group auth code (e.g. GeckoGlacier38#0723) before enabling sync.");
					return;
				}
				pendingSyncToggle = Boolean.TRUE;
				runOneShotHubSyncThenManifestAndMirror();
			}
			else
			{
				// Fire-and-forget the sync.enabled=false PATCH first, then clear the JWT in the
				// callback so the executor thread still has a valid token to authenticate with.
				reportSyncDisabledToHubBestEffort(() -> {
					groupSync.clearHubSessionBlocking();
					pendingSyncToggle = null;
				});
				postChatBanner("Color Locked: sync disabled. Manual color-lock is in effect.");
				scheduleManifestRefetchForManualFilters();
			}
			return;
		}
		if (MANUAL_ITEM_FILTER_KEYS.contains(key))
		{
			if (!config.hubGroupSyncEnabled())
			{
				groupSync.clearItemsUrlOverride();
				scheduleManifestRefetchForManualFilters();
			}
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
		kickoffFetch();
		if (config.hubGroupSyncEnabled() && hubCredentialsFilled(config))
		{
			runLoginVerification();
		}
	}

	@Subscribe
	public void onMenuEntryAdded(MenuEntryAdded event)
	{
		MenuEntry e = event.getMenuEntry();
		if (manifestStore.itemCount() == 0)
		{
			return;
		}
		if (!shouldStripMenuEntry(e))
		{
			return;
		}
		// Left-click default skips deprioritized ops; right-click list still stripped each frame.
		// Tags on target text are preserved so native OSRS color highlighting stays intact.
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

	/** Strip restricted verbs from the live menu (submenus included). Tags on names are preserved. */
	private void stripOpenMenu()
	{
		if (manifestStore.itemCount() == 0 || !client.isMenuOpen())
		{
			return;
		}
		stripMenuLevel(client.getMenu());
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
		for (MenuEntry e : entries)
		{
			if (shouldStripMenuEntry(e))
			{
				e.setOption("");
				e.setTarget("");
				menu.removeMenuEntry(e);
			}
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
		MenuEntry me = event.getMenuEntry();
		if (shouldStripMenuEntry(me))
		{
			event.consume();
		}
	}

	private boolean shouldStripMenuEntry(MenuEntry e)
	{
		if (manifestStore.itemCount() == 0)
		{
			return false;
		}
		if (restrictInventoryUseMenuEntry(e))
		{
			return true;
		}
		return ColorLockSkillingGate.shouldStripGatherMenu(client, itemManager, manifestStore, assignment(), e);
	}

	private boolean restrictInventoryUseMenuEntry(MenuEntry e)
	{
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

	private void schedulePeriodicManifestRefresh()
	{
		cancelPeriodicManifestRefresh();
		if (GraphicsEnvironment.isHeadless())
		{
			return;
		}
		int minutes = MANIFEST_REFRESH_INTERVAL_MINUTES;
		scheduledManifestRefresh = scheduledExecutor.scheduleAtFixedRate(
			this::kickoffFetch,
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

	private void schedulePeriodicMeHeartbeat()
	{
		cancelPeriodicMeHeartbeat();
		if (GraphicsEnvironment.isHeadless())
		{
			return;
		}
		scheduledMeHeartbeat = scheduledExecutor.scheduleAtFixedRate(
			this::tickMeHeartbeat,
			ME_HEARTBEAT_INTERVAL_SECONDS,
			ME_HEARTBEAT_INTERVAL_SECONDS,
			TimeUnit.SECONDS
		);
	}

	private void cancelPeriodicMeHeartbeat()
	{
		if (scheduledMeHeartbeat != null)
		{
			scheduledMeHeartbeat.cancel(false);
			scheduledMeHeartbeat = null;
		}
	}

	private void tickMeHeartbeat()
	{
		if (!config.hubGroupSyncEnabled() || !hubCredentialsFilled(config))
		{
			return;
		}
		if (!groupSync.isResolvedOk())
		{
			return;
		}
		clientThread.invokeLater(() -> {
			if (client.getGameState() != GameState.LOGGED_IN)
			{
				return;
			}
			String name = client.getLocalPlayer() != null ? client.getLocalPlayer().getName() : null;
			if (name == null || name.isBlank())
			{
				return;
			}
			String currentColorKey = currentEffectiveColorKey();
			Boolean toggle = pendingSyncToggle;
			pendingSyncToggle = null;
			java.util.Map<String, Integer> stats = gatherStats();
			groupSync.patchMeAsync(config, name, true, currentColorKey, toggle, stats,
				this::pullStateAndMirrorColorOnHeartbeat);
		});
	}

	/**
	 * Fire the {@code sync.enabled=false} PATCH so the hub timestamps the toggle and drops the
	 * "Online" flag, then invoke {@code onPatchFinished} (always — even if we never sent the
	 * request because there's no JWT / no player name yet) so the caller can clean up.
	 */
	private void reportSyncDisabledToHubBestEffort(Runnable onPatchFinished)
	{
		Runnable done = onPatchFinished == null ? () -> { } : onPatchFinished;
		if (!groupSync.isResolvedOk())
		{
			done.run();
			return;
		}
		String name = null;
		try
		{
			name = client.getLocalPlayer() != null ? client.getLocalPlayer().getName() : null;
		}
		catch (RuntimeException ignored)
		{
		}
		if (name == null || name.isBlank())
		{
			done.run();
			return;
		}
		groupSync.patchMeAsync(config, name, false, currentEffectiveColorKey(), Boolean.FALSE, null, done);
	}

	private String currentEffectiveColorKey()
	{
		ColorLockColor c = groupSync.effectiveAssignment(config);
		return c == null ? null : c.getKey();
	}

	private java.util.Map<String, Integer> gatherStats()
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return null;
		}
		java.util.Map<String, Integer> stats = new java.util.LinkedHashMap<>();
		for (Skill skill : Skill.values())
		{
			if (skill == Skill.OVERALL)
			{
				continue;
			}
			stats.put(skill.getName().toLowerCase(java.util.Locale.ENGLISH), client.getRealSkillLevel(skill));
		}
		stats.put("hitpoints_current", client.getBoostedSkillLevel(Skill.HITPOINTS));
		stats.put("prayer_current", client.getBoostedSkillLevel(Skill.PRAYER));
		return stats;
	}

	/**
	 * After every heartbeat, refresh group state so a hub-side {@code member.assignedColor} change
	 * propagates without requiring a settings reopen or re-sync.
	 */
	private void pullStateAndMirrorColorOnHeartbeat()
	{
		final ColorLockColor before = groupSync.getHubAssignedColor();
		groupSync.pollStateAsync(config, () -> {
			ColorLockColor after = groupSync.getHubAssignedColor();
			boolean colorChanged = after != null && before != after;
			if (after != null)
			{
				mirrorHubAssignedColorIntoConfig();
			}
			if (colorChanged)
			{
				String slug = config.groupSlug() == null ? "" : config.groupSlug().trim();
				String fromKey = before == null ? "(none)" : before.getKey();
				postChatBanner("Color Locked: hub changed your color to " + after.getKey()
					+ " (was " + fromKey + ") for group " + slug + ".");
			}
			if (groupSync.consumeGroupItemPolicyDirty())
			{
				reloadManifestAfterHubItemPolicyChange();
			}
		});
	}

	/** Hub potion/food/ammo toggles changed on /state — refetch items with current Bearer policy. */
	private void reloadManifestAfterHubItemPolicyChange()
	{
		if (!config.hubGroupSyncEnabled())
		{
			return;
		}
		final String prevUrl = manifestStore.lastLoadedManifestUrl();
		final int prevSchema = manifestStore.manifestSchemaVersion();
		manifestStore.downloadAsync(() -> {
			validateSchemaQuietly();
			postItemsMismatchBannerIfChanged(prevUrl, prevSchema);
		});
		postChatBanner("Color Locked: hub item rules changed — rules reloaded.");
	}

	private void sendPresenceOfflineBestEffort()
	{
		if (!config.hubGroupSyncEnabled() || !hubCredentialsFilled(config))
		{
			return;
		}
		if (!groupSync.isResolvedOk())
		{
			return;
		}
		String name = null;
		try
		{
			name = client.getLocalPlayer() != null ? client.getLocalPlayer().getName() : null;
		}
		catch (RuntimeException ignored)
		{
		}
		if (name == null || name.isBlank())
		{
			return;
		}
		groupSync.patchMeAsync(config, name, false, currentEffectiveColorKey(), null);
	}

	private void kickoffFetch()
	{
		if (GraphicsEnvironment.isHeadless())
		{
			return;
		}
		manifestStore.downloadAsync(() -> validateSchemaQuietly());
	}

	/** After config is committed — manual filter toggles and sync-off need a fresh items URL. */
	private void scheduleManifestRefetchForManualFilters()
	{
		if (GraphicsEnvironment.isHeadless())
		{
			return;
		}
		clientThread.invokeLater(this::kickoffFetch);
	}

	/** One shot: auth + state, then manifest, then mirror hub-assigned color into config. Runs only on checkbox-on. */
	private void runOneShotHubSyncThenManifestAndMirror()
	{
		if (GraphicsEnvironment.isHeadless())
		{
			return;
		}
		final ColorLockColor savedBefore = config.assignedColor();
		final String prevItemsUrl = manifestStore.lastLoadedManifestUrl();
		final int prevSchema = manifestStore.manifestSchemaVersion();
		groupSync.refreshAsync(config, () -> {
			mirrorHubAssignedColorIntoConfig();
			postSyncBanner(savedBefore, "Sync with group");
			pushMeHeartbeatNowAndShortRetry();
			manifestStore.downloadAsync(() -> {
				validateSchemaQuietly();
				postItemsMismatchBannerIfChanged(prevItemsUrl, prevSchema);
			});
		});
	}

	/** Login-only verification: re-auth + state, then banner in chat if anything changed/blocked. */
	private void runLoginVerification()
	{
		if (GraphicsEnvironment.isHeadless())
		{
			return;
		}
		final ColorLockColor savedBefore = config.assignedColor();
		final String prevItemsUrl = manifestStore.lastLoadedManifestUrl();
		final int prevSchema = manifestStore.manifestSchemaVersion();
		groupSync.refreshAsync(config, () -> {
			mirrorHubAssignedColorIntoConfig();
			postSyncBanner(savedBefore, "Login check");
			pushMeHeartbeatNowAndShortRetry();
			manifestStore.downloadAsync(() -> {
				validateSchemaQuietly();
				postItemsMismatchBannerIfChanged(prevItemsUrl, prevSchema);
			});
		});
	}

	/**
	 * Push the player's RS name to the hub immediately after a successful sync, plus a single 5 s retry to
	 * cover the LOGGED_IN race where {@code client.getLocalPlayer().getName()} is briefly null.
	 */
	private void pushMeHeartbeatNowAndShortRetry()
	{
		tickMeHeartbeat();
		scheduledExecutor.schedule(this::tickMeHeartbeat, 5, TimeUnit.SECONDS);
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
	}

	private void postSyncBanner(ColorLockColor savedBefore, String trigger)
	{
		int auth = groupSync.getLastAuthHttpStatus();
		int state = groupSync.getLastStateHttpStatus();
		String authErr = groupSync.getLastAuthErrorMessage();
		boolean ok = groupSync.isResolvedOk();
		boolean active = groupSync.isLastMemberActive();
		ColorLockColor hubNow = groupSync.getHubAssignedColor();

		boolean authJwtOk = auth == 200;
		boolean statelessResolveOk = auth == 404 && ok && active;
		if (!authJwtOk && !statelessResolveOk)
		{
			String friendly = mapAuthErrorToFriendlyText(auth, authErr);
			postChatBanner(trigger + ": " + friendly);
			return;
		}
		if (!active)
		{
			postChatBanner(trigger + ": your group membership is not active (likely kicked). Manual color-lock is in effect.");
			return;
		}
		if (!ok)
		{
			postChatBanner(trigger + ": hub state read failed (HTTP " + state + "). Color may be stale.");
			return;
		}
		String color = hubNow == null ? "no color yet" : hubNow.getKey();
		String slug = config.groupSlug() == null ? "" : config.groupSlug().trim();
		boolean changed = hubNow != null && savedBefore != null && hubNow != savedBefore;
		if (changed)
		{
			postChatBanner("Color Locked: " + trigger.toLowerCase() + " - hub assigned you " + color
				+ " (was " + savedBefore.getKey() + ") for group " + slug + ".");
			return;
		}
		if ("Sync with group".equalsIgnoreCase(trigger))
		{
			postChatBanner("Color Locked: synced - group " + slug + ", color " + color + ".");
		}
	}

	/**
	 * Map hub error responses to user-actionable chat lines. Hub error strings (per the
	 * webapp's {@code lookupActiveMemberByPublicCode}): "Invalid join passcode",
	 * "Group not found", "Unknown member code for this group", "Member not active for this group".
	 */
	private static String mapAuthErrorToFriendlyText(int httpStatus, String hubErr)
	{
		String e = hubErr == null ? "" : hubErr.trim();
		String low = e.toLowerCase(java.util.Locale.ENGLISH);

		if (low.contains("invalid join passcode") || low.contains("join passcode"))
		{
			return "wrong Group password. If the group doesn't use one, clear that field.";
		}
		if (low.contains("unknown member code"))
		{
			return "Member code is not recognised for this group.";
		}
		if (low.contains("group not found"))
		{
			return "Group code is not recognised by the hub.";
		}
		if (low.contains("member not active"))
		{
			return "your group membership is not active (likely kicked). Manual color-lock is in effect.";
		}
		if (low.contains("invalid access code"))
		{
			return "invalid Group/Member code format. Use Group slug + Member code (#0000), or paste GroupSlug#0000 in Group code.";
		}
		if (low.contains("publiccode required") || low.contains("slug or inviteurl")
			|| low.contains("accesscode or"))
		{
			return "missing required field on auth request: " + e;
		}
		if (httpStatus == 400 && !e.isEmpty())
		{
			return e;
		}
		if (httpStatus == 502 || httpStatus == 503)
		{
			return "hub temporarily unavailable (HTTP " + httpStatus + "). Item rules may be stale until retry succeeds.";
		}
		if (low.contains("storage") || low.contains("redis") || httpStatus == 503)
		{
			return "hub storage is unavailable right now. Try again in a minute.";
		}
		if (httpStatus == 401)
		{
			return "hub auth rejected (401). " + (e.isEmpty() ? "Check Group code and Member code." : e);
		}
		if (httpStatus == 403)
		{
			return e.isEmpty()
				? "hub refused this account (403). Check Group password if the group has one."
				: "hub refused this account (403): " + e;
		}
		if (httpStatus == 404)
		{
			return "hub does not recognise this Group code or Member code.";
		}
		if (httpStatus <= 0)
		{
			return "could not reach the hub. Check your internet connection.";
		}
		return "hub error (HTTP " + httpStatus + ")" + (e.isEmpty() ? "." : ": " + e);
	}

	private void postItemsMismatchBannerIfChanged(String prevUrl, int prevSchema)
	{
		String err = manifestStore.lastLoadError();
		if (err != null)
		{
			postChatBanner("Color Locked: item list reload failed (" + err + ").");
			return;
		}
		int curSchema = manifestStore.manifestSchemaVersion();
		StringBuilder parts = new StringBuilder();
		if (prevSchema > 0 && curSchema > 0 && prevSchema != curSchema)
		{
			parts.append("schema ").append(prevSchema).append(" -> ").append(curSchema);
		}
		if (curSchema > 0 && curSchema != EXPECTED_SCHEMA_VERSION)
		{
			if (parts.length() > 0) parts.append("; ");
			parts.append("plugin update may be needed (schema ").append(curSchema)
				.append(" vs expected ").append(EXPECTED_SCHEMA_VERSION).append(")");
		}
		if (parts.length() > 0)
		{
			postChatBanner("Color Locked: items updated (" + parts + ").");
		}
	}

	private void postChatBanner(String text)
	{
		final String chatLine = text;
		final String body;
		String prefix = "Color Locked: ";
		if (text != null && text.startsWith(prefix))
		{
			body = text.substring(prefix.length());
		}
		else
		{
			body = text == null ? "" : text;
		}
		final String formatted = new ChatMessageBuilder()
			.append(ChatColorType.HIGHLIGHT)
			.append("Color Locked: ")
			.append(ChatColorType.NORMAL)
			.append(body)
			.build();
		clientThread.invokeLater(() -> chatMessageManager.queue(QueuedMessage.builder()
			.type(ChatMessageType.GAMEMESSAGE)
			.runeLiteFormattedMessage(formatted)
			.build()));
		log.info(chatLine);
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
			log.warn("Items schemaVersion={} plugin expects {} - see DATA_CONTRACT.md", v, EXPECTED_SCHEMA_VERSION);
		}
	}

	private static boolean parseConfigBoolean(String raw)
	{
		return raw != null && Boolean.parseBoolean(raw.trim());
	}

	private static boolean hubCredentialsFilled(ColorLockConfig cfg)
	{
		return ColorLockCredentials.from(cfg).isFilled();
	}

	private void applyManualItemFilterRowsVisibility(boolean show)
	{
		applyConfigRowVisibility(ColorLockConfig.MANUAL_LOCK_POTIONS_CONFIG_NAME, show);
		applyConfigRowVisibility(ColorLockConfig.MANUAL_INCLUDE_FOOD_CONFIG_NAME, show);
		applyConfigRowVisibility(ColorLockConfig.MANUAL_LOCK_AMMUNITION_CONFIG_NAME, show);
	}

	/** Hide legacy member row when a Slug#0000 access code is already in the group field. */
	private void applyLegacyMemberCodeRowVisibility()
	{
		ColorLockCredentials cred = ColorLockCredentials.from(config);
		applyConfigRowVisibility("Member code (legacy)", cred.accessCode == null);
	}

	private void applyConfigRowVisibility(String configRowTitle, boolean show)
	{
		for (Window w : Window.getWindows())
		{
			if (w == null || !w.isDisplayable() || !w.isShowing())
			{
				continue;
			}
			JLabel rowLabel = findJLabelMatchingText(w, configRowTitle);
			if (rowLabel == null)
			{
				continue;
			}
			Container rowPanel = rowLabel.getParent();
			if (rowPanel != null)
			{
				rowPanel.setVisible(show);
			}
		}
	}

	/** @return {@code true} when the plugin's settings panel (the "Your color lock" row) is visible. */
	private boolean applyAssignedColorRowMuteInConfigPanels(boolean disableManualPicker)
	{
		String wantTitle = ColorLockConfig.ASSIGNED_COLOR_CONFIG_NAME;
		ColorLockColor want = config.assignedColor();
		for (Window w : Window.getWindows())
		{
			if (w == null || !w.isDisplayable() || !w.isShowing())
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
			JComboBox<?> combo = (JComboBox<?>) east;
			rowLabel.setEnabled(!disableManualPicker);
			combo.setEnabled(!disableManualPicker);
			rowLabel.setForeground(disableManualPicker
				? ColorScheme.MEDIUM_GRAY_COLOR : ColorScheme.TEXT_COLOR);
			if (want != null && combo.getSelectedItem() != want)
			{
				combo.setSelectedItem(want);
			}
			return true;
		}
		return false;
	}

	private void maybeResyncOnSettingsOpened()
	{
		if (!config.hubGroupSyncEnabled() || !hubCredentialsFilled(config))
		{
			return;
		}
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		long now = System.currentTimeMillis();
		if (lastSettingsOpenResyncMs != 0L && now - lastSettingsOpenResyncMs < SETTINGS_OPEN_RESYNC_DEBOUNCE_MS)
		{
			return;
		}
		lastSettingsOpenResyncMs = now;
		runLoginVerification();
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
