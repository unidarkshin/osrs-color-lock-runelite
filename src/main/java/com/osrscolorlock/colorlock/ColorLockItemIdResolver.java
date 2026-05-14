package com.osrscolorlock.colorlock;

import net.runelite.api.Client;
import net.runelite.api.MenuEntry;
import net.runelite.api.widgets.Widget;

/** Resolves OSRS item id from menu entries (inventory CC_OP often omits getItemId). */
final class ColorLockItemIdResolver
{
	private ColorLockItemIdResolver()
	{
	}

	static int resolve(MenuEntry e, Client client)
	{
		if (e == null)
		{
			return -1;
		}
		int id = e.getItemId();
		if (id > 0)
		{
			return id;
		}

		Widget w = e.getWidget();
		if (w != null)
		{
			id = w.getItemId();
			if (id > 0)
			{
				return id;
			}
			int slot = e.getParam0();
			if (slot >= 0)
			{
				id = itemIdFromSlotChild(w, slot);
				if (id > 0)
				{
					return id;
				}
				Widget parent = w.getParent();
				if (parent != null)
				{
					id = itemIdFromSlotChild(parent, slot);
					if (id > 0)
					{
						return id;
					}
				}
			}
		}

		if (client != null)
		{
			int packed = e.getParam1();
			if (packed != 0)
			{
				Widget container = client.getWidget(packed);
				if (container != null)
				{
					id = itemIdFromSlotChild(container, e.getParam0());
					if (id > 0)
					{
						return id;
					}
				}
			}
		}

		id = e.getIdentifier();
		return id > 0 ? id : -1;
	}

	private static int itemIdFromSlotChild(Widget container, int slot)
	{
		if (container == null || slot < 0)
		{
			return -1;
		}
		Widget child = container.getChild(slot);
		if (child != null)
		{
			int id = child.getItemId();
			if (id > 0)
			{
				return id;
			}
		}
		Widget[] children = container.getChildren();
		if (children != null && slot < children.length)
		{
			Widget c = children[slot];
			if (c != null)
			{
				int id = c.getItemId();
				if (id > 0)
				{
					return id;
				}
			}
		}
		return -1;
	}
}
