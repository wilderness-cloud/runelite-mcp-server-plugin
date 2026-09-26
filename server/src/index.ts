#!/usr/bin/env node
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { registerTools } from "./tools.js";
import { DEFAULT_HTTP_PORT, startHttpServer } from "./mcphttp.js";
import { SERVER_NAME, VERSION } from "./version.js";

type Options = { http: boolean; port: number; host: string };

/**
 * stdio by default (Claude Desktop / Claude Code / ZCode spawn the process).
 * `--http [port]` instead serves Streamable HTTP for clients that only take a
 * URL. Note this is *not* the plugin's port 8765 — that one serves plain GET
 * JSON and answers an MCP POST with 405.
 */
function parseArgs(argv: string[]): Options
{
	const opts: Options = {
		http: false,
		port: Number(process.env.MCP_HTTP_PORT ?? DEFAULT_HTTP_PORT),
		host: process.env.MCP_HTTP_HOST ?? "127.0.0.1",
	};

	for (let i = 0; i < argv.length; i++)
	{
		const arg = argv[i];
		const [flag, inlineValue] = arg.includes("=") ? [arg.slice(0, arg.indexOf("=")), arg.slice(arg.indexOf("=") + 1)] : [arg, undefined];
		const next = () => inlineValue ?? (argv[i + 1] && !argv[i + 1].startsWith("-") ? argv[++i] : undefined);

		switch (flag)
		{
			case "--http":
			{
				opts.http = true;
				const value = next();
				if (value !== undefined)
				{
					opts.port = Number(value);
				}
				break;
			}
			case "--port":
			{
				const value = next();
				if (value !== undefined)
				{
					opts.port = Number(value);
				}
				break;
			}
			case "--host":
			{
				const value = next();
				if (value !== undefined)
				{
					opts.host = value;
				}
				break;
			}
			default:
				break;
		}
	}

	if (!Number.isInteger(opts.port) || opts.port < 1 || opts.port > 65535)
	{
		throw new Error(`invalid port: ${opts.port}`);
	}
	return opts;
}

async function main(): Promise<void>
{
	const opts = parseArgs(process.argv.slice(2));
	if (opts.http)
	{
		await startHttpServer(opts.port, opts.host);
		return;
	}

	const server = new McpServer({ name: SERVER_NAME, version: VERSION });
	registerTools(server);
	const transport = new StdioServerTransport();
	await server.connect(transport);
}

main().catch((err) =>
{
	console.error(err);
	process.exit(1);
});
