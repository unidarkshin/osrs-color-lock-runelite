package com.osrscolorlock.colorlock;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Stroke;
import javax.inject.Inject;
import javax.inject.Singleton;

import net.runelite.api.widgets.WidgetItem;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.overlay.WidgetItemOverlay;

@Singleton
public class ColorLockItemOverlay extends WidgetItemOverlay
{
	private static final Color MARK_RED = new Color(220, 40, 40);

	private final ColorLockConfig config;
	private final ManifestStore manifestStore;
	private final ItemManager itemManager;

	@Inject
	ColorLockItemOverlay(ColorLockConfig config, ManifestStore manifestStore, ItemManager itemManager)
	{
		this.config = config;
		this.manifestStore = manifestStore;
		this.itemManager = itemManager;
		showOnInventory();
		showOnBank();
		showOnEquipment();
	}

	@Override
	public void renderItemOverlay(Graphics2D graphics, int itemId, WidgetItem widgetItem)
	{
		if (!config.showColorOverlay())
		{
			return;
		}
		ManifestItem row = manifestStore.getListedManifestItem(itemId, itemManager);
		if (row == null || row.getUsableColors().isEmpty())
		{
			return;
		}

		boolean restricted = manifestStore.isRestrictedForAssignment(itemId, config.assignedColor(), itemManager);

		if (!restricted)
		{
			return;
		}

		drawRestrictedCornerMark(graphics, widgetItem.getCanvasBounds());
	}

	/** Top-right corner — avoids stack counts drawn bottom-right on stacks. */
	private static void drawRestrictedCornerMark(Graphics2D g, Rectangle b)
	{
		int inset = Math.max(1, Math.min(b.width, b.height) / 14);
		int arm = Math.max(6, Math.min(b.width, b.height) / 3);
		int x0 = b.x + b.width - arm - inset;
		int y0 = b.y + inset;

		Object oldAa = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
		Stroke oldStroke = g.getStroke();
		Color oldColor = g.getColor();
		try
		{
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			float w = Math.max(1.8f, arm / 5f);
			g.setStroke(new BasicStroke(w, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
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
}
