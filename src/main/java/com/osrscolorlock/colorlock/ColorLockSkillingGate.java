package com.osrscolorlock.colorlock;

import net.runelite.api.Client;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.client.game.ItemManager;
import net.runelite.client.util.Text;

/**
 * OSRS can mine/chop using tools in inventory without wielding them. Strip tree/rock gather verbs when
 * the player carries any color-restricted pickaxe (mine) or axe/hatchet (chop/cut) in inventory or equipment.
 */
final class ColorLockSkillingGate
{
	enum SkillingToolKind
	{
		PICKAXE,
		AXE,
		OTHER
	}

	private ColorLockSkillingGate()
	{
	}

	static boolean shouldStripGatherMenu(Client client, ItemManager itemManager, ManifestStore manifestStore,
		ColorLockColor assignment, MenuEntry entry)
	{
		if (client == null || entry == null || manifestStore == null || manifestStore.itemCount() == 0)
		{
			return false;
		}
		if (!isObjectGatherMenu(entry))
		{
			return false;
		}
		SkillingToolKind required = requiredToolForVerb(entry.getOption());
		if (required == null)
		{
			return false;
		}
		return inventoryContainsRestrictedToolOfKind(client, itemManager, manifestStore, assignment, required);
	}

	/**
	 * True when any pickaxe/axe of {@code required} kind in inventory or equipment is restricted for
	 * {@code assignment}.
	 */
	static boolean inventoryContainsRestrictedToolOfKind(Client client, ItemManager itemManager,
		ManifestStore manifestStore, ColorLockColor assignment, SkillingToolKind required)
	{
		if (client == null || manifestStore == null || required == null)
		{
			return false;
		}
		for (InventoryID invId : new InventoryID[] { InventoryID.INVENTORY, InventoryID.EQUIPMENT })
		{
			ItemContainer container = client.getItemContainer(invId);
			if (container == null)
			{
				continue;
			}
			for (Item item : container.getItems())
			{
				if (item == null || item.getId() <= 0)
				{
					continue;
				}
				if (classifyTool(itemManager, manifestStore, item.getId()) != required)
				{
					continue;
				}
				if (manifestStore.isRestrictedForAssignment(item.getId(), assignment, itemManager))
				{
					return true;
				}
			}
		}
		return false;
	}

	static SkillingToolKind requiredToolForVerb(String rawOption)
	{
		if (rawOption == null)
		{
			return null;
		}
		String opt = Text.removeTags(rawOption).trim().toLowerCase();
		if (isMiningVerb(opt))
		{
			return SkillingToolKind.PICKAXE;
		}
		if (isWoodcutVerb(opt))
		{
			return SkillingToolKind.AXE;
		}
		return null;
	}

	static boolean isMiningVerb(String optLc)
	{
		return "mine".equals(optLc) || optLc.startsWith("mine ") || "prospect".equals(optLc);
	}

	static boolean isWoodcutVerb(String optLc)
	{
		return "chop down".equals(optLc) || optLc.startsWith("chop") || "cut".equals(optLc) || optLc.startsWith("cut ");
	}

	static SkillingToolKind classifyToolFromName(String name)
	{
		if (name == null || name.isEmpty())
		{
			return SkillingToolKind.OTHER;
		}
		String n = name.toLowerCase();
		if (n.contains("pickaxe"))
		{
			return SkillingToolKind.PICKAXE;
		}
		if (n.contains("hatchet") || n.contains(" axe") || n.endsWith(" axe") || n.equals("axe"))
		{
			return SkillingToolKind.AXE;
		}
		return SkillingToolKind.OTHER;
	}

	private static boolean isObjectGatherMenu(MenuEntry entry)
	{
		MenuAction type = entry.getType();
		if (type == null)
		{
			return false;
		}
		switch (type)
		{
			case GAME_OBJECT_FIRST_OPTION:
			case GAME_OBJECT_SECOND_OPTION:
			case GAME_OBJECT_THIRD_OPTION:
			case GAME_OBJECT_FOURTH_OPTION:
			case GAME_OBJECT_FIFTH_OPTION:
			case WIDGET_TARGET_ON_GAME_OBJECT:
				return true;
			default:
				return false;
		}
	}

	private static SkillingToolKind classifyTool(ItemManager itemManager, ManifestStore manifestStore, int itemId)
	{
		ManifestItem row = manifestStore.getListedManifestItem(itemId, itemManager);
		if (row != null && !row.getName().isEmpty())
		{
			SkillingToolKind fromManifest = classifyToolFromName(row.getName());
			if (fromManifest != SkillingToolKind.OTHER)
			{
				return fromManifest;
			}
		}
		if (itemManager != null)
		{
			try
			{
				String name = itemManager.getItemComposition(itemManager.canonicalize(itemId)).getName();
				return classifyToolFromName(name);
			}
			catch (RuntimeException ignored)
			{
				return SkillingToolKind.OTHER;
			}
		}
		return SkillingToolKind.OTHER;
	}
}
