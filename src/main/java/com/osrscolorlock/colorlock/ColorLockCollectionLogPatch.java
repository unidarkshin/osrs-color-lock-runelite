package com.osrscolorlock.colorlock;

/** PATCH /me `collectionLog` body: snapshot (total only) or new-drop (total + newDrop + item). */
final class ColorLockCollectionLogPatch
{
	final int totalCount;
	/** True for new-drop events (nearby resolved on hub later from heartbeat XY). */
	final boolean newDrop;
	/** New entry name from game chat when notifications are on; may be null. */
	final String collectionItem;

	private ColorLockCollectionLogPatch(int totalCount, boolean newDrop, String collectionItem)
	{
		this.totalCount = totalCount;
		this.newDrop = newDrop;
		this.collectionItem = collectionItem;
	}

	static ColorLockCollectionLogPatch snapshot(int totalCount)
	{
		return new ColorLockCollectionLogPatch(totalCount, false, null);
	}

	static ColorLockCollectionLogPatch newDrop(int totalCount, String collectionItem)
	{
		String item = collectionItem != null && !collectionItem.isBlank() ? collectionItem.trim() : null;
		return new ColorLockCollectionLogPatch(totalCount, true, item);
	}

	boolean isNewDropEvent()
	{
		return newDrop;
	}
}
