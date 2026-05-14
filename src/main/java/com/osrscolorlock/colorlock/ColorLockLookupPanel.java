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
import javax.swing.JTextField;
import javax.swing.JViewport;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;

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
		showPlaceholderIntro();

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
			"Only items your color lock can use (group palette intersect). Mutually exclusive with All-colors-only.");
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

		add(north, BorderLayout.NORTH);
		add(scroll, BorderLayout.CENTER);
		add(south, BorderLayout.SOUTH);

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
		JLabel intro = new JLabel("<html><body style='width:220px'>Type part of a name (at least 2 letters), then <b>Search</b>. Each row lists palette colors as swatches (hover for the color name).</body></html>");
		intro.setAlignmentX(Component.LEFT_ALIGNMENT);
		resultsPanel.add(intro);
		resultsPanel.revalidate();
		resultsPanel.repaint();
	}

	private void runSearch()
	{
		String raw = queryField.getText();
		String q = raw == null ? "" : raw.trim().toLowerCase(Locale.ENGLISH);
		if (q.length() < 2)
		{
			SwingUtilities.invokeLater(() -> {
				resultsPanel.removeAll();
				resultsPanel.add(new JLabel("Type at least 2 characters."));
				resultsPanel.revalidate();
				resultsPanel.repaint();
			});
			return;
		}

		SwingUtilities.invokeLater(() -> {
			resultsPanel.removeAll();
			resultsPanel.add(new JLabel("Searching…"));
			resultsPanel.revalidate();
			resultsPanel.repaint();
		});

		clientThread.invokeLater(() -> {
			List<LookupHit> hits = new ArrayList<>();
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
				if (!name.toLowerCase(Locale.ENGLISH).contains(q))
				{
					continue;
				}
				int canon = itemManager.canonicalize(id);
				// Omit noted / placeholder / worn-alias ids — same logical item as canonical row
				if (id != canon)
				{
					continue;
				}
				ManifestItem row = manifestStore.getListedManifestItem(id, itemManager);
				if (row == null || row.getUsableColors().isEmpty())
				{
					continue;
				}
				if (!passesLookupFilters(row, id))
				{
					continue;
				}
				hits.add(new LookupHit(id, name, canon, row));
			}

			boolean truncated = hits.size() >= MAX_RESULTS;
			SwingUtilities.invokeLater(() -> populateResults(hits, truncated));
		});
	}

	/** Applies lookup filter checkboxes ({@linkplain #myPaletteOnlyCheckbox} vs {@linkplain #allColorsListingsCheckbox}). */
	private boolean passesLookupFilters(ManifestItem row, int itemId)
	{
		boolean enforced = ManifestRules.isLockEnforced(row);
		if (myPaletteOnlyCheckbox.isSelected())
		{
			if (!enforced)
			{
				return false;
			}
			ColorLockColor lock = groupSync.effectiveAssignment(config);
			return !manifestStore.isRestrictedForAssignment(itemId, lock, itemManager);
		}
		if (allColorsListingsCheckbox.isSelected())
		{
			return !enforced;
		}
		return true;
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
				msg = "No hub opt-out matches — broaden the query or turn off All-colors filter.";
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
		ManifestItem listedEarly = hit.manifestRow;
		boolean hasColorsEarly = listedEarly != null && !listedEarly.getUsableColors().isEmpty();
		if (hasColorsEarly && !ManifestRules.isLockEnforced(listedEarly))
		{
			return createCompactOptOutLookupRow(hit);
		}

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
		else
		{
			appendPaletteChipsWrapped(bodyColumn, ManifestRules.usableColorsManifestOrdered(listed));

			Set<String> crewFilter = groupSync.manifestRuleCrewFilter(config);

			if (crewFilter != null)
			{
				List<String> crewOverlap = ManifestRules.usableColorsEffectiveForCrew(listed, crewFilter);
				JPanel crewRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
				crewRow.setOpaque(false);
				JLabel cl = new JLabel("Group");
				cl.setFont(smallUi);
				cl.setForeground(muted);
				crewRow.add(cl);
				if (crewOverlap.isEmpty())
				{
					JLabel na = new JLabel("(none — blocked)");
					na.setFont(smallUi);
					na.setForeground(new Color(200, 90, 96));
					crewRow.add(na);
				}
				else
				{
					for (String key : crewOverlap)
					{
						if (key != null && !key.trim().isEmpty())
						{
							crewRow.add(new PaletteChip(key.trim()));
						}
					}
				}
				bodyColumn.add(crewRow);
			}
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

	/** Hub opt-out rows: one short strip (no full-width title + extra strut). */
	private JPanel createCompactOptOutLookupRow(LookupHit hit)
	{
		JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
		row.setOpaque(false);
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		row.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(55, 55, 62)),
			BorderFactory.createEmptyBorder(6, 4, 6, 4)));

		JLabel iconLabel = new JLabel();
		iconLabel.setPreferredSize(new Dimension(ICON_SLOT, ICON_SLOT));
		iconLabel.setHorizontalAlignment(JLabel.CENTER);
		iconLabel.setVerticalAlignment(JLabel.CENTER);
		itemManager.getImage(hit.itemId).addTo(iconLabel);
		row.add(iconLabel);

		String safe = escapeHtml(hit.name.trim());
		JLabel text = new JLabel("<html><body style='width:165px'><b>" + safe
			+ ":</b> <span style='color:#6EAA78;font-weight:bold'>All colors</span></body></html>");
		text.setVerticalAlignment(JLabel.TOP);
		row.add(text);

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

	static BufferedImage createNavIcon()
	{
		BufferedImage img = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
		for (int x = 0; x < 32; x++)
		{
			for (int y = 0; y < 32; y++)
			{
				boolean edge = x == 0 || y == 0 || x == 31 || y == 31;
				img.setRGB(x, y, edge ? 0xFF333333 : 0xFF6C5CE7);
			}
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
