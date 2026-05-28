package com.osrscolorlock.colorlock;

import com.google.gson.Gson;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Assert;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.Rule;

public class ManifestDiskCacheTest
{
	@Rule
	public TemporaryFolder temp = new TemporaryFolder();

	@Test
	public void roundTripSnapshot() throws Exception
	{
		Path file = temp.newFile("items-manifest.json").toPath();
		Gson gson = new Gson();
		ManifestDiskCache cache = new ManifestDiskCache(gson, file);

		ManifestItem row = gson.fromJson(
			"{\"id\":284,\"name\":\"Plague jacket\",\"usableColors\":[\"yellow\"],"
				+ "\"questColorLockKeys\":[\"sheep_herder\"]}",
			ManifestItem.class);

		cache.save("https://example.test/api/v1/items", 91, java.util.List.of(row));

		ManifestDiskCache.Snapshot snap = cache.load();
		Assert.assertNotNull(snap);
		Assert.assertEquals(91, snap.schemaVersion);
		Assert.assertEquals(1, snap.items.size());
		Assert.assertEquals(284, snap.items.get(0).getId());
		Assert.assertTrue(Files.isRegularFile(file));
	}
}
