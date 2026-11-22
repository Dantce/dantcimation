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

## Verified in the game (21 November)

- **Write endpoints** (`FilmEditor`): `POST /film/actors`, `POST /film/actors/<i>/pose-keys`,
  `POST /film/actors/<i>/root-keys`, `POST /film/actors/<i>/pose-keys/clear`, `POST /film/seek`,
  plus `GET /film/actors/<i>/rig` and `GET /models`. Keys are always constant and on the frame
  grid; rotations are sent in degrees. An actor was added to a film and a 12-pose stepped wave
  was written and played back.
- **Same-origin requests**: the server accepts an `Origin` that is its own address, so a browser
  tab on the bridge can POST to it. This is how the endpoints are driven until the MCP server
  exists.

- **`GET /render`**: the film editor's preview as a PNG, or with `frames=0,9,12` a labelled
  contact sheet of those frames (`columns`, `width` optional). It moves the playhead, waits
  for the game to draw, and reads back BBS's own snapshot texture. This is how poses are
  checked: write, render a sheet, look, correct.
- **Film length counts pose keys**: `Film.calculateDuration` now uses
  `Replay.getLastKeyframeTick`, which includes property tracks. Before, a film with pose keys
  and no camera clip was one tick long and would not play. This is the one change to BBS's own
  code so far.
- **A shadow-boxing sequence** on 2s (28 poses, 98 frames: jab, jab-cross, hook, uppercut)
  written through the bridge, checked on contact sheets, no validator violations.

## Verified in the game (21 November, bridge version 3)

- **Any form as an actor**: `POST /film/actors` takes `form`, written the way BBS saves forms
  (`{"id": "bbs:billboard", "texture": "assets:textures/pixel.png", ...}`), instead of `model`.
  `POST /film/actors/<i>/form` swaps an actor's form, `POST /film/actors/<i>/remove` takes the
  actor out, and `GET /film/actors/<i>/form` returns the form's data and the paths of its
  keyable properties (a body part's properties sit under the part's id).
- **Property keys**: `POST /film/actors/<i>/property-keys` with
  `{"property": "color_overlay", "keys": [{"frame": 26, "value": "#FFFFFFFF"}]}` keys any form
  property other than the pose: `visible`, `color`, `color_overlay`, `transform`, a body part's
  `<partId>/color`. Values are written as BBS saves them; a colour may be `#RRGGBB` or
  `#AARRGGBB`. Keys are constant. `.../property-keys/clear` and `.../root-keys/clear` remove
  keys in a frame range.
- **`GET /status`** reports where the view camera is; **`GET /film`** reports where each actor
  stands. An effect that must sit behind an actor is placed from the two.
- **Impact textures** in `assets:textures/dantcimation/`: `impact_spikes`, `impact_burst`,
  `impact_lines`. White on transparent, tinted by the billboard's colour. Drawn by
  `tools/dantcimation/impact_textures.py`.

## MCP server

`mcp/server.mjs`: one dependency-free Node file that speaks MCP over stdio and turns each of
its tools into a bridge request. `mcp/guide.md` is the animation guide it serves to the model
through `get_animation_guide`. `node mcp/test.mjs` checks it against a stand-in bridge. Verified
on 21 November with the Claude desktop app as the client and the real game: status, film,
validation and a rendered contact sheet all came back. Setup is in `mcp/README.md`.

## Camera (bridge version 4; verified in the game 21 November)

- `GET /film/camera` lists the camera's clips and the editor's view (with fov).
- `POST /film/camera/clips` adds a clip by `type` (an idle clip at the current view by default,
  for the whole film) or as `clip` data the way BBS saves it. `.../clips/<i>/remove` and
  `/film/camera/clear` take clips off.
- `POST /film/camera/moves` builds a keyframe clip from keys in film frames, each with an
  `ease`; linear and elastic are refused (rule C3).
- **Playback without a camera clip**: `RunnerCameraController` now runs for the film's length
  instead of the camera's, so an animated film with no camera clip plays. This is the second
  change to BBS's own code.
- The MCP server has the five matching tools. Through them an fov punch-in was laid over the
  locked shot of the shadow-boxing film, on a layer above the idle clip.

## Keys lead their frame (bridge version 5; verified in the game 21 November)

BBS's `KeyframeChannel.findRawSegment` lets a key take effect only once the playhead is past
it, with an exception for whole ticks. A key at tick 22.5 sampled at tick 22.5 still shows the
key before. A 24 fps export samples exactly the frame ticks, so keys written on them would show
a frame late or on time depending on float error: the opposite of a stepped cadence. It is also
why contact sheets showed one-frame effects a frame late.

