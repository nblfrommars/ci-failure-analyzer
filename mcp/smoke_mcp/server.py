from mcp.server.fastmcp import FastMCP


mcp = FastMCP("smoke-test")


@mcp.tool()
def ping() -> str:
    """Kiểm tra MCP server có nhận và trả lời tool call hay không."""
    return "pong"


if __name__ == "__main__":
    mcp.run()
