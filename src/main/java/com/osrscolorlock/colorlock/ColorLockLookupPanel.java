package com.osrscolorlock.colorlock;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.Container;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JCheckBox;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextField;
import javax.swing.JViewport;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

import net.runelite.api.Client;
import net.runelite.api.ItemComposition;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.LinkBrowser;
import net.runelite.client.util.Text;

@Singleton
public class ColorLockLookupPanel extends PluginPanel
{
	private static final int MAX_RESULTS = 80;
	private static final int ICON_SLOT = 36;
	/** Before the panel is in the hierarchy, use a fraction of the screen as a height hint. */
	private static final int MIN_LOOKUP_PANEL_HEIGHT = 480;

	private static final Set<String> LOOKUP_ACTIONABLE_CATEGORIES = Set.of(
		"weapon", "armour", "food", "potion", "ammo"
	);

	private static boolean parseStoredToggle(ConfigManager cm, String key, boolean defaultValue)
	{
		String raw = cm.getConfiguration("colorlockhelper", key);
		if (raw == null || raw.isEmpty())
		{
			return defaultValue;
		}
		return Boolean.parseBoolean(raw);
	}

	private final Client client;
	private final ClientThread clientThread;
	private final ManifestStore manifestStore;
	private final ItemManager itemManager;
	private final ColorLockConfig config;
	private final ConfigManager configManager;
	private final ColorLockGroupSync groupSync;

	private final JTextField queryField = new JTextField(18);
	private final JButton searchButton = new JButton("Search");
	private final JCheckBox myPaletteOnlyCheckbox = new JCheckBox("My palette only");
	private final JCheckBox allColorsListingsCheckbox = new JCheckBox("All-colors only");
	private final JPanel resultsPanel;
	private final JTabbedPane tabs = new JTabbedPane();
	private final JPanel groupRosterColumn = new JPanel();
	private final JLabel groupHeaderLabel = new JLabel(" ", SwingConstants.LEFT);
	private final JPanel groupEnabledColorsRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
	private final JLabel groupStateLabel = new JLabel(" ", SwingConstants.LEFT);
	private Timer groupRefreshTimer;
	private long lastRenderedStateMs;

	@Inject
	ColorLockLookupPanel(
		Client client,
		ClientThread clientThread,
		ManifestStore manifestStore,
		ItemManager itemManager,
		ColorLockConfig config,
		ConfigManager configManager,
		ColorLockGroupSync groupSync)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.manifestStore = manifestStore;
		this.itemManager = itemManager;
		this.config = config;
		this.configManager = configManager;
		this.groupSync = groupSync;

		setLayout(new BorderLayout());

		resultsPanel = new JPanel();
		resultsPanel.setLayout(new BoxLayout(resultsPanel, BoxLayout.Y_AXIS));
		resultsPanel.setAlignmentX(Component.LEFT_ALIGNMENT);

		JPanel itemsTab = new JPanel(new BorderLayout());

