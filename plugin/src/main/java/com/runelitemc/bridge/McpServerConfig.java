package com.runelitemc.bridge;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("runelitemcpserver")
public interface McpServerConfig extends Config
{
	@ConfigItem(
		keyName = "port",
		name = "HTTP port",
		description = "Port for the localhost read-only state server (server restarts on change)"
	)
	default int port()
	{
		return 8765;
	}
}