`FrameGrid.toKeyTick` now writes every key 0.01 tick before its frame (frame 0 stays at 0);
`toTick` is still the frame's own time, used for seeking. `POST /film/regrid` (MCP:
`snap_keys_to_grid`) moves existing on-frame keys to the lead tick. On the shadow-boxing film
it moved 117 keys, and a contact sheet then showed every impact frame on the frame it was keyed
for (26 and 27 for the first jab, nothing on 25 or 28). Camera clips are not regridded: a move
made before this has to be removed and added again.

## Pose and action library (started 22 November)

- `mcp/library.json` holds poses and actions for the player rig, stored facing the model's
  rest direction for a right-handed character; `mcp/library.mjs` expands an entry into pose
  keys, with `turn` (anchor r.y) and `mirror`. MCP tools: `list_library`, `get_library_entry`,
  `apply_pose`, `apply_action` (returns contact frames and where the action ends).
- **Checked on front-view renders in the game**: `idle`, `guard`, `guard_up`, `guard_down`,
  `bob`, `jab`, `cross`, `hook`, `uppercut`. Side views are still to do; a hit reaction and
  locomotion are not written.
- The shadow-boxing film was rebuilt from these through the MCP tools alone (guard up, bob,
  jab, jab-cross, bob, hook-uppercut, guard down), with the contacts on the same frames as
  before; it validates clean and reads better than the hand-keyed version it replaced.
- **Reading keys back** (bridge version 6, verified): `GET /film/actors/<i>/pose-keys` and
  `.../property-keys?property=`, as `get_pose_keys` and `get_property_keys`.
- **Facing the camera**: an actor faces the camera at `turn` = its `bodyYaw` minus the yaw of
  the line to the camera (`get_facing_turn`). `bodyYaw` can differ from `yaw` by tens of
  degrees on an actor made where the player stood, which is what made this look unpredictable.
- Rotation signs settled on renders: `anchor`, `low_body` and `head` r.y positive turn to the
  character's own left; an arm raised forward swings to the character's left with positive
  r.y; `low_body` r.z positive leans to its right.

## Impact frames: how the first ones were built

On the shadow-boxing film, each punch gets 2 or 3 frames on 1s, all through the endpoints above.

- **A backdrop actor that stands at the lens.** Its position is the view camera's (`/status`),
  its yaw is the camera's yaw + 180, so in its own space the camera is the origin, +x is screen
  right and -z is into the picture. Its root is a billboard with alpha 0; everything else is a
  body part: a wall behind the character, a floor, a ceiling and two side walls that hide the
  world, speed lines, a burst behind the character, and edge spikes 0.6 blocks from the lens.
- **Off except on impact**: the root's `visible` is keyed false at frame 0, true on the contact
  frame, false again 2 or 3 frames later.
- **The character flashes** through `color_overlay`: `#FFFFFFFF` white, `#FF000000` black,
  `#00FFFFFF` off. White character on a black burst, then black character on a white burst.
- **Body part ids** must be 8 hex characters with at least one letter, or BBS replaces them.
- **`<partId>/transform` is the part's form transform**, applied inside the body part's own
  transform: key it as a change (scale 1 is the part's size), not as a place in the world.
- Everything is aimed at where the camera was. Until a film has its own camera clip, moving the
  view means building the backdrop again.

## Rig conventions (player models, from BBS's shipped poses)

- Arms: `r.x` positive swings the arm forward and up (90 is straight ahead). `r.z` raises it
  sideways: positive for the right arm, negative for the left (160 is overhead).
- Legs: `r.x` positive swings the leg forward (90 is sitting). `r.z` spreads them: positive for
  the right leg, negative for the left.
- Head: `r.x` positive looks up, `r.y` turns, `r.z` tilts.
- `low_body`: `r.x` positive leans back, negative leans forward; `r.z` leans sideways; `r.y`
  twists the torso.
- `anchor`: `t.y` negative lowers the whole body (in model pixels); `r.y` turns the whole body
  without touching the actor's facing, which is how a pose is turned toward the lens.
- Elbows and knees on `steve_bends` (confirmed on side-view renders): an elbow bends the natural
  way with positive `r.x`, a knee with negative `r.x`.

## Next

1. Turn the impact-frame build into one recipe a tool can call with a list of contact frames.
2. Check that a film with no camera clip plays (the `RunnerCameraController` change), and
   export the shadow-boxing film at 24 fps to confirm the cadence survives the exporter.
3. Animate a whole shot through the MCP tools alone, from a fresh chat. Pose library, action recipes, bake to stepped keys, the remaining validator
   rules. See section 13.3 of the style guide.

## Notes for the write endpoints

Read from the BBS source on the `1.21.11` branch; this is what the write endpoints are built on.

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
- `/render` only shows the film while the film editor is open on screen.
- A film without a camera clip is seen from wherever the editor's camera happens to be, so an
  effect composed toward the lens (an impact backdrop) is only right from that spot.
- The style guide says a pose bone has two rotations, `r` and `r2`. `r2` is legacy and is folded
  into `r` on load; the guide needs that corrected.
