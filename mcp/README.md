# Dantcimation MCP server

Lets an AI client that speaks the Model Context Protocol animate the BBS film that is open in
Minecraft. It is one file with no dependencies; it needs Node 18 or newer.

```
AI client  --MCP over stdio-->  mcp/server.mjs  --HTTP on 127.0.0.1-->  the mod's bridge in Minecraft
```

The mod writes the bridge's address and a token for that launch to
`run/config/bbs/dantcimation/bridge.json`. The server reads that file on every call, so the game
can be restarted freely. If the file is somewhere else, set `DANTCIMATION_BRIDGE_FILE` to it.

## Connecting a client

Any client that can start a local (stdio) MCP server works. The command is
`node <path to this folder>/server.mjs`.

For the Claude desktop app, open Settings, Developer, Edit Config, and add the server to
`claude_desktop_config.json` (use your own path, with doubled backslashes on Windows):

```json
{
  "mcpServers": {
    "dantcimation": {
      "command": "node",
      "args": ["C:\\Users\\you\\IdeaProjects\\dantcimation\\mcp\\server.mjs"]
    }
  }
}
```

Then quit and reopen the app. If the file already lists other servers, add `dantcimation`
beside them inside `mcpServers`.

For Claude Code: `claude mcp add dantcimation -- node /path/to/dantcimation/mcp/server.mjs`.

## Using it

1. Start Minecraft with the mod (`runClient`), load a world, open a film in the BBS film editor
   and leave the editor on screen.
2. Ask the client for an animation. It should call `get_animation_guide` first, which is
   `guide.md` in this folder: the timing rules, the rig's rotation conventions and the working
   method.

## Tools

`get_animation_guide`, `get_status`, `list_models`, `get_film`, `validate_film`, `add_actor`,
`remove_actor`, `get_rig`, `get_form`, `set_form`, `set_pose_keys`, `clear_pose_keys`,
`set_root_keys`, `clear_root_keys`, `set_property_keys`, `clear_property_keys`, `get_camera`,
`add_camera_clip`, `add_camera_move`, `remove_camera_clip`, `clear_camera`,
`snap_keys_to_grid`, `seek`,
`render_frames`.

Each is one request to the bridge; `src/client/java/dantcimation/bridge/Bridge.java` is where
they land.

## Checking it

`node mcp/test.mjs` runs the server through a whole session against a stand-in for the game.
