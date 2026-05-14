package com.osrscolorlock.colorlock;

import com.google.gson.Gson;

import javax.inject.Inject;
import javax.inject.Singleton;

import net.runelite.client.callback.ClientThread;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.logging.Level;
import java.util.logging.Logger;

import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;

/**
 * Fetches crew lock color via {@code POST .../api/groups/{slug}/plugin-resolve}.
 */
@Singleton
public class ColorLockGroupSync
{
	private static final Gson GSON = new Gson();
	private static final Logger log = Logger.getLogger(ColorLockGroupSync.class.getName());

	private final ClientThread clientThread;

	private volatile boolean resolvedOk;
	private volatile ColorLockColor resolvedLock;

	@Inject
	public ColorLockGroupSync(ClientThread clientThread)
	{
		this.clientThread = clientThread;
	}

	ColorLockColor effectiveAssignment(ColorLockConfig config)
	{
		if (!config.syncGroupAssignmentFromWeb())
		{
			return config.assignedColor();
		}
		if (!resolvedOk)
		{
			return config.assignedColor();
		}
		if (resolvedLock == null)
		{
			return config.assignedColor();
		}
		return resolvedLock;
	}

	void refreshAsync(ColorLockConfig config, Runnable onFinishClientThread)
	{
		new Thread(() -> {
			doRefreshBlocking(config);
			clientThread.invokeLater(onFinishClientThread);
		}, "osrs-color-lock-groupsync").start();
	}

	private void doRefreshBlocking(ColorLockConfig config)
	{
		if (!config.syncGroupAssignmentFromWeb())
		{
			resolvedOk = false;
			resolvedLock = null;
			return;
		}
		String slug = config.groupSlug() == null ? "" : config.groupSlug().trim();
		String memberCode = config.memberPublicCode() == null ? "" : config.memberPublicCode().trim();
		String base = ColorLockSites.deriveBaseSiteUrl(config.itemsUrl());
		if (slug.isEmpty() || memberCode.isEmpty() || base.isEmpty())
		{
			log.warning("Group sync skipped: set Items JSON URL + group slug + member code");
			resolvedOk = false;
			resolvedLock = null;
			return;
		}

		String encodedSlug = URLEncoder.encode(slug, StandardCharsets.UTF_8)
			.replace("+", "%20");
		try
		{
			String uri = base + "/api/groups/" + encodedSlug + "/plugin-resolve";
			HttpURLConnection conn = (HttpURLConnection) URI.create(uri).toURL().openConnection();
			conn.setRequestMethod("POST");
			conn.setConnectTimeout(20_000);
			conn.setReadTimeout(120_000);
			conn.setDoOutput(true);
			conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
			conn.setRequestProperty("Accept", "application/json");
			conn.setRequestProperty("User-Agent", "osrs-color-lock-runelite/1.0 (https://github.com/unidarkshin/osrs-color-lock)");

			String jp = config.groupJoinPasscode();
			boolean sendPasscode = jp != null && !jp.isEmpty();

			OutputStreamWriter w = new OutputStreamWriter(conn.getOutputStream(), StandardCharsets.UTF_8);
			try
			{
				if (sendPasscode)
				{
					w.write(GSON.toJson(new ResolveBody(memberCode, jp)));
				}
				else
				{
					w.write(GSON.toJson(new ResolveBodyOnlyCode(memberCode)));
				}
			}
			finally
			{
				w.flush();
				w.close();
			}

			int rc = conn.getResponseCode();
			if (rc != HttpURLConnection.HTTP_OK)
			{
				resolvedOk = false;
				resolvedLock = null;
				log.warning("plugin-resolve HTTP " + rc + " for group slug " + slug);
				conn.disconnect();
				return;
			}

			try (BufferedReader reader = new BufferedReader(
				new java.io.InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8)))
			{
				ResolveResponse resp = GSON.fromJson(reader, ResolveResponse.class);
				if (resp != null && resp.member != null)
				{
					resolvedLock = ColorLockColor.fromPaletteKey(resp.member.assignedColor);
				}
				else
				{
					resolvedLock = null;
				}
				resolvedOk = true;
				if (resolvedLock != null)
				{
					log.info(() -> "Group sync OK: server assignedColor=" + resolvedLock.getKey());
				}
				else
				{
					log.info("Group sync OK but server assignedColor is empty — using plugin \"Your color lock\" fallback");
				}
			}
			finally
			{
				conn.disconnect();
			}
		}
		catch (IOException e)
		{
			resolvedOk = false;
			resolvedLock = null;
			log.log(Level.WARNING, "group sync failed", e);
		}
	}

	static final class ResolveBodyOnlyCode
	{
		final String publicCode;

		ResolveBodyOnlyCode(String publicCode)
		{
			this.publicCode = publicCode;
		}
	}

	static final class ResolveBody
	{
		final String publicCode;
		final String joinPasscode;

		ResolveBody(String publicCode, String joinPasscode)
		{
			this.publicCode = publicCode;
			this.joinPasscode = joinPasscode;
		}
	}

	static final class ResolveResponse
	{
		ResolveMember member;
	}

	static final class ResolveMember
	{
		String assignedColor;
	}
}
