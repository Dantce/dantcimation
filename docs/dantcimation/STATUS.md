# Dantcimation: where the work stands

Last updated: 21 November 2026.

Dantcimation is a fork of [Wemppy4/bbs-fs](https://github.com/Wemppy4/bbs-fs) that lets an AI
animate BBS films through MCP, following a fixed animation style. The style guide and technical
specification live in the Claude doc "Dantcimation — Animation Style Guide & Technical
Specification".

## Branches

| Branch | What it is |
| --- | --- |
| `master` | upstream's Minecraft 1.20.4 code, untouched |
| `1.21.11` | upstream's Minecraft 1.21.11 code, untouched (Fabric loader 0.19.3, Java 21) |
| `dantcimation` | the work branch, cut from `1.21.11` |

## Done

- **Style core** (`src/main/java/dantcimation/style`): the 24 fps frame grid over BBS's 20 tick
  time, the two cadences (on 3s and on 2s), and validators for timing rules T1 to T5 and curve
  rules C1 and C3. Plain Java, no game dependency. Checks: `StyleCoreChecks` in `src/test`.
- **Bridge** (`src/client/java/dantcimation/bridge`): an HTTP server on `127.0.0.1:47110` that
  starts with the game through the `bbs-client-addon` entry point. It writes its port and a
  per-launch token to `run/config/bbs/dantcimation/bridge.json`. Read-only endpoints:
  `GET /status`, `GET /film`, `GET /film/validate?cadence=acting|action`.
- **Verified in the real game** on Minecraft 1.21.11: the branch compiles, the bridge starts,
  refuses requests without the token, and reads the open film (tested on a film with one camera
  clip and no actors).

## Next

1. **Write endpoints on the bridge**: add an actor, write pose keys, write position keys.
2. **Let a same-origin page POST**: the server refuses any request with an `Origin` header;
   allow the bridge's own origin so it can be exercised from a browser tab.
3. **MCP server**: a small separate process that exposes the bridge as MCP tools.
4. Pose library, action recipes, bake to stepped keys, contact-sheet rendering, the remaining
   validator rules, camera tools. See section 13.3 of the style guide.

## Notes for the write endpoints

Read from the BBS source on the `1.21.11` branch; none of this is written yet.

- **Adding an actor.** `ReplayFactory.atPosition(film, position, pitch, yaw)` creates a replay
  with its first position and rotation keys. Give it a form with
  `BBSMod.getForms().create(Link.bbs("model"))`, cast to `ModelForm`, and set `model` to the
  model's id. The shipped player models are under `assets/models/player/` (`steve`,
  `steve_bends`); confirm the exact id string before relying on it.
- **Refreshing the editor after a structural change.** `panel.replayEditor.setFilm(film)`
  rebuilds the replay list, `panel.replayEditor.setReplay(replay)` selects one,
  `panel.getController().createEntities()` rebuilds the actors,
  `panel.replayEditor.updateChannelsList()` rebuilds the tracks. All four are public.
- **Pose keys.** `replay.properties.getOrCreate(form, "pose")` returns the whole-pose channel.
  `channel.insert(tick, pose)` returns the key's index; then
  `channel.get(index).getInterpolation().setInterp(Interpolations.CONST)`. Build the pose with
  `pose.getOrCreate(bone)` and set `translate`, `scale`, `rotate` (radians) and `fix`.
- **Undo and server sync come from notifications.** `KeyframeChannel.insert` and
  `Replays.addReplay` already call `preNotify` and `postNotify`; the film editor's undo handler
  records them and sends synced values to the server. Wrap a multi-step edit in
  `BaseValue.edit(replay, ...)` so it is one undo step and the whole replay is synced. That also
  covers a known gap: a track created on the client by `getOrCreate` is otherwise not sent.
- **Everything runs on the client thread**, as the read endpoints already do.

## Known limits

- This code cannot be compiled outside a machine that can download Minecraft. Each change to a
  class that touches Minecraft or BBS needs a local `runClient` to confirm it.
- Synthetic mouse and keyboard input does not reach the Minecraft window, so the game itself
  has to be driven by a person; the bridge is how a program reaches it.
- The style guide says a pose bone has two rotations, `r` and `r2`. `r2` is legacy and is folded
  into `r` on load; the guide needs that corrected.
