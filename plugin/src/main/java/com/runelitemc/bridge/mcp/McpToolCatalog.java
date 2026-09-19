package com.runelitemc.bridge.mcp;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;

/**
 * Tool contracts live in resources, not in code: one JSON file per tool holding
 * its name, description, inputSchema and outputSchema. That file is the source
 * of truth — the client validates against the same schema this plugin serves,
 * and the handler here only supplies behaviour.
 *
 * @see /mcp/tools in src/main/resources
 */
public final class McpToolCatalog
{
	private static final String PATH = "/mcp/tools/";
	private static final Gson GSON = new Gson();

	private McpToolCatalog()
	{
	}

	public static McpTool load(String name, McpTool.Handler handler)
	{
		JsonObject spec = read(name);

		String declared = spec.has("name") ? spec.get("name").getAsString() : null;
		if (!name.equals(declared))
		{
			throw new IllegalStateException(PATH + name + ".json declares name '" + declared + "'");
		}
		if (!spec.has("description") || spec.get("description").getAsString().isEmpty())
		{
			throw new IllegalStateException(PATH + name + ".json has no description");
		}

		return new McpTool(
			name,
			spec.get("description").getAsString(),
			objectSchema(name, spec, "inputSchema"),
			spec.has("outputSchema") ? objectSchema(name, spec, "outputSchema") : null,
			handler);
	}

	private static JsonObject read(String name)
	{
		String resource = PATH + name + ".json";
		try (InputStream in = McpToolCatalog.class.getResourceAsStream(resource))
		{
			if (in == null)
			{
				throw new IllegalStateException("missing tool schema resource " + resource);
			}
			try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8))
			{
				return GSON.fromJson(reader, JsonObject.class);
			}
		}
		catch (IOException e)
		{
			throw new IllegalStateException("could not read " + resource, e);
		}
	}

	/** MCP requires both schemas to be JSON Schema objects. */
	private static JsonObject objectSchema(String name, JsonObject spec, String key)
	{
		if (!spec.has(key) || !spec.get(key).isJsonObject())
		{
			throw new IllegalStateException(PATH + name + ".json has no " + key);
		}
		JsonObject schema = spec.getAsJsonObject(key);
		String type = schema.has("type") ? schema.get("type").getAsString() : null;
		if (!"object".equals(type))
		{
			throw new IllegalStateException(PATH + name + ".json: " + key + " must be type object, got " + type);
		}
		return schema;
	}
}
