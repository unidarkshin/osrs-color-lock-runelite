package com.osrscolorlock.colorlock;

import java.util.concurrent.ConcurrentLinkedQueue;

/** FIFO labels from collection-log chat lines, paired one-per-drop when varps increase. */
final class ColorLockCollectionLogItemQueue
{
	private final ConcurrentLinkedQueue<String> pendingItems = new ConcurrentLinkedQueue<>();

	void offerFromChat(String raw)
	{
		if (raw == null)
		{
			return;
		}
		String item = raw.trim();
		if (!item.isEmpty())
		{
			pendingItems.offer(item);
		}
	}

	String pollForDrop()
	{
		return pendingItems.poll();
	}

	void clear()
	{
		pendingItems.clear();
	}

	int size()
	{
		return pendingItems.size();
	}
}
