package com.runelitemc.bridge;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class McpServerPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(McpServerPlugin.class);
		RuneLite.main(args);
	}
}
