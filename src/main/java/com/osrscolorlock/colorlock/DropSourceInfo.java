package com.osrscolorlock.colorlock;

import com.google.gson.annotations.SerializedName;

/** Single monster drop source for an item; matches hub {@code MonsterDropSource} shape. */
public class DropSourceInfo
{
	@SerializedName(value = "monsterName", alternate = {"monster_name"})
	private String monsterName;

	@SerializedName(value = "monsterWikiUrl", alternate = {"monster_wiki_url"})
	private String monsterWikiUrl;

	private String quantity;
	private double rarity;
	private int rolls;

	@SerializedName(value = "rarityLabel", alternate = {"rarity_label"})
	private String rarityLabel;

	@SerializedName(value = "dropType", alternate = {"drop_type"})
	private String dropType;

	public String getMonsterName()
	{
		return monsterName == null ? "" : monsterName;
	}

	public String getMonsterWikiUrl()
	{
		return monsterWikiUrl;
	}

	public String getQuantity()
	{
		return quantity == null ? "" : quantity;
	}

	public double getRarity()
	{
		return rarity;
	}

	public int getRolls()
	{
		return rolls;
	}

	public String getRarityLabel()
	{
		return rarityLabel == null ? "" : rarityLabel;
	}

	public String getDropType()
	{
		return dropType == null ? "" : dropType;
	}
}
