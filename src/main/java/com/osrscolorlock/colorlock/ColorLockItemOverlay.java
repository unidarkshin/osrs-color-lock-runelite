package com.osrscolorlock.colorlock;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.inject.Inject;
import javax.inject.Singleton;

import net.runelite.api.Client;
import net.runelite.api.KeyCode;
import net.runelite.api.widgets.InterfaceID;
import net.runelite.api.widgets.WidgetItem;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.WidgetItemOverlay;

@Singleton
public class ColorLockItemOverlay extends WidgetItemOverlay
{
	private static final Color MARK_RED = new Color(220, 40, 40);
	private static final BasicStroke MARK_STROKE = new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
	private static final int SWATCH_SIZE = 4;
	private static final int SWATCH_GAP = 2;
	private static final Color TOOLTIP_BG = new Color(15, 15, 18, 210);
	private static final Color TOOLTIP_BORDER = new Color(60, 60, 65, 180);
	private static final Color TOOLTIP_TEXT = new Color(220, 220, 225);

	private final Client client;
	private final ColorLockConfig config;
	private final ManifestStore manifestStore;
	private final ColorLockGroupSync groupSync;
	private final ItemManager itemManager;

	@Inject
	ColorLockItemOverlay(Client client, ColorLockConfig config, ManifestStore manifestStore,
		ColorLockGroupSync groupSync, ItemManager itemManager)
	{
		this.client = client;
		this.config = config;
		this.manifestStore = manifestStore;
		this.groupSync = groupSync;
		this.itemManager = itemManager;
		showOnInventory();
		showOnBank();
		showOnEquipment();
		showOnInterfaces(
			InterfaceID.GROUP_STORAGE,
			InterfaceID.GROUP_STORAGE_INVENTORY,
			InterfaceID.SHOP);
	}

	@Override
	public void renderItemOverlay(Graphics2D graphics, int itemId, WidgetItem widgetItem)
	{
		ManifestItem row = manifestStore.getListedManifestItem(itemId, itemManager);
		if (row == null || row.getUsableColors().isEmpty() || !ManifestRules.isLockEnforced(row))
		{
			return;
		}

		Rectangle bounds = widgetItem.getCanvasBounds();

		if (config.showColorOverlay())
		{
			ColorLockColor lock = groupSync.effectiveAssignment(config);
			boolean restricted = manifestStore.isRestrictedForAssignment(itemId, lock, itemManager);
			if (restricted)
			{
				drawRestrictedCornerMark(graphics, bounds);
			}
		}

		if (config.showColorSwatches())
		{
			net.runelite.api.Point mouse = client.getMouseCanvasPosition();
			if (bounds.contains(mouse.getX(), mouse.getY()))
			{
				List<String> colors = ManifestRules.usableColorsManifestOrdered(row);
				if (!colors.isEmpty())
				{
					drawColorSwatches(graphics, bounds, colors);

					if (client.isKeyPressed(KeyCode.KC_ALT))
					{
						drawMemberTooltip(graphics, bounds, colors);
					}
				}
			}
		}
	}

	private static void drawRestrictedCornerMark(Graphics2D g, Rectangle b)
	{
		int inset = Math.max(1, Math.min(b.width, b.height) / 14);
		int arm = Math.max(5, Math.min(b.width, b.height) / 4);
		int x0 = b.x + b.width - arm - inset;
		int y0 = b.y + inset;

		Object oldAa = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
		Stroke oldStroke = g.getStroke();
		Color oldColor = g.getColor();
		try
		{
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g.setStroke(MARK_STROKE);
			g.setColor(MARK_RED);
			g.drawLine(x0, y0, x0 + arm, y0 + arm);
			g.drawLine(x0 + arm, y0, x0, y0 + arm);
		}
		finally
		{
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, oldAa);
			g.setStroke(oldStroke);
			g.setColor(oldColor);
		}
	}

	private static void drawColorSwatches(Graphics2D g, Rectangle b, List<String> colors)
	{
		int count = colors.size();
		int totalWidth = count * SWATCH_SIZE + (count - 1) * SWATCH_GAP;
		int x = b.x + (b.width - totalWidth) / 2;
		int y = b.y + 2;

		Color oldColor = g.getColor();
		try
		{
			for (String colorKey : colors)
			{
				g.setColor(new Color(0, 0, 0, 160));
				g.fillRect(x - 1, y - 1, SWATCH_SIZE + 2, SWATCH_SIZE + 2);
				g.setColor(ColorLockPalette.toUiColor(colorKey));
				g.fillRect(x, y, SWATCH_SIZE, SWATCH_SIZE);
				x += SWATCH_SIZE + SWATCH_GAP;
			}
		}
		finally
		{
			g.setColor(oldColor);
		}
	}

	private void drawMemberTooltip(Graphics2D g, Rectangle bounds, List<String> colors)
	{
		List<ColorLockGroupSync.RosterMemberSnapshot> roster = groupSync.getRosterSnapshot();
		if (roster.isEmpty())
		{
			return;
		}

		List<TooltipLine> lines = new ArrayList<>();
		for (String colorKey : colors)
		{
			String colorLc = colorKey.trim().toLowerCase(Locale.ENGLISH);
			String memberName = null;
			for (ColorLockGroupSync.RosterMemberSnapshot m : roster)
			{
				if (colorLc.equals(m.assignedColorKey)
					|| ("purple".equals(colorLc) && "violet".equals(m.assignedColorKey))
					|| ("violet".equals(colorLc) && "purple".equals(m.assignedColorKey)))
				{
					memberName = m.displayName;
					break;
				}
			}
			String label = memberName != null ? memberName : "—";
			lines.add(new TooltipLine(ColorLockPalette.toUiColor(colorKey), label));
		}

		if (lines.isEmpty())
		{
			return;
		}

		Font font = FontManager.getRunescapeSmallFont();
		g.setFont(font);
		FontMetrics fm = g.getFontMetrics();
		int lineH = fm.getHeight();
		int swatchW = 6;
		int pad = 3;
		int gap = 3;

		int maxTextW = 0;
		for (TooltipLine line : lines)
		{
			maxTextW = Math.max(maxTextW, fm.stringWidth(line.text));
		}

		int boxW = pad + swatchW + gap + maxTextW + pad;
		int boxH = pad + lines.size() * lineH + pad;
		int boxX = bounds.x + (bounds.width - boxW) / 2;
		int boxY = bounds.y - boxH - 2;

		Object oldAa = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
		Color oldColor = g.getColor();
		try
		{
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g.setColor(TOOLTIP_BG);
			g.fillRoundRect(boxX, boxY, boxW, boxH, 4, 4);
			g.setColor(TOOLTIP_BORDER);
			g.drawRoundRect(boxX, boxY, boxW, boxH, 4, 4);

			int textY = boxY + pad + fm.getAscent();
			for (TooltipLine line : lines)
			{
				int sx = boxX + pad;
				int sy = textY - fm.getAscent() + (lineH - swatchW) / 2;
				g.setColor(line.color);
				g.fillRect(sx, sy, swatchW, swatchW);
				g.setColor(TOOLTIP_TEXT);
				g.drawString(line.text, sx + swatchW + gap, textY);
				textY += lineH;
			}
		}
		finally
		{
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, oldAa);
			g.setColor(oldColor);
		}
	}

	private static final class TooltipLine
	{
		final Color color;
		final String text;

		TooltipLine(Color color, String text)
		{
			this.color = color;
			this.text = text;
		}
	}
}