		JScrollPane scroll = new JScrollPane(
			resultsPanel,
			ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
			ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.getVerticalScrollBar().setUnitIncrement(16);

		JPanel north = new JPanel();
		north.setLayout(new BoxLayout(north, BoxLayout.Y_AXIS));
		north.setAlignmentX(Component.LEFT_ALIGNMENT);
		JPanel searchRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
		searchRow.add(new JLabel("Search"));
		searchRow.add(queryField);
		searchRow.add(searchButton);
		searchRow.setAlignmentX(Component.LEFT_ALIGNMENT);
		north.add(searchRow);

		boolean palCfg = parseStoredToggle(configManager, "lookupMyPaletteOnly", false);
		boolean optOutCfg = parseStoredToggle(configManager, "lookupAllColorsRowsOnly", false);
		if (palCfg && optOutCfg)
		{
			optOutCfg = false;
			configManager.setConfiguration("colorlockhelper", "lookupAllColorsRowsOnly", "false");
		}
		myPaletteOnlyCheckbox.setSelected(palCfg);
		myPaletteOnlyCheckbox.setToolTipText(
			"Fetches hub items with usableBy=your color and groupFilters (potion/food/ammo per sync). Mutually exclusive with All-colors-only.");
		myPaletteOnlyCheckbox.setAlignmentX(Component.LEFT_ALIGNMENT);
		JPanel paletteRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
		paletteRow.setAlignmentX(Component.LEFT_ALIGNMENT);
		paletteRow.add(myPaletteOnlyCheckbox);
		north.add(paletteRow);

		allColorsListingsCheckbox.setSelected(optOutCfg);
		allColorsListingsCheckbox.setToolTipText(
			"Only hub listings that opted out (shown as \"All colors\" — potions, throwables…). Exclusive with palette filter.");
		allColorsListingsCheckbox.setAlignmentX(Component.LEFT_ALIGNMENT);
		JPanel optOutRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
		optOutRow.setAlignmentX(Component.LEFT_ALIGNMENT);
		optOutRow.add(allColorsListingsCheckbox);
		north.add(optOutRow);

		JPanel south = new JPanel();
		south.setLayout(new BoxLayout(south, BoxLayout.Y_AXIS));
		south.setAlignmentX(Component.LEFT_ALIGNMENT);
		JLabel hint0 = new JLabel("<html><body style='width:220px'>Matches client item names. Icons load from cache.</body></html>");
		hint0.setAlignmentX(Component.LEFT_ALIGNMENT);
		south.add(hint0);
		south.add(Box.createVerticalStrut(6));
		JLabel siteHead = new JLabel("<html><body style='width:220px'><b>Color Locked hub</b></body></html>");
		siteHead.setAlignmentX(Component.LEFT_ALIGNMENT);
		south.add(siteHead);
		south.add(urlLink(ColorLockWeb.HUB));
		south.add(urlLink(ColorLockWeb.ITEMS_PAGE));

		itemsTab.add(north, BorderLayout.NORTH);
		itemsTab.add(scroll, BorderLayout.CENTER);
		itemsTab.add(south, BorderLayout.SOUTH);

		tabs.addTab("Items", itemsTab);
		tabs.addTab("Group", buildGroupTab());
		tabs.addChangeListener(e -> {
			if (isGroupTabSelected())
			{
				refreshGroupTab();
			}
		});

		add(tabs, BorderLayout.CENTER);

		searchButton.addActionListener(e -> runSearch());
		queryField.addActionListener(e -> runSearch());

		myPaletteOnlyCheckbox.addActionListener(e ->
		{
			if (myPaletteOnlyCheckbox.isSelected())
			{
				allColorsListingsCheckbox.setSelected(false);
				configManager.setConfiguration("colorlockhelper", "lookupAllColorsRowsOnly", "false");
			}
			configManager.setConfiguration(
				"colorlockhelper",
				"lookupMyPaletteOnly",
				Boolean.toString(myPaletteOnlyCheckbox.isSelected()));
			runSearch();
		});

		allColorsListingsCheckbox.addActionListener(e ->
		{
			if (allColorsListingsCheckbox.isSelected())
			{
				myPaletteOnlyCheckbox.setSelected(false);
				configManager.setConfiguration("colorlockhelper", "lookupMyPaletteOnly", "false");
			}
			configManager.setConfiguration(
				"colorlockhelper",
				"lookupAllColorsRowsOnly",
				Boolean.toString(allColorsListingsCheckbox.isSelected()));
			runSearch();
		});

		if (allColorsListingsCheckbox.isSelected())
		{
			runSearch();
		}
		else
		{
			showPlaceholderIntro();
		}
	}

	@Override
	public void onActivate()
	{
		super.onActivate();
		String q = queryField.getText();
		if (allColorsListingsCheckbox.isSelected() && (q == null || q.trim().length() < 2))
		{
			runSearch();
		}
		startGroupRefreshTimer();
		if (isGroupTabSelected())
		{
			refreshGroupTab();
		}
	}

	@Override
	public void onDeactivate()
	{
		super.onDeactivate();
		stopGroupRefreshTimer();
	}

	private boolean isGroupTabSelected()
	{
		return tabs.getSelectedIndex() == 1;
	}

