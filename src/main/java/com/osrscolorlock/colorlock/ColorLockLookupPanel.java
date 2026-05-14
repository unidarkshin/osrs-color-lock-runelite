package com.osrscolorlock.colorlock;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;

import net.runelite.api.Client;
import net.runelite.api.ItemComposition;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.Text;

@Singleton
public class ColorLockLookupPanel extends PluginPanel
{
	private static final int MAX_RESULTS = 80;
	private static final int ICON_SLOT = 36;

	private final Client client;
	private final ClientThread clientThread;
	private final ManifestStore manifestStore;
	private final ItemManager itemManager;
	private final ColorLockConfig config;

	private final JTextField queryField = new JTextField(18);
	private final JButton searchButton = new JButton("Search");
	private final JPanel resultsPanel;

	@Inject
	ColorLockLookupPanel(
		Client client,
		ClientThread clientThread,
		ManifestStore manifestStore,
		ItemManager itemManager,
		ColorLockConfig config)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.manifestStore = manifestStore;
		this.itemManager = itemManager;
		this.config = config;

		setLayout(new BorderLayout());
		setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH, 420));

		resultsPanel = new JPanel();
		resultsPanel.setLayout(new BoxLayout(resultsPanel, BoxLayout.Y_AXIS));
		resultsPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
		showPlaceholderIntro();

		JScrollPane scroll = new JScrollPane(
			resultsPanel,
			ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
			ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.getVerticalScrollBar().setUnitIncrement(16);

		JPanel north = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 6));
		north.add(new JLabel("Search"));
		north.add(queryField);
		north.add(searchButton);

		JLabel hint = new JLabel("<html><body style='width:220px'>Matches client item names. Icons load from cache.</body></html>");

		add(north, BorderLayout.NORTH);
		add(scroll, BorderLayout.CENTER);
		add(hint, BorderLayout.SOUTH);

		searchButton.addActionListener(e -> runSearch());
		queryField.addActionListener(e -> runSearch());
	}

	private void showPlaceholderIntro()
	{
		resultsPanel.removeAll();
		JLabel intro = new JLabel("<html><body style='width:220px'>Type part of a name (at least 2 letters), then <b>Search</b>. Each row shows icon, name, and usable colors.</body></html>");
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
				ManifestItem row = manifestStore.getListedManifestItem(id, itemManager);
				hits.add(new LookupHit(id, name, canon, row));
			}

			boolean truncated = hits.size() >= MAX_RESULTS;
			SwingUtilities.invokeLater(() -> populateResults(hits, truncated));
		});
	}

	private void populateResults(List<LookupHit> hits, boolean truncated)
	{
		resultsPanel.removeAll();
		if (hits.isEmpty())
		{
			resultsPanel.add(new JLabel("No matches — try a shorter name."));
		}
		else
		{
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

	private JPanel createResultRow(LookupHit hit)
	{
		JPanel row = new JPanel(new BorderLayout(10, 0));
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, ICON_SLOT + 28));
		row.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(55, 55, 62)),
			BorderFactory.createEmptyBorder(8, 4, 8, 4)));

		JLabel iconLabel = new JLabel();
		iconLabel.setPreferredSize(new Dimension(ICON_SLOT, ICON_SLOT));
		iconLabel.setHorizontalAlignment(JLabel.CENTER);
		iconLabel.setVerticalAlignment(JLabel.CENTER);
		itemManager.getImage(hit.itemId).addTo(iconLabel);

		JPanel textColumn = new JPanel();
		textColumn.setLayout(new BoxLayout(textColumn, BoxLayout.Y_AXIS));
		textColumn.setOpaque(false);

		String safeName = escapeHtml(hit.name);
		String lockNote = "";
		ManifestItem listed = hit.manifestRow;
		boolean hasColors = listed != null && !listed.getUsableColors().isEmpty();
		if (hasColors)
		{
			boolean blocked = ManifestRules.isRestrictedForAssignment(listed, config.assignedColor());
			lockNote = blocked
				? "<br/><span style='color:#cc4444;font-size:11px'>Blocked for your lock</span>"
				: "<br/><span style='color:#559955;font-size:11px'>Ok for your lock</span>";
		}

		JLabel title = new JLabel("<html><body style='width:170px'><b>" + safeName + "</b>"
			+ "<br/><span style='color:#909090;font-size:11px'>id " + hit.itemId
			+ (hit.canon != hit.itemId ? " · canon " + hit.canon : "")
			+ "</span>" + lockNote + "</body></html>");
		title.setAlignmentX(Component.LEFT_ALIGNMENT);
		textColumn.add(title);

		JPanel dotRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
		dotRow.setOpaque(false);
		if (!hasColors)
		{
			JLabel none = new JLabel("Not in manifest");
			none.setFont(none.getFont().deriveFont(Font.PLAIN, 11f));
			none.setForeground(new Color(140, 140, 150));
			dotRow.add(none);
		}
		else
		{
			JLabel lab = new JLabel("Colors:");
			lab.setFont(lab.getFont().deriveFont(Font.PLAIN, 11f));
			lab.setForeground(new Color(170, 170, 180));
			dotRow.add(lab);
			for (String key : listed.getUsableColors())
			{
				String trimmed = key == null ? "" : key.trim();
				if (trimmed.isEmpty())
				{
					continue;
				}
				Color awt = ColorLockPalette.toUiColor(trimmed);
				dotRow.add(new ColorSwatch(awt, trimmed));
			}
		}
		textColumn.add(dotRow);

		row.add(iconLabel, BorderLayout.WEST);
		row.add(textColumn, BorderLayout.CENTER);
		return row;
	}

	private static String escapeHtml(String s)
	{
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
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

	/** Small filled circle with palette tooltip. */
	private static final class ColorSwatch extends JPanel
	{
		private static final int DIAM = 14;
		private final Color fill;

		ColorSwatch(Color fill, String paletteTooltip)
		{
			this.fill = fill;
			setOpaque(false);
			setToolTipText(paletteTooltip);
			Dimension d = new Dimension(DIAM + 4, DIAM + 4);
			setPreferredSize(d);
			setMinimumSize(d);
			setMaximumSize(d);
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			super.paintComponent(g);
			Graphics2D g2 = (Graphics2D) g.create();
			try
			{
				g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				int pad = 3;
				int w = getWidth() - 2 * pad;
				int h = getHeight() - 2 * pad;
				g2.setColor(fill);
				g2.fillOval(pad, pad, w, h);
				g2.setColor(new Color(55, 55, 62));
				g2.drawOval(pad, pad, w, h);
			}
			finally
			{
				g2.dispose();
			}
		}
	}
}
