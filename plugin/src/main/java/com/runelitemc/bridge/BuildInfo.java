package com.runelitemc.bridge;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Identifies the jar that is actually running. A sideloaded plugin is whatever
 * jar happens to sit in the sideload directory, and the declared version does
 * not move between builds — so without a stamp the only way to tell a current
 * jar from a stale one is to diff the schemas it serves, which is a terrible
 * way to find out mid-deploy. {@code client_status} reports these two fields so
 * one cheap call answers "is the running plugin current?".
 *
 * <p>Values come from /build-info.properties, written at build time. Missing
 * file (an IDE run, say) degrades to "dev" rather than failing.
 */
public final class BuildInfo
{
	private static final String RESOURCE = "/build-info.properties";
	private static final String UNKNOWN = "dev";

	private static final String BUILD;
	private static final String BUILT_AT;

	static
	{
		Properties props = new Properties();
		try (InputStream in = BuildInfo.class.getResourceAsStream(RESOURCE))
		{
			if (in != null)
			{
				props.load(in);
			}
		}
		catch (IOException ignored)
		{
			// Stamp is diagnostic, never a reason to fail startup.
		}
		BUILD = value(props, "build");
		BUILT_AT = value(props, "builtAt");
	}

	private BuildInfo()
	{
	}

	private static String value(Properties props, String key)
	{
		String v = props.getProperty(key);
		return v == null || v.isEmpty() || v.startsWith("@") ? UNKNOWN : v;
	}

	/** Short git SHA, with "-dirty" when built from an unclean tree, or "dev". */
	public static String build()
	{
		return BUILD;
	}

	/** ISO-8601 UTC instant the jar was built, or "dev". */
	public static String builtAt()
	{
		return BUILT_AT;
	}
}