	private JPanel buildGroupTab()
	{
		JPanel root = new JPanel(new BorderLayout(0, 6));
		root.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));

		JPanel header = new JPanel();
		header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
		header.setAlignmentX(Component.LEFT_ALIGNMENT);

		groupHeaderLabel.setFont(groupHeaderLabel.getFont().deriveFont(Font.BOLD, 13f));
		groupHeaderLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
		header.add(groupHeaderLabel);

		groupEnabledColorsRow.setOpaque(false);
		groupEnabledColorsRow.setAlignmentX(Component.LEFT_ALIGNMENT);
		header.add(groupEnabledColorsRow);

		groupStateLabel.setForeground(new Color(160, 160, 160));
		groupStateLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
		header.add(groupStateLabel);

		JButton refreshBtn = new JButton("Refresh now");
		refreshBtn.addActionListener(e -> {
			groupStateLabel.setText("Refreshing\u2026");
			groupSync.pollStateAsync(config, () -> SwingUtilities.invokeLater(() -> {
				refreshGroupTab();
				if (groupSync.consumeGroupItemPolicyDirty())
				{
					manifestStore.downloadAsync(() -> { });
				}
			}));
		});
		JPanel refreshRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
		refreshRow.setOpaque(false);
		refreshRow.setAlignmentX(Component.LEFT_ALIGNMENT);
		refreshRow.add(refreshBtn);
		header.add(refreshRow);

		root.add(header, BorderLayout.NORTH);

		groupRosterColumn.setLayout(new BoxLayout(groupRosterColumn, BoxLayout.Y_AXIS));
		groupRosterColumn.setAlignmentX(Component.LEFT_ALIGNMENT);
		JScrollPane rosterScroll = new JScrollPane(
			groupRosterColumn,
			ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
			ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		rosterScroll.getVerticalScrollBar().setUnitIncrement(16);
		rosterScroll.setBorder(BorderFactory.createEmptyBorder());
		root.add(rosterScroll, BorderLayout.CENTER);

		return root;
	}

	private void startGroupRefreshTimer()
	{
		if (groupRefreshTimer != null)
		{
			return;
		}
		// Read-only timer: re-renders from the cached snapshot every few seconds. The actual
		// network poll piggy-backs on the heartbeat in ColorLockPlugin.
		groupRefreshTimer = new Timer(3_000, e -> {
			if (!isGroupTabSelected())
			{
				return;
			}
			long stateAt = groupSync.getLastStateAtMs();
			if (stateAt != lastRenderedStateMs)
			{
				refreshGroupTab();
			}
		});
		groupRefreshTimer.setRepeats(true);
		groupRefreshTimer.start();
	}

	private void stopGroupRefreshTimer()
	{
		if (groupRefreshTimer != null)
		{
			groupRefreshTimer.stop();
			groupRefreshTimer = null;
		}
	}

	private void refreshGroupTab()
	{
		ColorLockGroupSync.GroupSnapshot g = groupSync.getGroupSnapshot();
		List<ColorLockGroupSync.RosterMemberSnapshot> roster = groupSync.getRosterSnapshot();
		long stateAt = groupSync.getLastStateAtMs();
		lastRenderedStateMs = stateAt;

		groupHeaderLabel.setText(g == null || g.name.isEmpty() ? "Color Locked group" : g.name);

		groupEnabledColorsRow.removeAll();
		if (g != null && !g.enabledColorsLowercase.isEmpty())
		{
			JLabel pre = new JLabel("Palette: ");
			pre.setForeground(new Color(180, 180, 180));
			groupEnabledColorsRow.add(pre);
			for (String c : g.enabledColorsLowercase)
			{
				groupEnabledColorsRow.add(new PaletteChip(c));
			}
		}

		if (!config.hubGroupSyncEnabled())
		{
			groupStateLabel.setText("Sync with group is off - enable it in plugin settings to see your roster.");
		}
		else if (g == null)
		{
			groupStateLabel.setText("Waiting for hub - check Group code, Member code, and Group password.");
		}
		else if (stateAt == 0L)
		{
			groupStateLabel.setText("Roster not pulled yet - heartbeat will refresh it within ~60s.");
		}
		else
		{
			long ageSec = Math.max(0, (System.currentTimeMillis() - stateAt) / 1000L);
			groupStateLabel.setText("Last refreshed " + ageSec + "s ago.");
		}

		groupRosterColumn.removeAll();
		if (roster == null || roster.isEmpty())
		{
			JLabel none = new JLabel("<html><body style='width:200px'>No roster data yet. Press <b>Refresh now</b> or wait for the next heartbeat.</body></html>");
			none.setForeground(ROSTER_BODY_FG);
			none.setBorder(BorderFactory.createEmptyBorder(6, 4, 0, 0));
			none.setAlignmentX(Component.LEFT_ALIGNMENT);
			groupRosterColumn.add(none);
		}
		else
		{
			boolean first = true;
			for (ColorLockGroupSync.RosterMemberSnapshot row : roster)
			{
				if (!"active".equalsIgnoreCase(row.status))
				{
					continue;
				}
				if (!first)
				{
					groupRosterColumn.add(Box.createVerticalStrut(2));
				}
				first = false;
				groupRosterColumn.add(buildRosterRow(row));
			}
		}
		// Glue keeps stacked rows pinned to the top so BoxLayout doesn't stretch them apart.
		groupRosterColumn.add(Box.createVerticalGlue());
		groupRosterColumn.revalidate();
		groupRosterColumn.repaint();
		groupEnabledColorsRow.revalidate();
		groupEnabledColorsRow.repaint();
	}

	private static final Color ROSTER_BODY_FG = new Color(210, 210, 210);
	private static final Color ROSTER_MUTED_FG = new Color(155, 155, 160);
	private static final Color ROSTER_ONLINE_FG = new Color(120, 200, 130);
	private static final Color ROSTER_OFFLINE_FG = new Color(150, 150, 150);
	private static final Color ROSTER_DIVIDER = new Color(60, 60, 64);

	private JPanel buildRosterRow(ColorLockGroupSync.RosterMemberSnapshot m)
	{
		JPanel row = new JPanel();
		row.setLayout(new BoxLayout(row, BoxLayout.Y_AXIS));
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		row.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 0, 1, 0, ROSTER_DIVIDER),
			BorderFactory.createEmptyBorder(6, 2, 6, 2)));

		// Line 1: name (left) + presence badge (right).
		JPanel line1 = new JPanel(new BorderLayout(8, 0));
		line1.setOpaque(false);
		line1.setAlignmentX(Component.LEFT_ALIGNMENT);
		JLabel name = new JLabel(m.displayName.isEmpty() ? "(unnamed)" : m.displayName);
		name.setFont(name.getFont().deriveFont(Font.BOLD, 13f));
		name.setForeground(ROSTER_BODY_FG);
		line1.add(name, BorderLayout.WEST);

		JLabel presenceBadge = new JLabel(m.presenceOnline ? "● Online" : "○ Offline");
		presenceBadge.setFont(presenceBadge.getFont().deriveFont(12f));
		presenceBadge.setForeground(m.presenceOnline ? ROSTER_ONLINE_FG : ROSTER_OFFLINE_FG);
		if (!m.presenceOnline && m.presenceSummary != null && !m.presenceSummary.isEmpty())
		{
			presenceBadge.setToolTipText(m.presenceSummary);
		}
		line1.add(presenceBadge, BorderLayout.EAST);
		row.add(line1);

		// Line 2: role / sync state. Keep concise; tooltip carries the long-form details.
		String roleTxt = "creator".equalsIgnoreCase(m.role) ? "Creator" : "Member";
		String syncTxt = m.pluginSyncDisplay == null || m.pluginSyncDisplay.isEmpty()
			? "—"
			: m.pluginSyncDisplay;
		JLabel meta = new JLabel(roleTxt + "  ·  Sync: " + syncTxt);
		meta.setFont(meta.getFont().deriveFont(12f));
		meta.setForeground(ROSTER_MUTED_FG);
		meta.setAlignmentX(Component.LEFT_ALIGNMENT);
		meta.setBorder(BorderFactory.createEmptyBorder(2, 0, 0, 0));
		if (m.pluginSyncCaution)
		{
			meta.setText(meta.getText() + "  \u26A0");
			meta.setToolTipText("Plugin reported a different in-client color than the hub-assigned one.");
		}
		row.add(meta);

		// Line 3: swatch(es) on their own line so future multi-color assignments expand cleanly.
		JPanel swatchRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
		swatchRow.setOpaque(false);
		swatchRow.setAlignmentX(Component.LEFT_ALIGNMENT);
		swatchRow.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
		JLabel lockLbl = new JLabel("Lock:");
		lockLbl.setFont(lockLbl.getFont().deriveFont(12f));
		lockLbl.setForeground(ROSTER_MUTED_FG);
		swatchRow.add(lockLbl);
		List<String> assignedKeys = assignedColorKeysOf(m);
		if (assignedKeys.isEmpty())
		{
			JLabel dash = new JLabel("unassigned");
			dash.setFont(dash.getFont().deriveFont(12f));
			dash.setForeground(ROSTER_MUTED_FG);
			swatchRow.add(dash);
		}
		else
		{
			for (String key : assignedKeys)
			{
				swatchRow.add(new PaletteChip(key));
			}
		}
		row.add(swatchRow);

		// Constrain max height so BoxLayout never stretches a row to fill leftover space.
		Dimension pref = row.getPreferredSize();
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, pref.height));
		return row;
	}

	/**
	 * Hub currently sends a single {@code assignedColor}. When multi-color assignments land
	 * (e.g. {@code assignedColors: ["red","yellow"]}) this is the one place to swap in the
	 * new list - the row layout already supports N chips.
	 */
	private static List<String> assignedColorKeysOf(ColorLockGroupSync.RosterMemberSnapshot m)
	{
		if (m.assignedColorKey == null || m.assignedColorKey.isEmpty())
		{
			return java.util.Collections.emptyList();
		}
		return java.util.Collections.singletonList(m.assignedColorKey);
	}

	@Override
	public Dimension getPreferredSize()
	{
		JViewport vp = (JViewport) SwingUtilities.getAncestorOfClass(JViewport.class, this);
		int h = preferredHeightForViewport(vp);
		return new Dimension(PluginPanel.PANEL_WIDTH, h);
	}

	private static int preferredHeightForViewport(JViewport vp)
	{
		if (vp != null)
		{
			int vh = vp.getHeight();
			if (vh > 48)
			{
				return vh;
			}
		}
		int fallback = (int) (Toolkit.getDefaultToolkit().getScreenSize().height * 0.72);
		return Math.max(MIN_LOOKUP_PANEL_HEIGHT, fallback);
	}

	private void showPlaceholderIntro()
	{
		resultsPanel.removeAll();
		JLabel intro = new JLabel("<html><body style='width:220px'>Type part of a name (at least 2 letters), then <b>Search</b>. Each row lists palette colors as swatches (hover for the color name). Tick <b>All-colors only</b> with an empty search to browse every opt-out item.</body></html>");
		intro.setAlignmentX(Component.LEFT_ALIGNMENT);
		resultsPanel.add(intro);
		resultsPanel.revalidate();
		resultsPanel.repaint();
	}

	private void runSearch()
	{
		String raw = queryField.getText();
		String q = raw == null ? "" : raw.trim().toLowerCase(Locale.ENGLISH);
		final boolean browseAllColors = allColorsListingsCheckbox.isSelected() && q.length() < 2;
		if (q.length() < 2 && !browseAllColors)
		{
			SwingUtilities.invokeLater(() -> {
				resultsPanel.removeAll();
				resultsPanel.add(new JLabel("Type at least 2 characters."));
				resultsPanel.revalidate();
				resultsPanel.repaint();
			});
			return;
		}

		final String query = browseAllColors ? "" : q;

		SwingUtilities.invokeLater(() -> {
			resultsPanel.removeAll();
			resultsPanel.add(new JLabel(browseAllColors ? "Loading All-colors items…" : "Searching…"));
			resultsPanel.revalidate();
			resultsPanel.repaint();
		});

		if (myPaletteOnlyCheckbox.isSelected())
		{
			ColorLockColor lock = groupSync.effectiveAssignment(config);
			manifestStore.fetchPaletteLookupAsync(lock, rows -> collectLookupHitsFromRows(rows, query, hits ->
				SwingUtilities.invokeLater(() -> populateResults(hits, hits.size() >= MAX_RESULTS))));
			return;
		}

		clientThread.invokeLater(() -> {
			List<LookupHit> hits = new ArrayList<>();
			scanClientItemsForLookup(query, hits);
			boolean truncated = hits.size() >= MAX_RESULTS;
			SwingUtilities.invokeLater(() -> populateResults(hits, truncated));
		});
	}

	private void collectLookupHitsFromRows(List<ManifestItem> rows, String query, Consumer<List<LookupHit>> done)
	{
		List<LookupHit> hits = new ArrayList<>();
		for (ManifestItem row : rows)
		{
			if (hits.size() >= MAX_RESULTS || row == null || row.getUsableColors().isEmpty())
			{
				continue;
			}
			String name = row.getName();
			if (name == null || name.isEmpty())
			{
				continue;
			}
			String nameLc = name.toLowerCase(Locale.ENGLISH);
			if (!query.isEmpty() && !nameLc.contains(query))
			{
				continue;
			}
			int id = row.getId();
			int canon = itemManager.canonicalize(id);
			if (!passesLookupFilters(row, id, true))
			{
				continue;
			}
			hits.add(new LookupHit(id, name, canon, row));
		}
		done.accept(hits);
	}

	private void scanClientItemsForLookup(String query, List<LookupHit> hits)
	{
		int maxId = client.getItemCount();
		for (int id = 0; id < maxId && hits.size() < MAX_RESULTS; id++)
		{
			ItemComposition def = client.getItemDefinition(id);
			if (def == null)
			{
				continue;
			}
			String name = Text.removeTags(def.getName());
			if (name == null || name.isEmpty() || name.equals("null"))
			{
				continue;
			}
			if (!query.isEmpty() && !name.toLowerCase(Locale.ENGLISH).contains(query))
			{
				continue;
			}
			int canon = itemManager.canonicalize(id);
			if (id != canon)
			{
				continue;
			}
			ManifestItem row = manifestStore.getListedManifestItem(id, itemManager);
			if (row == null || row.getUsableColors().isEmpty())
			{
				continue;
			}
			if (!passesLookupFilters(row, id, false))
			{
				continue;
			}
			hits.add(new LookupHit(id, name, canon, row));
		}
	}

	/**
	 * Lookup filter checkboxes. When {@code paletteFilteredByHub}, palette rows already came from
	 * {@code usableBy} + {@code groupFilters} on the items API.
	 */
	private boolean passesLookupFilters(ManifestItem row, int itemId, boolean paletteFilteredByHub)
	{
		boolean enforced = ManifestRules.isLockEnforced(row);
		boolean manualAny = Boolean.TRUE.equals(row.getColorLockExcluded());
		boolean actionableCategory = LOOKUP_ACTIONABLE_CATEGORIES.contains(
			row.getCategory().toLowerCase(Locale.ENGLISH));
		if (myPaletteOnlyCheckbox.isSelected() && !paletteFilteredByHub)
		{
			if (manualAny)
			{
				return true;
			}
			if (!enforced || !actionableCategory)
			{
				return false;
			}
			ColorLockColor lock = groupSync.effectiveAssignment(config);
			return !manifestStore.isRestrictedForAssignment(itemId, lock, itemManager);
		}
		if (myPaletteOnlyCheckbox.isSelected() && paletteFilteredByHub)
		{
			return manualAny || actionableCategory;
		}
		if (allColorsListingsCheckbox.isSelected())
		{
			return !enforced && (manualAny || actionableCategory);
		}
		if (manualAny)
		{
			return true;
		}
		return enforced && actionableCategory;
	}

	private void populateResults(List<LookupHit> hits, boolean truncated)
	{
		resultsPanel.removeAll();
		if (hits.isEmpty())
		{
			String msg;
			if (myPaletteOnlyCheckbox.isSelected())
			{
				msg = "No matches for your palette — broaden the query or turn off palette filter.";
			}
			else if (allColorsListingsCheckbox.isSelected())
			{
				msg = "No All-colors items in the current manifest.";
			}
			else
			{
				msg = "No matches — try a shorter name.";
			}
			resultsPanel.add(new JLabel(msg));
		}
		else
		{
			addResultsAssignmentBanner();
			for (LookupHit hit : hits)
			{
				resultsPanel.add(createResultRow(hit));
				resultsPanel.add(Box.createVerticalStrut(8));
			}
			if (truncated)
			{
				resultsPanel.add(new JLabel("Showing first " + MAX_RESULTS + " matches only."));
			}
		}
		resultsPanel.revalidate();
		resultsPanel.repaint();
	}

	private void addResultsAssignmentBanner()
	{
		JPanel banner = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
		banner.setOpaque(false);
		banner.setAlignmentX(Component.LEFT_ALIGNMENT);
		Color muted = new Color(150, 150, 160);
		Font ui = banner.getFont().deriveFont(Font.PLAIN, 11f);
		JLabel lbl = new JLabel("Your lock:");
		lbl.setFont(ui);
		lbl.setForeground(muted);
		banner.add(lbl);
		ColorLockColor lock = groupSync.effectiveAssignment(config);
		banner.add(new PaletteChip(lock.getKey()));
		resultsPanel.add(banner);
		resultsPanel.add(Box.createVerticalStrut(10));
	}

	private JPanel createResultRow(LookupHit hit)
	{
		JPanel row = new JPanel();
		row.setLayout(new BoxLayout(row, BoxLayout.Y_AXIS));
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		row.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(55, 55, 62)),
			BorderFactory.createEmptyBorder(8, 4, 8, 4)));

		addLookupHeadingRow(row, hit.name.trim() + ":");

		JLabel iconLabel = new JLabel();
		iconLabel.setPreferredSize(new Dimension(ICON_SLOT, ICON_SLOT));
		iconLabel.setHorizontalAlignment(JLabel.CENTER);
		iconLabel.setVerticalAlignment(JLabel.CENTER);
		itemManager.getImage(hit.itemId).addTo(iconLabel);

		JPanel bodyColumn = new JPanel();
		bodyColumn.setLayout(new BoxLayout(bodyColumn, BoxLayout.Y_AXIS));
		bodyColumn.setOpaque(false);

		ManifestItem listed = hit.manifestRow;
		boolean hasColors = listed != null && !listed.getUsableColors().isEmpty();
		Font smallUi = bodyColumn.getFont().deriveFont(Font.PLAIN, 11f);
		Color muted = new Color(150, 150, 160);

		boolean isOptOut = listed != null && hasColors && !ManifestRules.isLockEnforced(listed);

		if (!hasColors)
		{
			JPanel miss = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
			miss.setOpaque(false);
			JLabel none = new JLabel("not in manifest");
			none.setFont(smallUi);
			none.setForeground(new Color(120, 120, 130));
			miss.add(none);
			bodyColumn.add(miss);
		}
		else if (isOptOut)
		{
			JPanel allRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
			allRow.setOpaque(false);
			JLabel allLbl = new JLabel("All colors");
			allLbl.setFont(bodyColumn.getFont().deriveFont(Font.BOLD, 12f));
			allLbl.setForeground(new Color(110, 170, 120));
			allRow.add(allLbl);
			bodyColumn.add(allRow);
		}
		else
		{
			// Per-item palette chips only. Group / crew intersection moved to a future "Group"
			// sidebar tab that will also show group name, member display names, and assignments.
			appendPaletteChipsWrapped(bodyColumn, ManifestRules.usableColorsManifestOrdered(listed));
		}

		row.add(Box.createVerticalStrut(4));

		JPanel iconBody = new JPanel(new BorderLayout(10, 0));
		iconBody.setOpaque(false);
		iconBody.setAlignmentX(Component.LEFT_ALIGNMENT);
		iconBody.add(iconLabel, BorderLayout.WEST);
		iconBody.add(bodyColumn, BorderLayout.CENTER);
		row.add(iconBody);
		attachWikiOpenToLookupRow(row, hit);
		return row;
	}

	/** Same URL shape as RuneLite Wiki for item lookups (Special:Lookup + utm_source). */
	private static String osrsWikiItemLookupUrl(String name, int canonicalItemId)
	{
		String nm = name == null ? "" : name.trim();
		String enc = URLEncoder.encode(nm, StandardCharsets.UTF_8).replace("+", "%20");
		return "https://oldschool.runescape.wiki/w/Special:Lookup?type=item&id=" + canonicalItemId
			+ "&name=" + enc
			+ "&utm_source=runelite";
	}

	private void openWikiForLookupHit(LookupHit hit)
	{
		clientThread.invokeLater(() ->
		{
			int id = itemManager.canonicalize(hit.itemId);
			ItemComposition def = client.getItemDefinition(id);
			String wikiName = def != null ? Text.removeTags(def.getMembersName()) : "";
			if (wikiName == null || wikiName.trim().isEmpty() || "null".equalsIgnoreCase(wikiName))
			{
				wikiName = hit.name == null ? "" : hit.name.trim();
			}
			String url = osrsWikiItemLookupUrl(wikiName, id);
			SwingUtilities.invokeLater(() -> LinkBrowser.browse(url));
		});
	}

	private void attachWikiOpenToLookupRow(JPanel rowRoot, LookupHit hit)
	{
		rowRoot.setToolTipText("Left-click row: OSRS Wiki");
		MouseAdapter opener = new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				if (SwingUtilities.isLeftMouseButton(e))
				{
					openWikiForLookupHit(hit);
				}
			}
		};
		eachSwingDescendant(rowRoot, jc ->
		{
			jc.addMouseListener(opener);
			jc.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		});
	}

	private static void eachSwingDescendant(Container root, Consumer<JComponent> visit)
	{
		if (root instanceof JComponent)
		{
			visit.accept((JComponent) root);
		}
		for (Component c : root.getComponents())
		{
			if (c instanceof Container)
			{
				eachSwingDescendant((Container) c, visit);
			}
		}
	}

	/** Bold item name full-width above icon row (never beside the sprite). */
	private static void addLookupHeadingRow(JPanel column, String nameWithColonSpace)
	{
		JPanel h = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
		h.setOpaque(false);
		h.setAlignmentX(Component.LEFT_ALIGNMENT);
		JLabel title = new JLabel(nameWithColonSpace);
		title.setFont(title.getFont().deriveFont(Font.BOLD));
		h.add(title);
		column.add(h);
	}
	/** Fits palette chips inside the sidebar by starting a new row every few chips */
	private static void appendPaletteChipsWrapped(JPanel column, List<String> manifestTokens)
	{
		int cap = paletteChipsPerRow();
		JPanel chipRow = null;
		for (String token : manifestTokens)
		{
			if (token == null || token.trim().isEmpty())
			{
				continue;
			}
			if (chipRow == null || chipRow.getComponentCount() >= cap)
			{
				chipRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 4));
				chipRow.setOpaque(false);
				chipRow.setAlignmentX(Component.LEFT_ALIGNMENT);
				column.add(chipRow);
			}
			chipRow.add(new PaletteChip(token.trim()));
		}
	}

	private static int paletteChipsPerRow()
	{
		int textBudgetPx = PluginPanel.PANEL_WIDTH - ICON_SLOT - 44;
		return Math.max(2, Math.min(10, textBudgetPx / 22));
	}

	private static String escapeHtml(String s)
	{
		if (s == null)
		{
			return "";
		}
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

	private static JLabel urlLink(final String url)
	{
		JLabel ln = new JLabel("<html><body style='width:220px'><font color=\"#6C97FF\"><u>"
			+ escapeHtml(url)
			+ "</u></font></body></html>");
		ln.setAlignmentX(Component.LEFT_ALIGNMENT);
		ln.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		ln.setToolTipText("Open in browser");
		ln.addMouseListener(new java.awt.event.MouseAdapter()
		{
			@Override
			public void mouseClicked(java.awt.event.MouseEvent ev)
			{
				openInBrowser(url);
			}
		});
		return ln;
	}

	private static void openInBrowser(String url)
	{
		if (!Desktop.isDesktopSupported())
		{
			return;
		}
		try
		{
			Desktop.getDesktop().browse(URI.create(url.trim()));
		}
		catch (Exception ignored)
		{
		}
	}

	/** Color-wheel nav icon: 8 OSRS palette slices, dark padlock-shackle accent. */
	static BufferedImage createNavIcon()
	{
		final int size = 32;
		final int pad = 2;
		final int diameter = size - pad * 2;
		BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		try
		{
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

			String[] slices = {"red", "yellow", "green", "blue", "purple", "brown", "black", "white"};
			float sliceDeg = 360f / slices.length;
			for (int i = 0; i < slices.length; i++)
			{
				g.setColor(ColorLockPalette.toUiColor(slices[i]));
				g.fillArc(pad, pad, diameter, diameter, Math.round(90f - i * sliceDeg - sliceDeg), Math.round(sliceDeg) + 1);
			}

			g.setStroke(new java.awt.BasicStroke(1.2f));
			g.setColor(new Color(20, 20, 24, 220));
			g.drawOval(pad, pad, diameter - 1, diameter - 1);

			int hole = 8;
			int hx = size / 2 - hole / 2;
			int hy = size / 2 - hole / 2;
			g.setColor(new Color(30, 30, 35));
			g.fillOval(hx, hy, hole, hole);
			g.setColor(new Color(220, 220, 230, 200));
			g.setStroke(new java.awt.BasicStroke(1.4f));
			g.drawArc(hx + 1, hy - 2, hole - 2, hole - 1, 20, 140);
		}
		finally
		{
			g.dispose();
		}
		return img;
	}

	private static final class LookupHit
	{
		final int itemId;
		final String name;
		final int canon;
		final ManifestItem manifestRow;

		LookupHit(int itemId, String name, int canon, ManifestItem manifestRow)
		{
			this.itemId = itemId;
			this.name = name;
			this.canon = canon;
			this.manifestRow = manifestRow;
		}
	}

	/** Rounded palette swatch — color fill only; tooltip shows the palette name. */
	private static final class PaletteChip extends JPanel
	{
		private static final int SWATCH = 18;

		private final Color fill;
		private final int arc;

		PaletteChip(String paletteToken)
		{
			setOpaque(false);
			String t = paletteToken == null ? "" : paletteToken.trim();
			String keyLc = t.toLowerCase(Locale.ENGLISH);
			fill = ColorLockPalette.toUiColor(t.isEmpty() ? null : keyLc);
			String tip = t.isEmpty() ? ColorLockPalette.abbreviation(null) : t;
			setToolTipText(tip);
			Dimension d = new Dimension(SWATCH, SWATCH);
			setPreferredSize(d);
			setMinimumSize(d);
			setMaximumSize(d);
			arc = SWATCH;
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			super.paintComponent(g);
			Graphics2D g2 = (Graphics2D) g.create();
			try
			{
				g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				int w = getWidth();
				int h = getHeight();
				g2.setColor(fill);
				g2.fillRoundRect(0, 0, w - 1, h - 1, arc, arc);
				g2.setColor(new Color(45, 45, 52));
				g2.drawRoundRect(0, 0, w - 1, h - 1, arc, arc);
			}
			finally
			{
				g2.dispose();
			}
		}
	}
}
